// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import java.beans.Introspector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;

/**
 * Emits a single {@code GeneratedServicesModule} Dagger {@code @Module} that provides all
 * generated contributors as {@code @IntoSet ServiceContractContributor} bindings.
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
 * }
 * }</pre>
 *
 * <p>Package resolution: uses the longest common prefix (LCP) of all generated contributor
 * packages. If all contributors reside in the same package, that package is used. If packages
 * are disjoint, falls back to {@code vertique.generated.services}. The {@code -Avertique.codegen.package}
 * option overrides the LCP resolution when set.
 */
public final class ContributorModuleEmitter {

    private static final String MODULE_SIMPLE_NAME = "GeneratedServicesModule";
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.services.processor.ServiceContractProcessor";
    private static final String FALLBACK_PACKAGE = "vertique.generated.services";

    // --- Dagger type names ---
    private static final ClassName DAGGER_MODULE = ClassName.get("dagger", "Module");
    private static final ClassName DAGGER_PROVIDES = ClassName.get("dagger", "Provides");
    private static final ClassName DAGGER_INTO_SET = ClassName.get("dagger.multibindings", "IntoSet");
    private static final ClassName SERVICE_CONTRACT_CONTRIBUTOR =
            ClassName.get("dev.vertique.services", "ServiceContractContributor");

    private final CodegenContext ctx;
    private final List<ContractModel> models = new ArrayList<>();

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
        models.add(model);
    }

    /**
     * Returns {@code true} if at least one model has been accumulated.
     *
     * @return {@code true} when there are models to emit
     */
    public boolean hasModels() {
        return !models.isEmpty();
    }

    /**
     * Emits the {@code GeneratedServicesModule} for all accumulated models.
     *
     * <p>Must only be called when {@link #hasModels()} returns {@code true}.
     */
    public void emit() {
        if (models.isEmpty()) {
            return;
        }

        String packageName = resolvePackage();

        TypeSpec.Builder moduleBuilder = TypeSpec.classBuilder(MODULE_SIMPLE_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addAnnotation(AnnotationSpec.builder(DAGGER_MODULE).build());

        // Dedupe by contract FQN: the processor now passes one representative model per contract
        // group, but this guard protects against accidental duplicate models from mixed rounds.
        Set<String> seenContracts = new LinkedHashSet<>();
        for (ContractModel model : models) {
            String contractFqn = model.contractType().getQualifiedName().toString();
            if (!seenContracts.add(contractFqn)) {
                // Already emitted a binding for this contract — skip the duplicate
                continue;
            }

            String contractPkg = ctx.packageNameOf(model.contractType());
            String contributorSimpleName = model.contractType().getSimpleName() + "_ContractContributor";
            ClassName contributorClass = ClassName.get(contractPkg, contributorSimpleName);

            // Method name: decapitalised contract simple name, keyword-guarded
            String methodName =
                    bindingMethodName(model.contractType().getSimpleName().toString());

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

        JavaFile javaFile = JavaFile.builder(packageName, moduleBuilder.build()).build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write %s: %s", MODULE_SIMPLE_NAME, e.getMessage());
        }
    }

    // --- Internal helpers ---

    /**
     * Resolves the output package using LCP of contributor packages, with option override support.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code -Avertique.codegen.package} option if set.</li>
     *   <li>LCP of all contributor contract packages.</li>
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

        // LCP of all contributor packages
        String lcp = models.stream()
                .map(m -> ctx.packageNameOf(m.contractType()))
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
}
