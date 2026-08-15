// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.delayed.processor.DelayedJobContractModel;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.SourceVersion;

/**
 * Emitter that generates an aggregate Dagger {@code @Module}
 * ({@code GeneratedDelayedJobClientsModule}) containing one {@code @Provides @Singleton} method per
 * validated {@code @DelayedJobContract} interface, so an application no longer hand-writes a
 * provider per contract.
 *
 * <p><strong>Delegate to the factory, never return the proxy directly.</strong> Every generated
 * binding body is:
 * <pre>{@code
 * @Provides
 * @Singleton
 * static DeliverWebhookJob provideDeliverWebhookJobClient(DelayedJobClientFactory factory) {
 *     return factory.create(DeliverWebhookJob.class);
 * }
 * }</pre>
 * The body MUST delegate to {@code DelayedJobClientFactory.create({Contract}.class)}. The factory
 * owns the contract checks and the per-contract config merge (annotation defaults overlaid with
 * {@code delayedJob.contracts.{name}.*}); a binding that constructed the generated proxy directly
 * would bypass both. The factory internally selects the generated proxy via {@code Class.forName},
 * so the injected instance is still the zero-reflection proxy.
 *
 * <p>Package resolution:
 * <ol>
 *   <li>The {@code -Avertique.codegen.package} option if set and non-blank.</li>
 *   <li>The longest-common-package-prefix of all contract packages.</li>
 *   <li>{@value #DEFAULT_PACKAGE} when the longest-common prefix is empty.</li>
 * </ol>
 * This option controls the module only — {@code DelayedJobProxyEmitter} keeps each proxy pinned to
 * its contract's own package because the runtime lookup derives the proxy name from the contract's
 * binary name.
 *
 * <p>Two contracts sharing a simple name across packages would otherwise produce two
 * {@code provide{Name}Client} methods differing only in return type, which does not compile. Rather
 * than failing the build, the second and later binding methods are suffixed with the contract's
 * flattened fully-qualified name.
 */
public final class DelayedJobClientsModuleEmitter {

    /** Simple name for the generated Dagger module. */
    static final String MODULE_SIMPLE_NAME = "GeneratedDelayedJobClientsModule";

    /** Fallback package when the longest-common-prefix of all contract packages is empty. */
    static final String DEFAULT_PACKAGE = "vertique.generated.delayedjob";

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor";

    private static final ClassName DELAYED_JOB_CLIENT_FACTORY =
            ClassName.get("dev.vertique.job.delayed", "DelayedJobClientFactory");

    private final CodegenContext ctx;

