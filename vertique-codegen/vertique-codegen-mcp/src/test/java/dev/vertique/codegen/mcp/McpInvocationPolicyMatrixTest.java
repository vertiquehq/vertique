// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.PolicyAxis;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 (T019, issue #379): {@link McpInputPolicyResolver}, wired to {@code
 * apt.ElementInvocationPolicies}, resolves the same IP-01..IP-19 matrix as {@code
 * ElementInvocationPoliciesTest} (T018 TP-001), {@code ReflectiveInvocationPoliciesTest} (T016
 * TP-002), and {@code JaxRsInvocationPolicyMatrixTest} (T018 TP-002/TP-003) — end-to-end through
 * the real {@link McpToolProcessor} pipeline, asserting the generated {@code Input} carrier's
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
 * <p><strong>Non-conflict rows</strong> ({@link #generatedCarrierMatchesTheMatrix}, IP-01..IP-13,
 * IP-16, IP-18): asserts the generated {@code Input} carrier's sole component carries
 * {@code @Canonicalize(X.class)}/{@code @Sanitize(X.class)} for each non-empty resolved axis, and
 * that the annotation is entirely absent (never emitted, per {@code addPolicyAnnotation}'s
 * empty-chain skip) for an empty axis. IP-16's fixture also carries a class-level
 * {@code @Sanitize(A.class)} on top of the scenario's interface method-level
 * {@code @SkipSanitization} (mirrors T018 A4, contract L159-162): the hierarchy walk resolves this
 * row to the same {@code []} expectation as every other row in the matrix.
 *
 * <p><strong>Conflict rows</strong> ({@link #conflictOnMethodIsACompileErrorNamingTheMethod} IP-14,
 * {@link #conflictOnParameterIsACompileError} IP-15,
 * {@link #overrideSkipOverInheritedAdditiveIsACompileError} IP-17,
 * {@link #subclassAdditiveOverSuperclassSkipIsACompileError} IP-19): asserts {@code assertFailed()}
 * plus the contract-shaped diagnostic. IP-14/IP-17 assert the exact message via
 * {@link InvocationPolicyScenarios#expectedConflictMessage} (the site names match this test's own
 * fixture class/method names); IP-15/IP-19 assert {@code "Conflicting @Sanitize"} plus the real
 * declaration sites, mirroring {@code JaxRsInvocationPolicyMatrixTest}'s own ruling that the contract
 * does not pin an exact literal for parameter-level/type-level conflicts.
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
    @DisplayName("IP-14: @Sanitize + @SkipSanitization on the same method is a compile error naming the method")
    void conflictOnMethodIsACompileErrorNamingTheMethod() {
        var result = compile(SourceFiles.inline("dev.vertique.test.mcp.ip14.Ip14", """
                package dev.vertique.test.mcp.ip14;

                import dev.vertique.core.sanitization.Sanitize;
                import dev.vertique.core.sanitization.SkipSanitization;
                import dev.vertique.input.processing.testkit.B;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;

                public class Ip14 {

                    @Inject
                    public Ip14() {}

                    @Sanitize(B.class)
                    @SkipSanitization
                    @McpTool(name = "ip14.bar", description = "IP-14: method-level conflict.")
                    public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                        return p;
                    }
                }
                """));

        result.assertFailed();
        result.assertErrorMessage(InvocationPolicyScenarios.expectedConflictMessage(
                PolicyAxis.SANITIZE, "Ip14.bar", "Ip14.bar", "method Ip14.bar"));
    }

    @Test
    @DisplayName("IP-15: @Sanitize + @SkipSanitization on the same parameter is a compile error")
    void conflictOnParameterIsACompileError() {
        var result = compile(SourceFiles.inline("dev.vertique.test.mcp.ip15.Ip15", """
                package dev.vertique.test.mcp.ip15;

                import dev.vertique.core.sanitization.Sanitize;
                import dev.vertique.core.sanitization.SkipSanitization;
                import dev.vertique.input.processing.testkit.C;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;

                public class Ip15 {

                    @Inject
                    public Ip15() {}

                    @McpTool(name = "ip15.bar", description = "IP-15: parameter-level conflict.")
                    public String bar(
                            @McpToolParam(name = "p", description = "The value.")
                            @Sanitize(C.class) @SkipSanitization String p) {
                        return p;
                    }
                }
                """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("Ip15.bar");
    }

    @Test
    @DisplayName("IP-17: an override @SkipSanitization over an inherited interface @Sanitize is a compile error")
    void overrideSkipOverInheritedAdditiveIsACompileError() {
        var result = compile(
                SourceFiles.inline("dev.vertique.test.mcp.ip17.IFoo", """
                        package dev.vertique.test.mcp.ip17;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;

                        public interface IFoo {
                            @Sanitize(B.class)
                            String bar(String p);
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.mcp.ip17.FooImpl", """
                        package dev.vertique.test.mcp.ip17;

                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class FooImpl implements IFoo {

                            @Inject
                            public FooImpl() {}

                            @Override
                            @SkipSanitization
                            @McpTool(name = "ip17.bar", description = "IP-17: override-over-inherited conflict.")
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
    @DisplayName("IP-19: a subclass class-level @Sanitize over a superclass class-level @SkipSanitization "
            + "is a compile error")
    void subclassAdditiveOverSuperclassSkipIsACompileError() {
        var result = compile(
                SourceFiles.inline("dev.vertique.test.mcp.ip19.Ip19Base", """
                        package dev.vertique.test.mcp.ip19;

                        import dev.vertique.core.sanitization.SkipSanitization;

                        @SkipSanitization
                        public class Ip19Base {
                            public Ip19Base() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.mcp.ip19.Ip19", """
                        package dev.vertique.test.mcp.ip19;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class Ip19 extends Ip19Base {

                            @Inject
                            public Ip19() {}

                            @McpTool(name = "ip19.bar", description = "IP-19: type-hierarchy conflict.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("Ip19Base");
        result.assertErrorMessage("Ip19");
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
                row("IP-01", List.of(), List.of(), """
                        package dev.vertique.test.mcp.ip01;

                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class Ip01 {

                            @Inject
                            public Ip01() {}

                            @McpTool(name = "ip01.bar", description = "IP-01: no policy annotations anywhere.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-02", List.of(), List.of("A"), """
                        package dev.vertique.test.mcp.ip02;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class Ip02 {

                            @Inject
                            public Ip02() {}

                            @McpTool(name = "ip02.bar", description = "IP-02: type-level @Sanitize.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-03", List.of(), List.of("B"), """
                        package dev.vertique.test.mcp.ip03;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class Ip03 {

                            @Inject
                            public Ip03() {}

                            @Sanitize(B.class)
                            @McpTool(name = "ip03.bar", description = "IP-03: method-level @Sanitize.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-04", List.of(), List.of("B"), """
                        package dev.vertique.test.mcp.ip04;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.input.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class Ip04 {

                            @Inject
                            public Ip04() {}

                            @Sanitize(B.class)
                            @McpTool(name = "ip04.bar", description = "IP-04: method over type.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-05", List.of(), List.of(), """
                        package dev.vertique.test.mcp.ip05;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class Ip05 {

                            @Inject
                            public Ip05() {}

                            @SkipSanitization
                            @McpTool(name = "ip05.bar", description = "IP-05: method skip over type additive.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-06", List.of(), List.of(), """
                        package dev.vertique.test.mcp.ip06;

                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class Ip06 {

                            @Inject
                            public Ip06() {}

                            @SkipSanitization
                            @McpTool(name = "ip06.bar", description = "IP-06: method skip, no type.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-07", List.of(), List.of("C"), """
                        package dev.vertique.test.mcp.ip07;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.input.processing.testkit.C;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class Ip07 {

                            @Inject
                            public Ip07() {}

                            @McpTool(name = "ip07.bar", description = "IP-07: parameter override over type.")
                            public String bar(
                                    @McpToolParam(name = "p", description = "The value.")
                                    @Sanitize(C.class) String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-08", List.of(), List.of(), """
                        package dev.vertique.test.mcp.ip08;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Sanitize(A.class)
                        public class Ip08 {

                            @Inject
                            public Ip08() {}

                            @McpTool(name = "ip08.bar", description = "IP-08: parameter skip over type additive.")
                            public String bar(
                                    @McpToolParam(name = "p", description = "The value.")
                                    @SkipSanitization String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "IP-09",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.mcp.ip09.Ip09Iface", """
                                        package dev.vertique.test.mcp.ip09;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.B;

                                        public interface Ip09Iface {
                                            @Sanitize(B.class)
                                            String bar(String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.mcp.ip09.Ip09", """
                                        package dev.vertique.test.mcp.ip09;

                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class Ip09 implements Ip09Iface {

                                            @Inject
                                            public Ip09() {}

                                            @Override
                                            @McpTool(name = "ip09.bar", description = "IP-09: interface method.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.ip09.Ip09_bar_McpToolInvoker",
                        List.of(),
                        List.of("B")),
                new ScenarioRow(
                        "IP-10",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.mcp.ip10.Ip10Base", """
                                        package dev.vertique.test.mcp.ip10;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.A;

                                        @Sanitize(A.class)
                                        public class Ip10Base {
                                            public Ip10Base() {}
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.mcp.ip10.Ip10", """
                                        package dev.vertique.test.mcp.ip10;

                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class Ip10 extends Ip10Base {

                                            @Inject
                                            public Ip10() {}

                                            @McpTool(name = "ip10.bar", description = "IP-10: superclass class-level.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.ip10.Ip10_bar_McpToolInvoker",
                        List.of(),
                        List.of("A")),
                row("IP-11", List.of(), List.of("B"), """
                        package dev.vertique.test.mcp.ip11;

                        import dev.vertique.input.processing.testkit.ComposedSanitize;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        public class Ip11 {

                            @Inject
                            public Ip11() {}

                            @ComposedSanitize
                            @McpTool(name = "ip11.bar", description = "IP-11: composed meta-annotation.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "IP-12",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.mcp.ip12.Ip12Iface", """
                                        package dev.vertique.test.mcp.ip12;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.C;

                                        public interface Ip12Iface {
                                            String bar(@Sanitize(C.class) String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.mcp.ip12.Ip12", """
                                        package dev.vertique.test.mcp.ip12;

                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class Ip12 implements Ip12Iface {

                                            @Inject
                                            public Ip12() {}

                                            @Override
                                            @McpTool(name = "ip12.bar", description = "IP-12: interface parameter.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.ip12.Ip12_bar_McpToolInvoker",
                        List.of(),
                        List.of("C")),
                row("IP-13", List.of("K"), List.of("B"), """
                        package dev.vertique.test.mcp.ip13;

                        import dev.vertique.core.sanitization.Canonicalize;
                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;
                        import dev.vertique.input.processing.testkit.K;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.annotation.McpToolParam;
                        import jakarta.inject.Inject;

                        @Canonicalize(K.class)
                        public class Ip13 {

                            @Inject
                            public Ip13() {}

                            @Sanitize(B.class)
                            @McpTool(name = "ip13.bar", description = "IP-13: independent axes.")
                            public String bar(@McpToolParam(name = "p", description = "The value.") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "IP-16",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.mcp.ip16.Ip16Iface", """
                                        package dev.vertique.test.mcp.ip16;

                                        import dev.vertique.core.sanitization.SkipSanitization;

                                        public interface Ip16Iface {
                                            @SkipSanitization
                                            String bar(String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.mcp.ip16.Ip16", """
                                        package dev.vertique.test.mcp.ip16;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.A;
                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        @Sanitize(A.class)
                                        public class Ip16 implements Ip16Iface {

                                            @Inject
                                            public Ip16() {}

                                            @Override
                                            @McpTool(name = "ip16.bar", description = "IP-16: inherited skip over "
                                                    + "concrete class-level additive.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.ip16.Ip16_bar_McpToolInvoker",
                        List.of(),
                        List.of()),
                new ScenarioRow(
                        "IP-18",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.mcp.ip18.Ip18Base", """
                                        package dev.vertique.test.mcp.ip18;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.B;

                                        public class Ip18Base {
                                            public Ip18Base() {}

                                            @Sanitize(B.class)
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.mcp.ip18.Ip18", """
                                        package dev.vertique.test.mcp.ip18;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.A;
                                        import dev.vertique.mcp.annotation.McpTool;
                                        import dev.vertique.mcp.annotation.McpToolParam;
                                        import jakarta.inject.Inject;

                                        public class Ip18 extends Ip18Base {

                                            @Inject
                                            public Ip18() {}

                                            @Override
                                            @Sanitize(A.class)
                                            @McpTool(name = "ip18.bar", description = "IP-18: direct override wins.")
                                            public String bar(
                                                    @McpToolParam(name = "p", description = "The value.") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.mcp.ip18.Ip18_bar_McpToolInvoker",
                        List.of(),
                        List.of("A")));
    }

    /**
     * Builds a single-source {@link ScenarioRow} whose generated invoker FQN follows the standard
     * {@code dev.vertique.test.mcp.ipNN.IpNN_bar_McpToolInvoker} shape.
     *
     * @param id             the scenario id (e.g. {@code "IP-01"})
     * @param canonicalizers expected carrier canonicalizer simple class names, in order
     * @param sanitizers     expected carrier sanitizer simple class names, in order
     * @param body           the inline source body (must declare a top-level {@code IpNN} class)
     * @return the built row
     */
    private static ScenarioRow row(String id, List<String> canonicalizers, List<String> sanitizers, String body) {
        String pkg =
                "dev.vertique.test.mcp." + id.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        String simpleName = "Ip" + id.substring("IP-".length());
        return new ScenarioRow(
                id,
                List.of(SourceFiles.inline(pkg + "." + simpleName, body)),
                pkg + "." + simpleName + "_bar_McpToolInvoker",
                canonicalizers,
                sanitizers);
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
     * {@code InputObjectProcessor}/{@code EffectiveInputPolicies} stub is needed: T019 (issue #379)
     * adds {@code vertique-input-processing} as a real compile-scope dependency of this module, so
     * both types are already on the classpath.
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

                import dev.vertique.core.sanitization.InputFieldNameResolver;
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
