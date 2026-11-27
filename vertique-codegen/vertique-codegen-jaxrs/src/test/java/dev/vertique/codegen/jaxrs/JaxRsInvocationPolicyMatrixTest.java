// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.jaxrs.stubs.PolicyLiteralAssertions;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.sanitization.processing.PolicyAxis;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link EffectiveJaxRsContractResolver}, wired to {@code apt.ElementInvocationPolicies}, resolves the
 * same invocation-policy matrix as {@code ElementInvocationPoliciesTest} and
 * {@code ReflectiveInvocationPoliciesTest} — end-to-end through the real
 * {@link JaxRsPipelineProcessor} pipeline, asserting the generated {@code POL0} literal content (not
 * the adapter's return value directly).
 *
 * <p>Every fixture reuses the exact class/method names of {@link InvocationPolicyScenarios}'s
 * reflection carriers (e.g. {@code SameMethodConflict.bar}, {@code IFoo}/{@code FooImpl}), decorated
 * with the JAX-RS wiring annotations ({@code @Path}, {@code @GET}, {@code @QueryParam}) needed for
 * {@link JaxRsMethodDiscovery}/{@code classifyEffectiveParam} to discover the method and parameter at
 * all — so the resolved {@code POL0} content and (for conflicts) the diagnostic message reuse the
 * exact site strings the reflective adapter produces.
 *
 * <p><strong>Non-conflict rows</strong> ({@link #generatedPlanMatchesTheMatrix}, {@code no-policy}
 * through {@code independent-axes}, {@code interface-method-skip}, {@code override-replaces-chain}):
 * asserts the generated {@code POL0} constant equals the matrix's parameter-axis expectation (the
 * per-parameter combined chain — route baseline plus any parameter-level override — via
 * {@link PolicyLiteralAssertions#effectiveInputPolicies}). The {@code interface-method-skip} fixture
 * also carries a class-level {@code @Sanitize(A.class)} on top of the scenario's interface
 * method-level {@code @SkipSanitization}: the hierarchy walk resolves this row to the same {@code []}
 * expectation as every other row in the matrix.
 *
 * <p><strong>Conflict rows</strong> ({@link #conflictOnMethodIsACompileErrorNamingTheMethod}
 * {@code same-method-conflict}, {@link #conflictOnParameterIsACompileError}
 * {@code same-parameter-conflict}, {@link #overrideSkipOverInheritedAdditiveIsACompileError}
 * {@code override-skip-conflict}, {@link #subclassAdditiveOverSuperclassSkipIsACompileError}
 * {@code supertype-skip-conflict}): asserts {@code assertFailed()} plus the expected diagnostic.
 * {@code same-method-conflict} and {@code override-skip-conflict} assert the exact message via
 * {@link InvocationPolicyScenarios#expectedConflictMessage}; {@code same-parameter-conflict} and
 * {@code supertype-skip-conflict} assert {@code "Conflicting @Sanitize"} plus both real declaration
 * sites, mirroring {@code ReflectiveInvocationPoliciesTest}: no exact literal is pinned for
 * parameter-level/type-level conflicts.
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
    @DisplayName("same-method-conflict: @Sanitize + @SkipSanitization on the same method is a compile error "
            + "naming the method")
    void conflictOnMethodIsACompileErrorNamingTheMethod() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.samemethodconflict.SameMethodConflict", """
                        package dev.vertique.test.samemethodconflict;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/same-method-conflict")
                        @PermitAll
                        public class SameMethodConflict {
                            public SameMethodConflict() {}

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
                PolicyAxis.SANITIZE,
                "SameMethodConflict.bar",
                "SameMethodConflict.bar",
                "method SameMethodConflict.bar"));
    }

    @Test
    @DisplayName("same-parameter-conflict: @Sanitize + @SkipSanitization on the same parameter is a compile error")
    void conflictOnParameterIsACompileError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.sameparameterconflict.SameParameterConflict", """
                        package dev.vertique.test.sameparameterconflict;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.C;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/same-parameter-conflict")
                        @PermitAll
                        public class SameParameterConflict {
                            public SameParameterConflict() {}

                            @GET
                            public String bar(
                                    @QueryParam("p") @Sanitize(C.class) @SkipSanitization String p) {
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
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.overrideskipconflict.IFoo", """
                        package dev.vertique.test.overrideskipconflict;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/override-skip-conflict")
                        public interface IFoo {
                            @GET
                            @Sanitize(B.class)
                            String bar(@QueryParam("p") String p);
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.overrideskipconflict.FooImpl", """
                        package dev.vertique.test.overrideskipconflict;

                        import dev.vertique.sanitization.SkipSanitization;
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
    @DisplayName("supertype-skip-conflict: a subclass class-level @Sanitize over a superclass class-level "
            + "@SkipSanitization is a compile error")
    void subclassAdditiveOverSuperclassSkipIsACompileError() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.supertypeskipconflict.SupertypeSkipConflictBase", """
                        package dev.vertique.test.supertypeskipconflict;

                        import dev.vertique.sanitization.SkipSanitization;

                        @SkipSanitization
                        public class SupertypeSkipConflictBase {
                            public SupertypeSkipConflictBase() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.supertypeskipconflict.SupertypeSkipConflict", """
                        package dev.vertique.test.supertypeskipconflict;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/supertype-skip-conflict")
                        @PermitAll
                        @Sanitize(A.class)
                        public class SupertypeSkipConflict extends SupertypeSkipConflictBase {
                            public SupertypeSkipConflict() {}

                            @GET
                            public String bar(@QueryParam("p") String p) {
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

    /** One non-conflict row: sources, the generated plan FQN, and expected POL0 chains. */
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
                row("no-policy", List.of(), List.of(), """
                        package dev.vertique.test.nopolicy;

                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/no-policy")
                        @PermitAll
                        public class NoPolicy {
                            public NoPolicy() {}

                            @GET
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("type-chain", List.of(), List.of("A"), """
                        package dev.vertique.test.typechain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/type-chain")
                        @PermitAll
                        @Sanitize(A.class)
                        public class TypeChain {
                            public TypeChain() {}

                            @GET
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-chain", List.of(), List.of("B"), """
                        package dev.vertique.test.methodchain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/method-chain")
                        @PermitAll
                        public class MethodChain {
                            public MethodChain() {}

                            @GET
                            @Sanitize(B.class)
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-overrides-type", List.of(), List.of("B"), """
                        package dev.vertique.test.methodoverridestype;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/method-overrides-type")
                        @PermitAll
                        @Sanitize(A.class)
                        public class MethodOverridesType {
                            public MethodOverridesType() {}

                            @GET
                            @Sanitize(B.class)
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-skip-over-type-chain", List.of(), List.of(), """
                        package dev.vertique.test.methodskipovertypechain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/method-skip-over-type-chain")
                        @PermitAll
                        @Sanitize(A.class)
                        public class MethodSkipOverTypeChain {
                            public MethodSkipOverTypeChain() {}

                            @GET
                            @SkipSanitization
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("method-skip-only", List.of(), List.of(), """
                        package dev.vertique.test.methodskiponly;

                        import dev.vertique.sanitization.SkipSanitization;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/method-skip-only")
                        @PermitAll
                        public class MethodSkipOnly {
                            public MethodSkipOnly() {}

                            @GET
                            @SkipSanitization
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                row("parameter-overrides-type", List.of(), List.of("C"), """
                        package dev.vertique.test.parameteroverridestype;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import dev.vertique.sanitization.processing.testkit.C;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/parameter-overrides-type")
                        @PermitAll
                        @Sanitize(A.class)
                        public class ParameterOverridesType {
                            public ParameterOverridesType() {}

                            @GET
                            public String bar(@QueryParam("p") @Sanitize(C.class) String p) {
                                return p;
                            }
                        }
                        """),
                row("parameter-skip-over-type-chain", List.of(), List.of(), """
                        package dev.vertique.test.parameterskipovertypechain;

                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.A;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/parameter-skip-over-type-chain")
                        @PermitAll
                        @Sanitize(A.class)
                        public class ParameterSkipOverTypeChain {
                            public ParameterSkipOverTypeChain() {}

                            @GET
                            public String bar(@QueryParam("p") @SkipSanitization String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "interface-method-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.interfacemethodchain.InterfaceMethodChainIface", """
                                        package dev.vertique.test.interfacemethodchain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.B;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/interface-method-chain")
                                        public interface InterfaceMethodChainIface {
                                            @GET
                                            @Sanitize(B.class)
                                            String bar(@QueryParam("p") String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.interfacemethodchain.InterfaceMethodChain", """
                                        package dev.vertique.test.interfacemethodchain;

                                        import jakarta.annotation.security.PermitAll;

                                        @PermitAll
                                        public class InterfaceMethodChain implements InterfaceMethodChainIface {
                                            public InterfaceMethodChain() {}

                                            @Override
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.interfacemethodchain.InterfaceMethodChain_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("B")),
                new ScenarioRow(
                        "superclass-type-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.superclasstypechain.SuperclassTypeChainBase", """
                                        package dev.vertique.test.superclasstypechain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.A;

                                        @Sanitize(A.class)
                                        public class SuperclassTypeChainBase {
                                            public SuperclassTypeChainBase() {}
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.superclasstypechain.SuperclassTypeChain", """
                                        package dev.vertique.test.superclasstypechain;

                                        import jakarta.annotation.security.PermitAll;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/superclass-type-chain")
                                        @PermitAll
                                        public class SuperclassTypeChain extends SuperclassTypeChainBase {
                                            public SuperclassTypeChain() {}

                                            @GET
                                            public String bar(@QueryParam("p") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.superclasstypechain.SuperclassTypeChain_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("A")),
                row("composed-annotation", List.of(), List.of("B"), """
                        package dev.vertique.test.composedannotation;

                        import dev.vertique.sanitization.processing.testkit.ComposedSanitize;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/composed-annotation")
                        @PermitAll
                        public class ComposedAnnotation {
                            public ComposedAnnotation() {}

                            @GET
                            @ComposedSanitize
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "interface-parameter-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.interfaceparameterchain.InterfaceParameterChainIface", """
                                        package dev.vertique.test.interfaceparameterchain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.C;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/interface-parameter-chain")
                                        public interface InterfaceParameterChainIface {
                                            @GET
                                            String bar(@QueryParam("p") @Sanitize(C.class) String p);
                                        }
                                        """),
                                SourceFiles.inline(
                                        "dev.vertique.test.interfaceparameterchain.InterfaceParameterChain", """
                                        package dev.vertique.test.interfaceparameterchain;

                                        import jakarta.annotation.security.PermitAll;

                                        @PermitAll
                                        public class InterfaceParameterChain implements InterfaceParameterChainIface {
                                            public InterfaceParameterChain() {}

                                            @Override
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.interfaceparameterchain.InterfaceParameterChain_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("C")),
                row("independent-axes", List.of("K"), List.of("B"), """
                        package dev.vertique.test.independentaxes;

                        import dev.vertique.sanitization.Canonicalize;
                        import dev.vertique.sanitization.Sanitize;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.sanitization.processing.testkit.K;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/independent-axes")
                        @PermitAll
                        @Canonicalize(K.class)
                        public class IndependentAxes {
                            public IndependentAxes() {}

                            @GET
                            @Sanitize(B.class)
                            public String bar(@QueryParam("p") String p) {
                                return p;
                            }
                        }
                        """),
                new ScenarioRow(
                        "interface-method-skip",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.interfacemethodskip.InterfaceMethodSkipIface", """
                                        package dev.vertique.test.interfacemethodskip;

                                        import dev.vertique.sanitization.SkipSanitization;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/interface-method-skip")
                                        public interface InterfaceMethodSkipIface {
                                            @GET
                                            @SkipSanitization
                                            String bar(@QueryParam("p") String p);
                                        }
                                        """),
                                SourceFiles.inline("dev.vertique.test.interfacemethodskip.InterfaceMethodSkip", """
                                        package dev.vertique.test.interfacemethodskip;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.A;
                                        import jakarta.annotation.security.PermitAll;

                                        @PermitAll
                                        @Sanitize(A.class)
                                        public class InterfaceMethodSkip implements InterfaceMethodSkipIface {
                                            public InterfaceMethodSkip() {}

                                            @Override
                                            public String bar(String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.interfacemethodskip.InterfaceMethodSkip_bar_0_ExecutionPlan",
                        List.of(),
                        List.of()),
                new ScenarioRow(
                        "override-replaces-chain",
                        List.of(
                                SourceFiles.inline(
                                        "dev.vertique.test.overridereplaceschain.OverrideReplacesChainBase", """
                                        package dev.vertique.test.overridereplaceschain;

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
                                        "dev.vertique.test.overridereplaceschain.OverrideReplacesChain", """
                                        package dev.vertique.test.overridereplaceschain;

                                        import dev.vertique.sanitization.Sanitize;
                                        import dev.vertique.sanitization.processing.testkit.A;
                                        import jakarta.annotation.security.PermitAll;
                                        import jakarta.ws.rs.GET;
                                        import jakarta.ws.rs.Path;
                                        import jakarta.ws.rs.QueryParam;

                                        @Path("/override-replaces-chain")
                                        @PermitAll
                                        public class OverrideReplacesChain extends OverrideReplacesChainBase {
                                            public OverrideReplacesChain() {}

                                            @Override
                                            @GET
                                            @Sanitize(A.class)
                                            public String bar(@QueryParam("p") String p) {
                                                return p;
                                            }
                                        }
                                        """)),
                        "dev.vertique.test.overridereplaceschain.OverrideReplacesChain_bar_0_ExecutionPlan",
                        List.of(),
                        List.of("A")));
    }

    /**
     * Builds a single-source {@link ScenarioRow} whose generated plan FQN follows the standard
     * {@code dev.vertique.test.<scenario>.<Scenario>_bar_0_ExecutionPlan} shape.
     *
     * @param id             the scenario id (e.g. {@code "no-policy"})
     * @param canonicalizers expected {@code POL0} canonicalizer simple class names, in order
     * @param sanitizers     expected {@code POL0} sanitizer simple class names, in order
     * @param body           the inline source body (must declare the scenario's top-level class)
     * @return the built row
     */
    private static ScenarioRow row(String id, List<String> canonicalizers, List<String> sanitizers, String body) {
        String pkg =
                "dev.vertique.test." + id.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        String simpleName = pascalCase(id);
        return new ScenarioRow(
                id,
                List.of(SourceFiles.inline(pkg + "." + simpleName, body)),
                pkg + "." + simpleName + "_bar_0_ExecutionPlan",
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
}
