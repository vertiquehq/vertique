// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.TypeVisibility;
import dev.vertique.codegen.services.processor.scan.ClientContractModel;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import java.beans.Introspector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.processing.Generated;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Emits a single {@code GeneratedServicesModule} Dagger {@code @Module} that provides all
 * generated contributors as {@code @IntoSet ServiceContractContributor} bindings and all eligible
 * contract-only client models as singleton typed-client bindings.
 *
 * <p>Generated module shape:
 * <pre>{@code
 * @Generated("dev.vertique.codegen.services.processor.ServiceContractProcessor")
 * @Module
 * public abstract class GeneratedServicesModule {
 *     @Provides @IntoSet
 *     static ServiceContractContributor userService(UserService_ContractContributor impl) {
 *         return impl;
 *     }
 *
 *     @Provides @Singleton
 *     static UserService provideUserServiceClient(ServiceClientFactory clients) {
 *         return clients.create(UserService.class);
 *     }
 * }
 * }</pre>
 *
 * <p>Package resolution: uses the longest common prefix (LCP) of all generated contributor and
 * client contract packages. If all contracts reside in the same package, that package is used. If
 * packages are disjoint, falls back to {@code vertique.generated.services}. The
 * {@code -Avertique.codegen.package} option overrides the LCP resolution when set.
 */
public final class ContributorModuleEmitter {

    private static final String MODULE_SIMPLE_NAME = "GeneratedServicesModule";
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.services.processor.ServiceContractProcessor";
    private static final String FALLBACK_PACKAGE = "vertique.generated.services";

    // --- Dagger type names ---
    private static final ClassName DAGGER_MODULE = ClassName.get("dagger", "Module");
    private static final ClassName DAGGER_PROVIDES = ClassName.get("dagger", "Provides");
    private static final ClassName DAGGER_INTO_SET = ClassName.get("dagger.multibindings", "IntoSet");
    private static final ClassName JAKARTA_SINGLETON = ClassName.get("jakarta.inject", "Singleton");
    private static final ClassName SERVICE_CONTRACT_CONTRIBUTOR =
            ClassName.get("dev.vertique.services", "ServiceContractContributor");
    private static final ClassName SERVICE_CLIENT_FACTORY =
            ClassName.get("dev.vertique.services", "ServiceClientFactory");

    private final CodegenContext ctx;
    private final List<ContractModel> contributorModels = new ArrayList<>();
    private final List<ClientContractModel> clientModels = new ArrayList<>();

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ContributorModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Accumulates a validated contract model for inclusion in the generated module.
     *
     * @param model the validated model; must not be {@code null}
     */
    public void add(ContractModel model) {
        contributorModels.add(model);
    }

    /**
     * Accumulates an eligible contract-only client model for inclusion in the generated module.
     *
     * @param model the validated client model; must not be {@code null}
     */
    public void addClient(ClientContractModel model) {
        clientModels.add(model);
    }

    /**
     * Returns {@code true} if at least one model has been accumulated.
     *
     * @return {@code true} when there are models to emit
     */
    public boolean hasModels() {
        return !contributorModels.isEmpty() || !clientModels.isEmpty();
    }

    /**
     * Emits the {@code GeneratedServicesModule} for all accumulated models.
     *
     * <p>Must only be called when {@link #hasModels()} returns {@code true}.
     */
    public void emit() {
        if (!hasModels()) {
            return;
        }

        String packageName = resolvePackage();

        TypeSpec.Builder moduleBuilder = TypeSpec.classBuilder(MODULE_SIMPLE_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addAnnotation(AnnotationSpec.builder(DAGGER_MODULE).build());

        Map<String, ContractModel> contributors = uniqueContributors();
        Map<String, ClientContractModel> clients = uniqueClients();
        Set<String> usedMethodNames = new LinkedHashSet<>();
        for (Map.Entry<String, ContractModel> entry : contributors.entrySet()) {
            ContractModel model = entry.getValue();
            String contractPkg = ctx.packageNameOf(model.contractType());
            // The contributor itself is always public and lands in the contract's own package, so
            // contract visibility never blocks this binding. The unnamed package is the one
            // exception: it cannot be named from the named package the module resolves to. That is
            // an error rather than a skip — dropping a contributor unregisters the service, which
            // fails at runtime instead of at Dagger's compile-time graph validation.
            if (contractPkg.isEmpty() && !packageName.isEmpty()) {
                ctx.diagnostics()
                        .error(
                                model.contractType(),
                                "@ServiceContract %s is in the unnamed package, so its generated"
                                        + " contributor cannot be referenced from package '%s', where %s is"
                                        + " generated. Move the contract into a named package.",
                                entry.getKey(),
                                packageName,
                                MODULE_SIMPLE_NAME);
                continue;
            }
            String contributorSimpleName = model.contractType().getSimpleName() + "_ContractContributor";
            ClassName contributorClass = ClassName.get(contractPkg, contributorSimpleName);

            String methodName = uniqueBindingMethodName(
                    bindingMethodName(model.contractType().getSimpleName().toString()),
                    entry.getKey(),
                    usedMethodNames);

            MethodSpec provideMethod = MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.STATIC)
                    .addAnnotation(AnnotationSpec.builder(DAGGER_PROVIDES).build())
                    .addAnnotation(AnnotationSpec.builder(DAGGER_INTO_SET).build())
                    .returns(SERVICE_CONTRACT_CONTRIBUTOR)
                    .addParameter(contributorClass, "impl")
                    .addStatement("return impl")
                    .build();

            moduleBuilder.addMethod(provideMethod);
        }

        for (Map.Entry<String, ClientContractModel> entry : clients.entrySet()) {
            ClientContractModel model = entry.getValue();
            TypeElement contractType = model.contractType();
            ClassName contractClass = ClassName.get(contractType);
            // Contributor bindings above are safe regardless of contract visibility: they reference
            // the generated public {Contract}_ContractContributor, not the contract. A client binding
            // returns the contract itself, so an unreferenceable contract would emit a module that
            // does not compile — breaking the build even without installing it in a @Component.
            if (!TypeVisibility.isReferenceableFrom(contractType, ctx.packageNameOf(contractType), packageName)) {
                ctx.diagnostics()
                        .mandatoryWarning(
                                contractType,
                                "@ServiceContract %s is not accessible from package '%s', where %s is"
                                        + " generated, so no typed client is bound for it. Make the contract"
                                        + " (and any enclosing type) public, set -A%s to a package it is visible"
                                        + " from, or provide the client with a hand-written @Provides method.",
                                contractClass.canonicalName(),
                                packageName,
                                MODULE_SIMPLE_NAME,
                                CodegenContext.OPTION_OUTPUT_PACKAGE);
                continue;
            }
            String methodName = uniqueBindingMethodName(
                    clientBindingMethodName(contractType.getSimpleName().toString()), entry.getKey(), usedMethodNames);

            MethodSpec provideMethod = MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.STATIC)
                    .addAnnotation(AnnotationSpec.builder(DAGGER_PROVIDES).build())
                    .addAnnotation(AnnotationSpec.builder(JAKARTA_SINGLETON).build())
                    .returns(contractClass)
                    .addParameter(SERVICE_CLIENT_FACTORY, "serviceClientFactory")
                    .addStatement("return serviceClientFactory.create($T.class)", contractClass)
                    .build();

            moduleBuilder.addMethod(provideMethod);
        }

