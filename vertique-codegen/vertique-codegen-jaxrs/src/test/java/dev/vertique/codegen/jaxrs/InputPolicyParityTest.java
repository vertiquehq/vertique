// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.jaxrs.stubs.PolicyLiteralAssertions;
import dev.vertique.codegen.jaxrs.stubs.PolicyTestStubs;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * APT-side parity test asserting that {@link JaxRsPipelineProcessor} emits correct
 * {@code POL_n} constants in generated execution-plan companions when {@code @Canonicalize},
 * {@code @Sanitize}, {@code @SkipCanonicalization}, and {@code @SkipSanitization} annotations
 * are present on resource classes and methods.
 *
 * <p>The compile-time algorithm in {@link dev.vertique.codegen.jaxrs.EffectiveJaxRsContractResolver}
 * must produce the same chain as the runtime
 * {@code ResourceScanner.resolveRouteCanonicalizerChain} /
 * {@code resolveRouteSanitizerChain} + {@code ParameterExtractor.resolveParamPolicies}.
 * The runtime-side verification is performed by {@code InputPolicyRuntimeParityTest}
 * in the {@code vertique-rest-jaxrs} module.
 *
 * <p>Scenarios (APT-side only, direct annotations on a single resource class):
 * <ul>
 *   <li>No annotations — {@code POL_n} is {@code EffectiveInputPolicies.NONE}.</li>
 *   <li>Class-level {@code @Canonicalize} — {@code POL_n} contains the canonicalizer class literal.</li>
 *   <li>Method-level {@code @Canonicalize} overrides class-level — {@code POL_n} uses method value.</li>
 *   <li>Method-level {@code @SkipCanonicalization} — {@code POL_n} reverts to {@code NONE}.</li>
 *   <li>Class-level {@code @Sanitize} — {@code POL_n} contains the sanitizer class literal.</li>
 *   <li>Descriptor {@code CC_} constant is non-empty when class-level {@code @Canonicalize} present.</li>
 *   <li>Descriptor {@code CC_} constant is empty ({@code new String[0]}) when no {@code @Canonicalize}.</li>
 * </ul>
 *
 * <p>Retargeted (T018, issue #379): the interface-, superclass-, and conflict-shaped scenarios this
 * test never covered (hierarchy precedence, meta-annotations, additive+skip conflicts — the full
 * IP-01..IP-19 matrix) are {@link JaxRsInvocationPolicyMatrixTest}'s responsibility; this test keeps
 * its original five direct-annotation scenarios unchanged, now asserting the {@code EffectiveInputPolicies.NONE}
 * / class-literal snippets through the same {@link PolicyLiteralAssertions} helper {@code
 * JaxRsInvocationPolicyMatrixTest} uses, so the literal shape is defined once, not restated per test.
 *
 * <p>Stub Canonicalizer and Sanitizer implementations live in {@link PolicyTestStubs} — a separate
 * top-level public class — so that inline source text compiled through
 * {@link ProcessorTestHarness} can reference them by FQN without inaccessible-class restrictions.
 */
class InputPolicyParityTest {

    // --- Helpers: FQNs and simple names for inline source import / assertion ---

    /**
     * Canonical FQN of {@link PolicyTestStubs.StubCanonicalizer} for use as a Java import.
     * Uses {@code getCanonicalName()} (dotted nested form) rather than {@code getName()}
     * (binary {@code $} form) because javac source imports require the dotted form.
     */
    private static final String STUB_CANON_FQN = PolicyTestStubs.StubCanonicalizer.class.getCanonicalName();

    /** Canonical FQN of {@link PolicyTestStubs.StubCanonicalizer2}. */
    private static final String STUB_CANON2_FQN = PolicyTestStubs.StubCanonicalizer2.class.getCanonicalName();

    /** Canonical FQN of {@link PolicyTestStubs.StubSanitizer}. */
    private static final String STUB_SANIT_FQN = PolicyTestStubs.StubSanitizer.class.getCanonicalName();

    /** Simple class name used as a class-literal marker in generated source assertions. */
    private static final String STUB_CANON_SIMPLE = PolicyTestStubs.StubCanonicalizer.class.getSimpleName();

    /** Simple class name used as a class-literal marker in generated source assertions. */
    private static final String STUB_CANON2_SIMPLE = PolicyTestStubs.StubCanonicalizer2.class.getSimpleName();

    /** Simple class name used as a class-literal marker in generated source assertions. */
    private static final String STUB_SANIT_SIMPLE = PolicyTestStubs.StubSanitizer.class.getSimpleName();

    // --- Tests ---

    @Nested
    @DisplayName("no sanitization annotations — POL constant is NONE")
    class NoPolicyScenario {

        @Test
        @DisplayName("APT: generated plan POL constant initialised as EffectiveInputPolicies.NONE")
        void apt_noPolicies() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.NoPolicyResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/no-policy")
                            @PermitAll
                            public class NoPolicyResource {
                                public NoPolicyResource() {}
                                @GET
                                public String search(@QueryParam("q") String q) { return q; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.NoPolicyResource_search_0_ExecutionPlan", PolicyLiteralAssertions.none());
        }

        @Test
        @DisplayName("APT: descriptor CC_ constant is empty string array when no @Canonicalize")
        void apt_noPolicies_descriptorCcEmpty() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.NoPolicyResource2", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/no-policy2")
                            @PermitAll
                            public class NoPolicyResource2 {
                                public NoPolicyResource2() {}
                                @GET
                                public String search(@QueryParam("q") String q) { return q; }
                            }
                            """));

            result.assertSuccess();
            // Empty CC constant: new String[0]
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.NoPolicyResource2_JaxRsDescriptor", "new String[0]");
        }
    }

    @Nested
    @DisplayName("class-level @Canonicalize — POL constant contains canonicalizer class literal")
    class ClassLevelCanonicalizerScenario {

        @Test
        @DisplayName("APT: generated plan POL constant contains StubCanonicalizer class literal")
        void apt_classLevelCanonicalize() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.ClassLevelCanonicalizerResource", String.format("""
                                    package dev.vertique.test;

                                    import %s;
                                    import dev.vertique.core.sanitization.Canonicalize;
                                    import jakarta.annotation.security.PermitAll;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import jakarta.ws.rs.QueryParam;

                                    @Path("/class-canon")
                                    @PermitAll
                                    @Canonicalize(StubCanonicalizer.class)
                                    public class ClassLevelCanonicalizerResource {
                                        public ClassLevelCanonicalizerResource() {}
                                        @GET
                                        public String search(@QueryParam("q") String q) { return q; }
                                    }
                                    """, STUB_CANON_FQN)));

            result.assertSuccess();
            // POL constant must include StubCanonicalizer.class, not be NONE
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.ClassLevelCanonicalizerResource_search_0_ExecutionPlan",
                    PolicyLiteralAssertions.classLiteral(STUB_CANON_SIMPLE));
        }
    }

    @Nested
    @DisplayName("method-level @Canonicalize overrides class-level")
    class MethodOverridesClassScenario {

        @Test
        @DisplayName("APT: plan POL contains StubCanonicalizer2 (not StubCanonicalizer)")
        void apt_methodOverridesClass() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.MethodOverridesClassResource",
                            String.format("""
                                    package dev.vertique.test;

                                    import %s;
                                    import %s;
                                    import dev.vertique.core.sanitization.Canonicalize;
                                    import jakarta.annotation.security.PermitAll;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import jakarta.ws.rs.QueryParam;

                                    @Path("/method-overrides")
                                    @PermitAll
                                    @Canonicalize(StubCanonicalizer.class)
                                    public class MethodOverridesClassResource {
                                        public MethodOverridesClassResource() {}
                                        @GET
                                        @Canonicalize(StubCanonicalizer2.class)
                                        public String search(@QueryParam("q") String q) { return q; }
                                    }
                                    """, STUB_CANON_FQN, STUB_CANON2_FQN)));

            result.assertSuccess();
            // Method-level wins — plan POL must mention StubCanonicalizer2
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.MethodOverridesClassResource_search_0_ExecutionPlan",
                    PolicyLiteralAssertions.classLiteral(STUB_CANON2_SIMPLE));
        }
    }

    @Nested
    @DisplayName("method-level @SkipCanonicalization voids the route chain")
    class RouteSkipScenario {

        @Test
        @DisplayName("APT: plan POL is NONE when route is skipped by @SkipCanonicalization")
        void apt_routeSkip() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.RouteSkipResource", String.format("""
                                    package dev.vertique.test;

                                    import %s;
                                    import dev.vertique.core.sanitization.Canonicalize;
                                    import dev.vertique.core.sanitization.SkipCanonicalization;
                                    import jakarta.annotation.security.PermitAll;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import jakarta.ws.rs.QueryParam;

                                    @Path("/route-skip")
                                    @PermitAll
                                    @Canonicalize(StubCanonicalizer.class)
                                    public class RouteSkipResource {
                                        public RouteSkipResource() {}
                                        @GET
                                        @SkipCanonicalization
                                        public String search(@QueryParam("q") String q) { return q; }
                                    }
                                    """, STUB_CANON_FQN)));

            result.assertSuccess();
            // Route-level skip means empty chain → POL must be NONE
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.RouteSkipResource_search_0_ExecutionPlan", PolicyLiteralAssertions.none());
        }
    }

    @Nested
    @DisplayName("class-level @Sanitize — POL constant contains sanitizer class literal")
    class ClassLevelSanitizerScenario {

        @Test
        @DisplayName("APT: plan POL constant contains StubSanitizer class literal")
        void apt_classLevelSanitize() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.ClassLevelSanitizerResource", String.format("""
                                    package dev.vertique.test;

                                    import %s;
                                    import dev.vertique.core.sanitization.Sanitize;
                                    import jakarta.annotation.security.PermitAll;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;
                                    import jakarta.ws.rs.QueryParam;

                                    @Path("/class-sanit")
                                    @PermitAll
                                    @Sanitize(StubSanitizer.class)
                                    public class ClassLevelSanitizerResource {
                                        public ClassLevelSanitizerResource() {}
                                        @GET
                                        public String search(@QueryParam("q") String q) { return q; }
                                    }
                                    """, STUB_SANIT_FQN)));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.ClassLevelSanitizerResource_search_0_ExecutionPlan",
                    PolicyLiteralAssertions.classLiteral(STUB_SANIT_SIMPLE));
        }
    }
}
