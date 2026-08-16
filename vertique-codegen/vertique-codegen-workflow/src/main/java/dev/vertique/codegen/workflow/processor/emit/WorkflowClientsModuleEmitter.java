// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.TypeVisibility;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that generates an aggregate Dagger {@code @Module} ({@code GeneratedWorkflowClientsModule})
 * containing one {@code @Provides @Singleton} method per validated {@code @WorkflowContract}
 * interface.
 *
 * <p><strong>Invariant D5 — delegate to the factory, never return the proxy directly.</strong>
 * Every generated binding body is:
 * <pre>{@code
 * @Provides
 * @Singleton
 * static OrderWorkflow provideOrderWorkflow(WorkflowClientFactory factory) {
 *     return factory.create(OrderWorkflow.class);
 * }
 * }</pre>
 * The body MUST delegate to {@code WorkflowClientFactory.create({Contract}.class)} and MUST NOT
 * return the generated proxy directly. Registry/plan validation lives only inside
 * {@code WorkflowClientFactory.create(…)}; a binding that returned the proxy directly would
 * bypass it. The factory internally selects the generated proxy via {@code Class.forName}, so the
 * injected instance is still the zero-reflection proxy AND has passed validation.
 *
 * <p>Package resolution:
 * <ol>
 *   <li>The {@code -Avertique.codegen.package} option if set and non-blank.</li>
 *   <li>The longest-common-package-prefix of all contract packages.</li>
 *   <li>{@value #DEFAULT_PACKAGE} when the longest-common prefix is empty.</li>
 * </ol>
 *
 * <p>Simple-name collisions (two contracts with the same simple name) are detected before emit.
 * Each colliding contract gets a compiler error message and the module is not emitted.
 *
 * <h2>Related ADRs</h2>
 * <ul>
 *   <li>ADR-0025 — generated module inclusion model</li>
 *   <li>ADR-0073 — {@code WorkflowClientFactory} proxy selection and naming</li>
 * </ul>
 */
public final class WorkflowClientsModuleEmitter {

    /** Simple name for the generated Dagger module. */
    static final String MODULE_SIMPLE_NAME = "GeneratedWorkflowClientsModule";

    /** Fallback package when the longest-common-prefix of all contract packages is empty. */
    static final String DEFAULT_PACKAGE = "vertique.generated.workflow";

    private static final ClassName WORKFLOW_CLIENT_FACTORY =
            ClassName.get("dev.vertique.workflow.client", "WorkflowClientFactory");

    private final CodegenContext ctx;

    /**
     * Constructs a {@code WorkflowClientsModuleEmitter} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public WorkflowClientsModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Public API ---

    /**
     * Emits the {@code GeneratedWorkflowClientsModule} for the given validated contracts.
     *
     * <p>If {@code contracts} is empty, returns immediately without emitting anything.
     * If two contracts share the same simple name a compiler error is emitted per colliding
     * contract and the method returns without emitting the module (the module would not compile).
     * Otherwise, a concrete {@code @Module} is written via the {@link DaggerModuleWriter} to the
     * compilation {@link javax.annotation.processing.Filer}.
     *
     * @param contracts the list of validated contract models; must not be {@code null}
     */
    public void emit(List<ContractModel> contracts) {
        if (contracts.isEmpty()) {
            return;
        }
        // Resolved from every valid contract before any is filtered out, so skipping an
        // unreferenceable contract never relocates the module.
        String pkg = resolvePackage(contracts);
        List<ContractModel> bindable = referenceableFrom(contracts, pkg);
        if (bindable.isEmpty()) {
            return;
        }
        // Collisions are checked on the contracts that actually get bindings. Checking the
        // unfiltered list would hard-fail on a clash with a contract that is about to be skipped —
        // a collision that never reaches the generated source.
        if (hasSimpleNameCollisions(bindable)) {
            return;
        }

        ClassName moduleName = ClassName.get(pkg, MODULE_SIMPLE_NAME);
        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();

        ParameterSpec factoryParam =
                ParameterSpec.builder(WORKFLOW_CLIENT_FACTORY, "factory").build();

        for (ContractModel contract : bindable) {
            ClassName contractType = ClassName.get(contract.contractType());
            String methodName = provideMethodName(contractType.simpleName());
            CodeBlock body = CodeBlock.of("return factory.create($T.class);", contractType);
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
     * Detects two or more contracts whose simple names are identical — which would produce
     * conflicting {@code provide{Name}} methods in the module — and emits a compiler error for
     * each colliding contract. Returns {@code true} when any collision was found so the caller can
     * skip the emit phase.
     *
     * @param contracts the contracts to check; must not be {@code null}
     * @return {@code true} if at least one collision was detected
     */
    private boolean hasSimpleNameCollisions(List<ContractModel> contracts) {
        Map<String, ContractModel> bySimpleName = new HashMap<>();
        boolean anyConflict = false;
        for (ContractModel c : contracts) {
            ClassName cn = ClassName.get(c.contractType());
            String simple = cn.simpleName();
            ContractModel prior = bySimpleName.putIfAbsent(simple, c);
            if (prior != null) {
                ClassName priorCn = ClassName.get(prior.contractType());
                ctx.diagnostics()
                        .error(
                                c.contractType(),
                                "@WorkflowContract %s collides with %s in the generated %s"
                                        + " — both produce the same provider method (simple name '%s')."
                                        + " Rename one contract interface to resolve.",
                                cn.canonicalName(),
                                priorCn.canonicalName(),
                                MODULE_SIMPLE_NAME,
                                simple);
                anyConflict = true;
            }
        }
        return anyConflict;
    }

    /**
     * Filters the contracts down to those the generated module can name, warning once per skipped
     * contract.
     *
     * <p>A contract that is not {@code public} (or is nested in a non-public type) outside the
     * module's package cannot be referenced from it, and neither can one in the unnamed package.
     * Emitting the binding anyway produces a module that does not compile, which would break the
     * application's build merely by putting this processor on the annotation-processor path — javac
     * compiles generated sources in the same task, so installing the module in a {@code @Component}
     * is not required to hit it. A skipped contract keeps working through a hand-written provider.
     *
     * @param contracts     the validated contracts; must not be {@code null}
     * @param modulePackage the already-resolved package the module will be written to
     * @return the subset the module may reference, in the original order; possibly empty
     */
    private List<ContractModel> referenceableFrom(List<ContractModel> contracts, String modulePackage) {
        List<ContractModel> bindable = new ArrayList<>(contracts.size());
        for (ContractModel contract : contracts) {
            TypeElement contractType = contract.contractType();
            if (TypeVisibility.isReferenceableFrom(contractType, modulePackage)) {
                bindable.add(contract);
                continue;
            }
            ctx.diagnostics()
                    .mandatoryWarning(
                            contractType,
                            "@WorkflowContract %s is not accessible from package '%s', where %s is"
                                    + " generated, so it is left unbound. Make the contract (and any enclosing"
                                    + " type) public, set -A%s to a package it is visible from, or provide it"
                                    + " with a hand-written @Provides method.",
                            ClassName.get(contractType).canonicalName(),
                            modulePackage,
                            MODULE_SIMPLE_NAME,
                            CodegenContext.OPTION_OUTPUT_PACKAGE);
        }
        return bindable;
    }

    /**
     * Resolves the output package for the generated module.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>The {@code -Avertique.codegen.package} option when set and non-blank.</li>
     *   <li>The longest-common-package-prefix of all contract package names.</li>
     *   <li>{@link #DEFAULT_PACKAGE} when the LCP is empty.</li>
     * </ol>
     *
     * @param contracts the validated contracts; must not be empty
     * @return the resolved package name; never {@code null}
     */
    private String resolvePackage(List<ContractModel> contracts) {
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }

        String lcp = contracts.stream()
                .map(c -> ctx.packageNameOf(c.contractType()))
                .reduce(WorkflowClientsModuleEmitter::longestCommonPackagePrefix)
                .orElse("");

        return lcp.isEmpty() ? DEFAULT_PACKAGE : lcp;
    }

    /**
     * Computes the longest common package prefix of two package names by comparing
     * dot-separated segments.
     *
     * <p>For example, {@code "com.example.orders"} and {@code "com.example.payments"} share
     * the prefix {@code "com.example"}; {@code "com.foo"} and {@code "org.bar"} share no common
     * prefix and return an empty string.
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
     * Derives the {@code @Provides} method name by prepending {@code "provide"} to the contract
     * simple name.
     *
     * <p>For example, {@code "OrderWorkflow"} → {@code "provideOrderWorkflow"}.
     * If the result would be a Java keyword or otherwise invalid identifier name, a trailing
     * underscore is appended.
     *
     * @param simpleName the simple name of the contract interface
     * @return the derived method name
     */
    private static String provideMethodName(String simpleName) {
        String name = "provide" + simpleName;
        return SourceVersion.isName(name) ? name : name + "_";
    }
}