        JavaFile javaFile = JavaFile.builder(packageName, moduleBuilder.build()).build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write %s: %s", MODULE_SIMPLE_NAME, e.getMessage());
        }
    }

    // --- Internal helpers ---

    /**
     * Resolves the output package using LCP of contract packages, with option override support.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code -Avertique.codegen.package} option if set.</li>
     *   <li>LCP of all contributor and client contract packages.</li>
     *   <li>Fallback: {@code vertique.generated.services}.</li>
     * </ol>
     *
     * @return the resolved package name; never {@code null}
     */
    private String resolvePackage() {
        // Check option override
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }

        String lcp = contractPackages().stream()
                .reduce(ContributorModuleEmitter::longestCommonPrefix)
                .orElse(FALLBACK_PACKAGE);

        return lcp.isBlank() ? FALLBACK_PACKAGE : lcp;
    }

    /**
     * Computes the longest common dot-delimited package prefix of two package names.
     *
     * @param a the first package name
     * @param b the second package name
     * @return the longest common prefix, or {@code ""} if there is none
     */
    static String longestCommonPrefix(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        String[] partsA = a.split("\\.", -1);
        String[] partsB = b.split("\\.", -1);
        int limit = Math.min(partsA.length, partsB.length);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (!partsA[i].equals(partsB[i])) {
                break;
            }
            if (!prefix.isEmpty()) {
                prefix.append('.');
            }
            prefix.append(partsA[i]);
        }
        return prefix.toString();
    }

    /**
     * Produces a Dagger {@code @Provides} method name from a contract simple class name.
     *
     * <p>Uses {@link Introspector#decapitalize} so that acronym-leading names are handled
     * correctly: {@code URLProvider} → {@code URLProvider} (not {@code uRLProvider}).
     * If the result collides with a Java keyword or reserved word, a trailing {@code _} is
     * appended, mirroring the convention in {@code DaggerModuleWriter.bindingMethodName}.
     *
     * @param simpleName the class simple name; must not be empty
     * @return a valid Java identifier suitable for use as a {@code @Provides} method name
     */
    public static String bindingMethodName(String simpleName) {
        String name = Introspector.decapitalize(simpleName);
        return SourceVersion.isName(name) ? name : name + "_";
    }

    /**
     * Produces the conventional typed-client provider name.
     *
     * @param simpleName the contract simple name; must not be empty
     * @return a valid Java identifier base name
     */
    public static String clientBindingMethodName(String simpleName) {
        return "provide" + simpleName + "Client";
    }

    private Map<String, ContractModel> uniqueContributors() {
        Map<String, ContractModel> result = new TreeMap<>();
        for (ContractModel model : contributorModels) {
            result.putIfAbsent(model.contractType().getQualifiedName().toString(), model);
        }
        return result;
    }

    private Map<String, ClientContractModel> uniqueClients() {
        Map<String, ClientContractModel> result = new TreeMap<>();
        for (ClientContractModel model : clientModels) {
            result.putIfAbsent(model.contractType().getQualifiedName().toString(), model);
        }
        return result;
    }

    private Set<String> contractPackages() {
        Set<String> result = new TreeSet<>();
        uniqueContributors().values().forEach(model -> result.add(ctx.packageNameOf(model.contractType())));
        uniqueClients().values().forEach(model -> result.add(ctx.packageNameOf(model.contractType())));
        return result;
    }

    private static String uniqueBindingMethodName(String baseName, String contractFqn, Set<String> usedNames) {
        if (usedNames.add(baseName)) {
            return baseName;
        }

        String suffix = contractFqn.replace('.', '_');
        String candidate = baseName + "_" + suffix;
        int ordinal = 2;
        while (!usedNames.add(candidate)) {
            candidate = baseName + "_" + suffix + "_" + ordinal++;
        }
        return candidate;
    }
}