    /**
     * Constructs a {@code DelayedJobClientsModuleEmitter} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public DelayedJobClientsModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Public API ---

    /**
     * Emits the {@code GeneratedDelayedJobClientsModule} for the given validated contracts.
     *
     * <p>If {@code contracts} is empty, returns immediately without emitting anything — an
     * application with no {@code @DelayedJobContract} gets no module, and no empty {@code @Module}
     * to install.
     *
     * @param contracts the list of validated contract models; must not be {@code null}
     */
    public void emit(List<DelayedJobContractModel> contracts) {
        if (contracts.isEmpty()) {
            return;
        }

        Map<String, DelayedJobContractModel> unique = uniqueByQualifiedName(contracts);
        String pkg = resolvePackage(unique.values());
        ClassName moduleName = ClassName.get(pkg, MODULE_SIMPLE_NAME);
        DaggerModuleWriter writer =
                DaggerModuleWriter.named(moduleName).generatedBy(PROCESSOR_FQN).concrete();

        ParameterSpec factoryParam =
                ParameterSpec.builder(DELAYED_JOB_CLIENT_FACTORY, "factory").build();
        Set<String> usedMethodNames = new LinkedHashSet<>();

        for (Map.Entry<String, DelayedJobContractModel> entry : unique.entrySet()) {
            ClassName contractType = ClassName.get(entry.getValue().contractType());
            String methodName = uniqueBindingMethodName(
                    clientBindingMethodName(contractType.simpleName()), entry.getKey(), usedMethodNames);
            CodeBlock body = CodeBlock.of("return factory.create($T.class);\n", contractType);
            writer.addSingletonProvides(contractType, methodName, body, factoryParam);
        }

        JavaFile file = writer.build();
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            contracts.get(0).contractType(),
                            "Failed to write generated module %s: %s",
                            moduleName.canonicalName(),
                            e.getMessage());
        }
    }

    // --- Internal helpers ---

    /**
     * Indexes the contracts by fully-qualified name, dropping duplicates so a contract scanned twice
     * in one round contributes a single binding. The {@link TreeMap} makes emission order
     * deterministic, which in turn makes the disambiguating method-name suffixes stable across
     * builds.
     *
     * @param contracts the validated contracts; must not be {@code null}
     * @return the contracts keyed by fully-qualified contract name, in name order
     */
    private static Map<String, DelayedJobContractModel> uniqueByQualifiedName(List<DelayedJobContractModel> contracts) {
        Map<String, DelayedJobContractModel> result = new TreeMap<>();
        for (DelayedJobContractModel contract : contracts) {
            result.putIfAbsent(contract.contractType().getQualifiedName().toString(), contract);
        }
        return result;
    }

    /**
     * Resolves the output package for the generated module.
     *
     * @param contracts the deduplicated validated contracts; must not be empty
     * @return the resolved package name; never {@code null}
     */
    private String resolvePackage(Iterable<DelayedJobContractModel> contracts) {
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }

        String lcp = null;
        for (DelayedJobContractModel contract : contracts) {
            String pkg = ctx.packageNameOf(contract.contractType());
            lcp = lcp == null ? pkg : longestCommonPackagePrefix(lcp, pkg);
        }

        return lcp == null || lcp.isEmpty() ? DEFAULT_PACKAGE : lcp;
    }

    /**
     * Computes the longest common package prefix of two package names by comparing dot-separated
     * segments.
     *
     * <p>For example, {@code "com.example.orders"} and {@code "com.example.payments"} share the
     * prefix {@code "com.example"}; {@code "com.foo"} and {@code "org.bar"} share no common prefix
     * and return an empty string.
     *
     * @param a the first package name; must not be {@code null}
     * @param b the second package name; must not be {@code null}
     * @return the longest common prefix, or an empty string when there is none
     */
    static String longestCommonPackagePrefix(String a, String b) {
        String[] segA = a.isEmpty() ? new String[0] : a.split("\\.");
        String[] segB = b.isEmpty() ? new String[0] : b.split("\\.");
        int common = 0;
        int limit = Math.min(segA.length, segB.length);
        while (common < limit && segA[common].equals(segB[common])) {
            common++;
        }
        if (common == 0) {
            return "";
        }
        return String.join(".", Arrays.copyOf(segA, common));
    }

    /**
     * Derives the conventional typed-client provider name — {@code "DeliverWebhookJob"} becomes
     * {@code "provideDeliverWebhookJobClient"} — matching the services processor's client bindings.
     *
     * @param simpleName the simple name of the contract interface; must not be empty
     * @return the derived method name, suffixed with {@code _} if it would not be a valid identifier
     */
    static String clientBindingMethodName(String simpleName) {
        String name = "provide" + simpleName + "Client";
        return SourceVersion.isName(name) ? name : name + "_";
    }

    /**
     * Returns {@code baseName} when it is still free, otherwise appends the contract's flattened
     * fully-qualified name (and, if that too is taken, an ordinal) so every binding method in the
     * module has a distinct name.
     *
     * @param baseName    the preferred method name
     * @param contractFqn the fully-qualified contract name used to disambiguate
     * @param usedNames   the names already claimed in this module; mutated by this call
     * @return a method name not previously present in {@code usedNames}
     */
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
