// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.sanitization.processing.PolicyAxis;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link McpInputPolicyResolver}, wired to {@code apt.ElementInvocationPolicies}, resolves the same
 * invocation-policy matrix as {@code ElementInvocationPoliciesTest},
 * {@code ReflectiveInvocationPoliciesTest}, and {@code JaxRsInvocationPolicyMatrixTest} — end-to-end
 * through the real {@link McpToolProcessor} pipeline, asserting the generated {@code Input} carrier's
 * per-component {@code @Canonicalize}/{@code @Sanitize} annotations (not the adapter's return value
 * directly, and not a {@code POL0} literal: unlike the JAX-RS/REST codegen carrier, MCP's generated
 * carrier emits the resolved chains as normalized base annotations directly on the record component,
 * never a literal constant — see {@code McpToolInvokerEmitter#addPolicyAnnotation}).
 *
 * <p>Every fixture reuses the {@link InvocationPolicyScenarios} shapes (interface/superclass
 * hierarchies, the composed-annotation row, the class/method/parameter tiers), decorated with the
 * {@code @McpTool}/{@code @McpToolParam} wiring every row needs to compile at all through the real
 * processor. One structural difference from {@code InvocationPolicyScenarios} and the JAX-RS matrix:
 * {@code @McpTool} is an annotation-processor entry point, so {@code roundEnv.getElementsAnnotatedWith}
 * only ever finds it where it is <em>literally</em> declared — every row's concrete tool class
 * therefore declares its own {@code @McpTool}/{@code @McpToolParam}-annotated method (mirroring the
 * scenario's own concrete {@code @Override}), while the invocation-policy annotations
 * ({@code @Sanitize}/{@code @SkipSanitization}/{@code @Canonicalize}/{@code @SkipCanonicalization})
 * stay exactly where {@link InvocationPolicyScenarios} places them (interface, superclass, or
 * method/parameter) for the hierarchy walk to discover.
 *
 * <p>Because every row's tool method has exactly one parameter (mirroring {@code bar(String)}
 * uniformly, like {@link InvocationPolicyScenarios}), the single generated carrier component's
 * resolved chain is always the row's <em>parameter-axis</em> (final, combined) expectation — the
 * same values {@code JaxRsInvocationPolicyMatrixTest}'s {@code row()} helper asserts as {@code POL0}.
 *
 * <p><strong>Non-conflict rows</strong> ({@link #generatedCarrierMatchesTheMatrix}, {@code no-policy}
 * through {@code independent-axes}, {@code interface-method-skip}, {@code override-replaces-chain}):
 * asserts the generated {@code Input} carrier's sole component carries
 * {@code @Canonicalize(X.class)}/{@code @Sanitize(X.class)} for each non-empty resolved axis, and
 * that the annotation is entirely absent (never emitted, per {@code addPolicyAnnotation}'s
 * empty-chain skip) for an empty axis. The {@code interface-method-skip} fixture also carries a
 * class-level {@code @Sanitize(A.class)} on top of the scenario's interface method-level
 * {@code @SkipSanitization}: the hierarchy walk resolves this row to the same {@code []} expectation
 * as every other row in the matrix.
 *
 * <p><strong>Conflict rows</strong> ({@link #conflictOnMethodIsACompileErrorNamingTheMethod}
 * {@code same-method-conflict}, {@link #conflictOnParameterIsACompileError}
 * {@code same-parameter-conflict}, {@link #overrideSkipOverInheritedAdditiveIsACompileError}
 * {@code override-skip-conflict}, {@link #subclassAdditiveOverSuperclassSkipIsACompileError}
 * {@code supertype-skip-conflict}): asserts {@code assertFailed()} plus the expected diagnostic.
 * {@code same-method-conflict} and {@code override-skip-conflict} assert the exact message via
 * {@link InvocationPolicyScenarios#expectedConflictMessage} (the site names match this test's own
 * fixture class/method names); {@code same-parameter-conflict} and {@code supertype-skip-conflict}
 * assert {@code "Conflicting @Sanitize"} plus the real declaration sites, mirroring
 * {@code JaxRsInvocationPolicyMatrixTest}: no exact literal is pinned for parameter-level/type-level
 * conflicts.
 */
class McpInvocationPolicyMatrixTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("generated Input carrier component matches the matrix's parameter-axis expectation")
    @MethodSource("nonConflictRows")
    void generatedCarrierMatchesTheMatrix(ScenarioRow row) {
        ProcessorTestHarness.Result result = compile(row.sources().toArray(JavaFileObject[]::new));

        result.assertSuccess();
        assertResolvedPolicies(result, row.invokerFqn(), row.canonicalizers(), row.sanitizers());
    }

    @Test
    @DisplayName("same-method-conflict: @Sanitize + @SkipSanitization on the same method is a compile error "
            + "naming the method")
    void conflictOnMethodIsACompileErrorNamingTheMethod() {
        var result = compile(SourceFiles.inline("dev.vertique.test.mcp.samemethodconflict.SameMethodConflict", """
                package dev.vertique.test.mcp.samemethodconflict;

                import dev.vertique.sanitization.Sanitize;
                import dev.vertique.sanitization.SkipSanitization;
                import dev.vertique.sanitization.processing.testkit.B;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;

                public class SameMethodConflict {

                    @Inject
                    public SameMethodConflict() {}

                    @Sanitize(B.class)
                    @SkipSanitization
                    @McpTool(name = "same-method-conflict.bar", description = "same-method-conflict: method-level conflict.")
                    public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                        return p;
                    }
                }
                """));

        result.assertFailed();
        result.assertErrorMessage(InvocationPolicyScenarios.expectedConflictMessage(
                PolicyAxis.SANITIZE,
                "SameMethodConflict.bar",
                "SameMethodConflict.bar",
                "method SameMethodConflict.bar"));
    }

    @Test
    @DisplayName("same-parameter-conflict: @Sanitize + @SkipSanitization on the same parameter is a compile error")
    void conflictOnParameterIsACompileError() {
        var result =
                compile(SourceFiles.inline("dev.vertique.test.mcp.sameparameterconflict.SameParameterConflict", """
                package dev.vertique.test.mcp.sameparameterconflict;

                import dev.vertique.sanitization.Sanitize;
                import dev.vertique.sanitization.SkipSanitization;
                import dev.vertique.sanitization.processing.testkit.C;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;

                public class SameParameterConflict {

                    @Inject
                    public SameParameterConflict() {}

                    @McpTool(name = "same-parameter-conflict.bar", description = "same-parameter-conflict: parameter-level conflict.")
                    public String bar(
                            @McpToolParam(name = "p", description = "The value.")
                            @Sanitize(C.class) @SkipSanitization String p) {
                        return p;
                    }
                }
                """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("SameParameterConflict.bar");
    }

    @Test
    @DisplayName("override-skip-conflict: an override @SkipSanitization over an inherited interface @Sanitize is "
            + "a compile error")
    void overrideSkipOverInheritedAdditiveIsACompileError() {
        var result = compile(
                SourceFiles.inline("dev.vertique.test.mcp.overrideskipconflict.IFoo", """
                        package dev.vertique.test.mcp.overrideskipconflict;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.B;

                        public interface IFoo {
                            @Sanitize(B.class)
                            String bar(String p);
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.mcp.overrideskipconflict.FooImpl", """
                        package dev.vertique.test.mcp.overrideskipconflict;

                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class FooImpl implements IFoo {

                            @Inject
                            public FooImpl() {}

                            @Override
                            @SkipSanitization
                            @McpTool(name = "override-skip-conflict.bar", description = "override-skip-conflict: override-over-inherited conflict.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage(InvocationPolicyScenarios.expectedConflictMessage(
                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"));
    }

    @Test
    @DisplayName("supertype-skip-conflict: a subclass class-level @Sanitize over a superclass class-level "
            + "@SkipSanitization is a compile error")
    void subclassAdditiveOverSuperclassSkipIsACompileError() {
        var result = compile(
                SourceFiles.inline("dev.vertique.test.mcp.supertypeskipconflict.SupertypeSkipConflictBase", """
                        package dev.vertique.test.mcp.supertypeskipconflict;

                        import dev.vertique.sanitization.SkipSanitization;

                        @SkipSanitization
                        public class SupertypeSkipConflictBase {
                            public SupertypeSkipConflictBase() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.mcp.supertypeskipconflict.SupertypeSkipConflict", """
                        package dev.vertique.test.mcp.supertypeskipconflict;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class SupertypeSkipConflict extends SupertypeSkipConflictBase {

                            @Inject
                            public SupertypeSkipConflict() {}

                            @McpTool(name = "supertype-skip-conflict.bar", description = "supertype-skip-conflict: type-hierarchy conflict.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("SupertypeSkipConflictBase");
        result.assertErrorMessage("SupertypeSkipConflict");
    }

    // --- Non-conflict row fixtures ---

    /** One non-conflict row: sources, the generated invoker FQN, and expected carrier chains. */
    private record ScenarioRow(
            String id,
            List<JavaFileObject> sources,
            String invokerFqn,
            List<String> canonicalizers,
            List<String> sanitizers) {

        @Override
        public String toString() {
            return id;
        }
    }

    private static Stream<ScenarioRow> nonConflictRows() {
        return Stream.of(
                row("no-policy", List.of(), List.of(), """
                        package dev.vertique.test.mcp.nopolicy;

                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class NoPolicy {

                            @Inject
                            public NoPolicy() {}

                            @McpTool(name = "no-policy.bar", description = "no-policy: no policy annotations anywhere.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("type-chain", List.of(), List.of("A"), """
                        package dev.vertique.test.mcp.typechain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class TypeChain {

                            @Inject
                            public TypeChain() {}

                            @McpTool(name = "type-chain.bar", description = "type-chain: type-level @Sanitize.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-chain", List.of(), List.of("B"), """
                        package dev.vertique.test.mcp.methodchain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class MethodChain {

                            @Inject
                            public MethodChain() {}

                            @Sanitize(B.class)
                            @McpTool(name = "method-chain.bar", description = "method-chain: method-level @Sanitize.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-overrides-type", List.of(), List.of("B"), """
                        package dev.vertique.test.mcp.methodoverridestype;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class MethodOverridesType {

                            @Inject
                            public MethodOverridesType() {}

                            @Sanitize(B.class)
                            @McpTool(name = "method-overrides-type.bar", description = "method-overrides-type: method over type.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-skip-over-type-chain", List.of(), List.of(), """
                        package dev.vertique.test.mcp.methodskipovertypechain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class MethodSkipOverTypeChain {

                            @Inject
                            public MethodSkipOverTypeChain() {}

                            @SkipSanitization
                            @McpTool(name = "method-skip-over-type-chain.bar", description = "method-skip-over-type-chain: method skip over type additive.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-skip-only", List.of(), List.of(), """
                        package dev.vertique.test.mcp.methodskiponly;

                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class MethodSkipOnly {

                            @Inject
                            public MethodSkipOnly() {}

                            @SkipSanitization
                            @McpTool(name = "method-skip-only.bar", description = "method-skip-only: method skip, no type.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("parameter-overrides-type", List.of(), List.of("C"), """
                        package dev.vertique.test.mcp.parameteroverridestype;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.sanitization.processing.testkit.C;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class ParameterOverridesType {

                            @Inject
                            public ParameterOverridesType() {}

                            @McpTool(name = "parameter-overrides-type.bar", description = "parameter-overrides-type: parameter override over type.")
                            public String bar(
                                    @McpToolParam(name = "p", description = "The value.")
                                    @Sanitize(C.class) String p) {
                                return p;
                            }
                        }
                        """),
                row("parameter-skip-over-type-chain", List.of(), List.of(), """
                        package dev.vertique.test.mcp.parameterskipovertypechain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class ParameterSkipOverTypeChain {

                            @Inject
                            public ParameterSkipOverTypeChain() {}

                            @McpTool(name = "parameter-skip-over-type-chain.bar", description = "parameter-skip-over-type-chain: parameter skip over type additive.")
                            public String bar(
                                    @McpToolParam(name = "p", description = "The value.")
                                    @SkipSanitization String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "interface-method-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.interfacemethodchain.InterfaceMethodChainIface", """
                                        package dev.vertique.test.mcp.interfacemethodchain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.B;

                                        public interface InterfaceMethodChainIface {
                                            @Sanitize(B.class)
                                            String bar(String p);
                                        }
                                        """),
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.interfacemethodchain.InterfaceMethodChain", """
                                        package dev.vertique.test.mcp.interfacemethodchain;

                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class InterfaceMethodChain implements InterfaceMethodChainIface {

                                            @Inject
                                            public InterfaceMethodChain() {}

                                            @Override
                                            @McpTool(name = "interface-method-chain.bar", description = "interface-method-chain: interface method.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.interfacemethodchain.InterfaceMethodChain_bar_McpToolInvoker",
                        List.of(),
                        List.of("B")),
                new ScenarioRow(
                        "superclass-type-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.superclasstypechain.SuperclassTypeChainBase", """
                                        package dev.vertique.test.mcp.superclasstypechain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.A;

                                        @Sanitize(A.class)
                                        public class SuperclassTypeChainBase {
                                            public SuperclassTypeChainBase() {}
                                        }
                                        """),
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.superclasstypechain.SuperclassTypeChain", """
                                        package dev.vertique.test.mcp.superclasstypechain;

                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class SuperclassTypeChain extends SuperclassTypeChainBase {

                                            @Inject
                                            public SuperclassTypeChain() {}

                                            @McpTool(name = "superclass-type-chain.bar", description = "superclass-type-chain: superclass class-level.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.superclasstypechain.SuperclassTypeChain_bar_McpToolInvoker",
                        List.of(),
                        List.of("A")),
                row("composed-annotation", List.of(), List.of("B"), """
                        package dev.vertique.test.mcp.composedannotation;

                        import dev.vertique.sanitization.processing.testkit.ComposedSanitize;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class ComposedAnnotation {

                            @Inject
                            public ComposedAnnotation() {}

                            @ComposedSanitize
                            @McpTool(name = "composed-annotation.bar", description = "composed-annotation: composed meta-annotation.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "interface-parameter-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.interfaceparameterchain.InterfaceParameterChainIface",
                                        """
                                        package dev.vertique.test.mcp.interfaceparameterchain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.C;

                                        public interface InterfaceParameterChainIface {
                                            String bar(@Sanitize(C.class) String p);
                                        }
                                        """),
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.interfaceparameterchain.InterfaceParameterChain", """
                                        package dev.vertique.test.mcp.interfaceparameterchain;

                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class InterfaceParameterChain implements InterfaceParameterChainIface {

                                            @Inject
                                            public InterfaceParameterChain() {}

                                            @Override
                                            @McpTool(name = "interface-parameter-chain.bar", description = "interface-parameter-chain: interface parameter.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.interfaceparameterchain.InterfaceParameterChain_bar_McpToolInvoker",
                        List.of(),
                        List.of("C")),
                row("independent-axes", List.of("K"), List.of("B"), """
                        package dev.vertique.test.mcp.independentaxes;

                        import dev.vertique.sanitization.Canonicalize;
                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.sanitization.processing.testkit.K;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Canonicalize(K.class)
                        public class IndependentAxes {

                            @Inject
                            public IndependentAxes() {}

                            @Sanitize(B.class)
                            @McpTool(name = "independent-axes.bar", description = "independent-axes: independent axes.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "interface-method-skip",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.interfacemethodskip.InterfaceMethodSkipIface", """
                                        package dev.vertique.test.mcp.interfacemethodskip;

                                        import dev.vertique.sanitization.SkipSanitization;

                                        public interface InterfaceMethodSkipIface {
                                            @SkipSanitization
                                            String bar(String p);
                                        }
                                        """),
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.interfacemethodskip.InterfaceMethodSkip", """
                                        package dev.vertique.test.mcp.interfacemethodskip;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.A;
                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        @Sanitize(A.class)
                                        public class InterfaceMethodSkip implements InterfaceMethodSkipIface {

                                            @Inject
                                            public InterfaceMethodSkip() {}

                                            @Override
                                            @McpTool(name = "interface-method-skip.bar", description = "interface-method-skip: inherited skip over "
                                                    + "concrete class-level additive.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.interfacemethodskip.InterfaceMethodSkip_bar_McpToolInvoker",
                        List.of(),
                        List.of()),
                new ScenarioRow(
                        "override-replaces-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.overridereplaceschain.OverrideReplacesChainBase", """
                                        package dev.vertique.test.mcp.overridereplaceschain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.B;

                                        public class OverrideReplacesChainBase {
                                            public OverrideReplacesChainBase() {}

                                            @Sanitize(B.class)
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """),
                                SourceFiles.inline(
                                        "dev.vertique.test.mcp.overridereplaceschain.OverrideReplacesChain", """
                                        package dev.vertique.test.mcp.overridereplaceschain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.A;
                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class OverrideReplacesChain extends OverrideReplacesChainBase {

                                            @Inject
                                            public OverrideReplacesChain() {}

                                            @Override
                                            @Sanitize(A.class)
                                            @McpTool(name = "override-replaces-chain.bar", description = "override-replaces-chain: direct override wins.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.overridereplaceschain.OverrideReplacesChain_bar_McpToolInvoker",
                        List.of(),
                        List.of("A")));
    }

    /**
     * Builds a single-source {@link ScenarioRow} whose generated invoker FQN follows the standard
     * {@code dev.vertique.test.mcp.<scenario>.<Scenario>_bar_McpToolInvoker} shape.
     *
     * @param id             the scenario id (e.g. {@code "no-policy"})
     * @param canonicalizers expected carrier canonicalizer simple class names, in order
     * @param sanitizers     expected carrier sanitizer simple class names, in order
     * @param body           the inline source body (must declare the scenario's top-level class)
     * @return the built row
     */
    private static ScenarioRow row(String id, List<String> canonicalizers, List<String> sanitizers, String body) {
        String pkg =
                "dev.vertique.test.mcp." + id.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        String simpleName = pascalCase(id);
        return new ScenarioRow(
                id,
                List.of(SourceFiles.inline(pkg + "." + simpleName, body)),
                pkg + "." + simpleName + "_bar_McpToolInvoker",
                canonicalizers,
                sanitizers);
    }

    /**
     * Converts a kebab-case scenario id such as {@code "type-chain"} to the {@code TypeChain} simple
     * class name of its fixture.
     */
    private static String pascalCase(String kebab) {
        return java.util.Arrays.stream(kebab.split("-"))
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining());
    }

    // --- Assertion helper ---

    /**
     * Asserts the generated {@code Input} carrier's sole component matches the resolved
     * canonicalizer/sanitizer chains exactly as {@code McpToolInvokerEmitter#addPolicyAnnotation}
     * emits them: {@code @Canonicalize(X.class)}/{@code @Sanitize(X.class)} for a non-empty chain
     * (every row in this matrix resolves at most one class per axis), and the annotation entirely
     * absent from the generated file for an empty chain — {@code addPolicyAnnotation} skips emission
     * altogether rather than emitting an empty array, so "no exception" is not a substitute for
     * asserting the annotation's absence explicitly.
     */
    private static void assertResolvedPolicies(
            ProcessorTestHarness.Result result,
            String invokerFqn,
            List<String> canonicalizers,
            List<String> sanitizers) {
        if (canonicalizers.isEmpty()) {
            result.assertGeneratedSourceDoesNotContain(invokerFqn, "@Canonicalize(");
        } else {
            canonicalizers.forEach(
                    name -> result.assertGeneratedSourceContains(invokerFqn, "@Canonicalize(" + name + ".class)"));
        }
        if (sanitizers.isEmpty()) {
            result.assertGeneratedSourceDoesNotContain(invokerFqn, "@Sanitize(");
        } else {
            sanitizers.forEach(
                    name -> result.assertGeneratedSourceContains(invokerFqn, "@Sanitize(" + name + ".class)"));
        }
    }

    // --- Compilation helper ---

    /**
     * Compiles the given fixture sources with the real {@link McpToolProcessor}, plus the
     * mcp-server runtime stubs every generated invoker references ({@link
     * #mcpToolParameterMetadataStub()}, {@link #mcpToolRuntimeFactoryStub()}, {@link
     * #mcpToolRuntimeStub()}). Unlike {@code McpToolProcessorCompileTest}'s equivalent helper, no
     * {@code InputObjectProcessor}/{@code EffectiveInputPolicies} stub is needed:
     * {@code vertique-sanitization} is a real compile-scope dependency of this module, so both
     * types are already on the classpath.
     *
     * @param sources the fixture sources; must not be empty
     * @return the harness result
     */
    private static ProcessorTestHarness.Result compile(JavaFileObject... sources) {
        List<JavaFileObject> all = new ArrayList<>(List.of(sources));
        all.add(mcpToolParameterMetadataStub());
        all.add(mcpToolRuntimeFactoryStub());
        all.add(mcpToolRuntimeStub());
        return ProcessorTestHarness.run(new McpToolProcessor(), all.toArray(JavaFileObject[]::new));
    }

    /**
     * A compiled stub of {@code dev.vertique.mcp.server.runtime.McpToolParameterMetadata}, the
     * frozen runtime record every parameterized tool's generated invoker references by name.
     * {@code vertique-mcp-server} is deliberately not a dependency of this module (it test-depends on
     * this module's own output, so the reverse edge would be a reactor cycle) — mirrors {@code
     * McpToolProcessorCompileTest}'s identically-named helper.
     *
     * @return the {@code dev.vertique.mcp.server.runtime.McpToolParameterMetadata} stub source
     */
    private static JavaFileObject mcpToolParameterMetadataStub() {
        return SourceFiles.inline("dev.vertique.mcp.server.runtime.McpToolParameterMetadata", """
                package dev.vertique.mcp.server.runtime;

                public record McpToolParameterMetadata(
                        String carrierComponentName, String externalName, String description) {}
                """);
    }

    /**
     * A compiled stub of {@code dev.vertique.mcp.server.runtime.McpToolRuntimeFactory}: every
     * generated invoker's {@code @Inject} constructor takes one as a parameter and calls
     * {@code create(...)} on it. Same rationale as {@link #mcpToolParameterMetadataStub()}.
     *
     * @return the {@code dev.vertique.mcp.server.runtime.McpToolRuntimeFactory} stub source
     */
    private static JavaFileObject mcpToolRuntimeFactoryStub() {
        return SourceFiles.inline("dev.vertique.mcp.server.runtime.McpToolRuntimeFactory", """
                package dev.vertique.mcp.server.runtime;

                import dev.vertique.core.json.JsonProfileId;
                import dev.vertique.mcp.tool.McpToolAccess;
                import dev.vertique.mcp.tool.McpToolAnnotations;
                import java.lang.reflect.Type;
                import java.util.List;

                public final class McpToolRuntimeFactory {
                    public <I> McpToolRuntime<I> create(
                            String name,
                            String title,
                            String description,
                            McpToolAnnotations annotations,
                            Class<I> inputCarrierType,
                            Type structuredOutputType,
                            List<McpToolParameterMetadata> parameters,
                            JsonProfileId declaredJsonProfile,
                            McpToolAccess access) {
                        return null;
                    }
                }
                """);
    }

    /**
     * A compiled stub of {@code dev.vertique.mcp.server.runtime.McpToolRuntime}: every generated
     * invoker retains one, obtained from {@link #mcpToolRuntimeFactoryStub()}, and calls
     * {@code descriptor()}, {@code materializeArguments(...)}, and {@code fieldNameResolver()} on it.
     * Same rationale as {@link #mcpToolParameterMetadataStub()}.
     *
     * @return the {@code dev.vertique.mcp.server.runtime.McpToolRuntime} stub source
     */
    private static JavaFileObject mcpToolRuntimeStub() {
        return SourceFiles.inline("dev.vertique.mcp.server.runtime.McpToolRuntime", """
                package dev.vertique.mcp.server.runtime;

                import dev.vertique.core.input.InputFieldNameResolver;
                import dev.vertique.mcp.tool.McpStructuredOutputWriter;
                import dev.vertique.mcp.tool.McpToolDescriptor;
                import java.io.IOException;
                import java.io.OutputStream;
                import java.util.Map;
                import java.util.Optional;
                import java.util.function.Function;

                public final class McpToolRuntime<I> implements McpStructuredOutputWriter {
                    public McpToolDescriptor descriptor() {
                        return null;
                    }

                    public I materializeArguments(Map<String, Object> normalizedArguments) {
                        return null;
                    }

                    public InputFieldNameResolver fieldNameResolver() {
                        return null;
                    }

                    @Override
                    public void write(Object value, OutputStream destination) throws IOException {
                    }

                    public <P> void verifyOptionalMaterialization(
                            Class<P> probeType, Function<P, Optional<?>> valueAccessor) {
                    }
                }
                """);
    }
}
