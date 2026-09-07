// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.jaxrs.stubs.PolicyLiteralAssertions;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.PolicyAxis;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-002/TP-003 (T018, issue #379): {@link EffectiveJaxRsContractResolver} (once wired to {@code
 * apt.ElementInvocationPolicies}) resolves the same IP-01..IP-19 matrix as {@code
 * ElementInvocationPoliciesTest} (TP-001) and {@code ReflectiveInvocationPoliciesTest} (T016
 * TP-002) — but end-to-end through the real {@link JaxRsPipelineProcessor} pipeline, asserting the
 * generated {@code POL0}/{@code ROUTE_POL} literal content (not the adapter's return value
 * directly).
 *
 * <p>Every fixture reuses the exact class/method names of {@link InvocationPolicyScenarios}'s
 * reflection carriers (e.g. {@code Ip14.bar}, {@code IFoo}/{@code FooImpl}), decorated with the
 * JAX-RS wiring annotations ({@code @Path}, {@code @GET}, {@code @QueryParam}) needed for {@link
 * JaxRsMethodDiscovery}/{@code classifyEffectiveParam} to discover the method and parameter at all
 * — so the resolved {@code POL0} content and (for conflicts) the diagnostic message reuse the exact
 * site strings the contract documents as byte-identical to the reflective adapter.
 *
 * <p><strong>TP-002</strong> ({@link #generatedPlanMatchesTheMatrix}, IP-01..IP-13, IP-16, IP-18):
 * asserts the generated {@code POL0} constant equals the matrix's parameter-axis expectation (the
 * per-parameter combined chain — route baseline plus any parameter-level override — via {@link
 * PolicyLiteralAssertions#effectiveInputPolicies}). IP-16's fixture also carries a class-level
 * {@code @Sanitize(A.class)} on top of the scenario's interface method-level {@code
 * @SkipSanitization} (A4, contract L159-162): today's concrete-class-only walk resolves {@code [A]}
 * against the expected {@code []}, giving this row a distinct red signature from IP-01..08/11/13
 * (already green) — recorded in the completion evidence, not asserted here (this test always
 * asserts the correct/green expectation).
 *
 * <p><strong>TP-003</strong> ({@link #conflictOnMethodIsACompileErrorNamingTheMethod} IP-14, {@link
 * #conflictOnParameterIsACompileError} IP-15, {@link #overrideSkipOverInheritedAdditiveIsACompileError}
 * IP-17, {@link #subclassAdditiveOverSuperclassSkipIsACompileError} IP-19): asserts {@code
 * assertFailed()} plus the contract-shaped diagnostic. IP-14/IP-17 assert the exact message via
 * {@link InvocationPolicyScenarios#expectedConflictMessage} (contract: message shape tested on
 * IP-14/IP-17); IP-15/IP-19 assert {@code "Conflicting @Sanitize"} plus both real declaration sites,
 * mirroring {@code ReflectiveInvocationPoliciesTest}'s own ruling (T016 L01) that the contract does
 * not pin an exact literal for parameter-level/type-level conflicts.
 *
 * <p>Today ({@code EffectiveJaxRsContractResolver} pre-T018): IP-01..08, IP-11, IP-13, IP-18 are
 * green; IP-09 (interface method), IP-10 (superclass class-level), IP-12 (interface parameter), and
 * IP-16 (A4 trick) are red; IP-14/IP-15/IP-17/IP-19 compile successfully today (the skip-short-circuit
 * bug), so {@code assertFailed()} is red for all four.
 */
class JaxRsInvocationPolicyMatrixTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("generated POL0 matches the matrix's parameter-axis expectation")
    @MethodSource("nonConflictRows")
    void generatedPlanMatchesTheMatrix(ScenarioRow row) {
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), row.sources().toArray(JavaFileObject[]::new));

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                row.planFqn(), PolicyLiteralAssertions.effectiveInputPolicies(row.canonicalizers(), row.sanitizers()));
    }

    @Test
    @DisplayName("IP-14: @Sanitize + @SkipSanitization on the same method is a compile error naming the method")
    void conflictOnMethodIsACompileErrorNamingTheMethod() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ip14.Ip14", """
                        package dev.vertique.test.ip14;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.input.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip14")
                        @PermitAll
                        public class Ip14 {
                            public Ip14() {}

                            @GET
                            @Sanitize(B.class)
                            @SkipSanitization
                            public String bar(@QueryParam("p") String p) {
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
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ip15.Ip15", """
                        package dev.vertique.test.ip15;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.input.processing.testkit.C;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip15")
                        @PermitAll
                        public class Ip15 {
                            public Ip15() {}

                            @GET
                            public String bar(
                                    @QueryParam("p") @Sanitize(C.class) @SkipSanitization String p) {
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
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.ip17.IFoo", """
                        package dev.vertique.test.ip17;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip17")
                        public interface IFoo {
                            @GET
                            @Sanitize(B.class)
                            String bar(@QueryParam("p") String p);
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.ip17.FooImpl", """
                        package dev.vertique.test.ip17;

                        import dev.vertique.core.sanitization.SkipSanitization;
                        import jakarta.annotation.security.PermitAll;

                        @PermitAll
                        public class FooImpl implements IFoo {
                            public FooImpl() {}

                            @Override
                            @SkipSanitization
                            public String bar(String p) {
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
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.ip19.Ip19Base", """
                        package dev.vertique.test.ip19;

                        import dev.vertique.core.sanitization.SkipSanitization;

                        @SkipSanitization
                        public class Ip19Base {
                            public Ip19Base() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.ip19.Ip19", """
                        package dev.vertique.test.ip19;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip19")
                        @PermitAll
                        @Sanitize(A.class)
                        public class Ip19 extends Ip19Base {
                            public Ip19() {}

                            @GET
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("Ip19Base");
        result.assertErrorMessage("Ip19");
    }

    // --- TP-002 row fixtures ---

    /** One TP-002 (non-conflict) row: sources, the generated plan FQN, and expected POL0 chains. */
    private record ScenarioRow(
            String id,
            List<JavaFileObject> sources,
            String planFqn,
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
                        package dev.vertique.test.ip01;

                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip01")
                        @PermitAll
                        public class Ip01 {
                            public Ip01() {}

                            @GET
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-02", List.of(), List.of("A"), """
                        package dev.vertique.test.ip02;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip02")
                        @PermitAll
                        @Sanitize(A.class)
                        public class Ip02 {
                            public Ip02() {}

                            @GET
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-03", List.of(), List.of("B"), """
                        package dev.vertique.test.ip03;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip03")
                        @PermitAll
                        public class Ip03 {
                            public Ip03() {}

                            @GET
                            @Sanitize(B.class)
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-04", List.of(), List.of("B"), """
                        package dev.vertique.test.ip04;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.input.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip04")
                        @PermitAll
                        @Sanitize(A.class)
                        public class Ip04 {
                            public Ip04() {}

                            @GET
                            @Sanitize(B.class)
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-05", List.of(), List.of(), """
                        package dev.vertique.test.ip05;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.input.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip05")
                        @PermitAll
                        @Sanitize(A.class)
                        public class Ip05 {
                            public Ip05() {}

                            @GET
                            @SkipSanitization
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-06", List.of(), List.of(), """
                        package dev.vertique.test.ip06;

                        import dev.vertique.core.sanitization.SkipSanitization;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip06")
                        @PermitAll
                        public class Ip06 {
                            public Ip06() {}

                            @GET
                            @SkipSanitization
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-07", List.of(), List.of("C"), """
                        package dev.vertique.test.ip07;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.A;
                        import dev.vertique.input.processing.testkit.C;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip07")
                        @PermitAll
                        @Sanitize(A.class)
                        public class Ip07 {
                            public Ip07() {}

                            @GET
                            public String bar(@QueryParam("p") @Sanitize(C.class) String p) {
                                return p;
                            }
                        }
                        """),
                row("IP-08", List.of(), List.of(), """
                        package dev.vertique.test.ip08;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.input.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip08")
                        @PermitAll
                        @Sanitize(A.class)
                        public class Ip08 {
                            public Ip08() {}

                            @GET
                            public String bar(@QueryParam("p") @SkipSanitization String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "IP-09",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.ip09.Ip09Iface", """
                                        package dev.vertique.test.ip09;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.B;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/ip09")
                                        public interface Ip09Iface {
                                            @GET
                                            @Sanitize(B.class)
                                            String bar(@QueryParam("p") String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.ip09.Ip09", """
                                        package dev.vertique.test.ip09;

                                        import jakarta.annotation.security.PermitAll;

                                        @PermitAll
                                        public class Ip09 implements Ip09Iface {
                                            public Ip09() {}

                                            @Override
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.ip09.Ip09_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("B")),
                new ScenarioRow(
                        "IP-10",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.ip10.Ip10Base", """
                                        package dev.vertique.test.ip10;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.A;

                                        @Sanitize(A.class)
                                        public class Ip10Base {
                                            public Ip10Base() {}
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.ip10.Ip10", """
                                        package dev.vertique.test.ip10;

                                        import jakarta.annotation.security.PermitAll;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/ip10")
                                        @PermitAll
                                        public class Ip10 extends Ip10Base {
                                            public Ip10() {}

                                            @GET
                                            public String bar(@QueryParam("p") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.ip10.Ip10_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("A")),
                row("IP-11", List.of(), List.of("B"), """
                        package dev.vertique.test.ip11;

                        import dev.vertique.input.processing.testkit.ComposedSanitize;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip11")
                        @PermitAll
                        public class Ip11 {
                            public Ip11() {}

                            @GET
                            @ComposedSanitize
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "IP-12",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.ip12.Ip12Iface", """
                                        package dev.vertique.test.ip12;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.C;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/ip12")
                                        public interface Ip12Iface {
                                            @GET
                                            String bar(@QueryParam("p") @Sanitize(C.class) String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.ip12.Ip12", """
                                        package dev.vertique.test.ip12;

                                        import jakarta.annotation.security.PermitAll;

                                        @PermitAll
                                        public class Ip12 implements Ip12Iface {
                                            public Ip12() {}

                                            @Override
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.ip12.Ip12_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("C")),
                row("IP-13", List.of("K"), List.of("B"), """
                        package dev.vertique.test.ip13;

                        import dev.vertique.core.sanitization.Canonicalize;
                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;
                        import dev.vertique.input.processing.testkit.K;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/ip13")
                        @PermitAll
                        @Canonicalize(K.class)
                        public class Ip13 {
                            public Ip13() {}

                            @GET
                            @Sanitize(B.class)
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "IP-16",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.ip16.Ip16Iface", """
                                        package dev.vertique.test.ip16;

                                        import dev.vertique.core.sanitization.SkipSanitization;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/ip16")
                                        public interface Ip16Iface {
                                            @GET
                                            @SkipSanitization
                                            String bar(@QueryParam("p") String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.ip16.Ip16", """
                                        package dev.vertique.test.ip16;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.A;
                                        import jakarta.annotation.security.PermitAll;

                                        @PermitAll
                                        @Sanitize(A.class)
                                        public class Ip16 implements Ip16Iface {
                                            public Ip16() {}

                                            @Override
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.ip16.Ip16_bar_0_ExecutionPlan",
                        List.of(),
                        List.of()),
                new ScenarioRow(
                        "IP-18",
                        List.of(
                                SourceFiles.inline("dev.vertique.test.ip18.Ip18Base", """
                                        package dev.vertique.test.ip18;

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
                                SourceFiles.inline("dev.vertique.test.ip18.Ip18", """
                                        package dev.vertique.test.ip18;

                                        import dev.vertique.core.sanitization.Sanitize;
                                        import dev.vertique.input.processing.testkit.A;
                                        import jakarta.annotation.security.PermitAll;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/ip18")
                                        @PermitAll
                                        public class Ip18 extends Ip18Base {
                                            public Ip18() {}

                                            @Override
                                            @GET
                                            @Sanitize(A.class)
                                            public String bar(@QueryParam("p") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.ip18.Ip18_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("A")));
    }

    /**
     * Builds a single-source {@link ScenarioRow} whose generated plan FQN follows the standard
     * {@code dev.vertique.test.ipNN.IpNN_bar_0_ExecutionPlan} shape.
     *
     * @param id             the scenario id (e.g. {@code "IP-01"})
     * @param canonicalizers expected {@code POL0} canonicalizer simple class names, in order
     * @param sanitizers     expected {@code POL0} sanitizer simple class names, in order
     * @param body           the inline source body (must declare a top-level {@code IpNN} class)
     * @return the built row
     */
    private static ScenarioRow row(String id, List<String> canonicalizers, List<String> sanitizers, String body) {
        String pkg =
                "dev.vertique.test." + id.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        String simpleName = "Ip" + id.substring("IP-".length());
        return new ScenarioRow(
                id,
                List.of(SourceFiles.inline(pkg + "." + simpleName, body)),
                pkg + "." + simpleName + "_bar_0_ExecutionPlan",
                canonicalizers,
                sanitizers);
    }
}
