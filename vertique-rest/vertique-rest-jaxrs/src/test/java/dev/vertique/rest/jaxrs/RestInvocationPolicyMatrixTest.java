// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.sanitization.processing.EffectiveInputPolicies;
import dev.vertique.sanitization.processing.InvocationPolicyConflictException;
import dev.vertique.sanitization.processing.PolicyAxis;
import dev.vertique.sanitization.processing.testkit.A;
import dev.vertique.sanitization.processing.testkit.B;
import dev.vertique.sanitization.processing.testkit.C;
import dev.vertique.sanitization.processing.testkit.ComposedSanitize;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Row;
import dev.vertique.sanitization.processing.testkit.K;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that the real {@link ResourceScanner} and {@link ParameterExtractor} reproduce every scenario
 * from {@link InvocationPolicyScenarios}, the single source of truth also consumed by the
 * {@code vertique-sanitization} resolver/adapter proofs.
 *
 * <p>Each row is driven through the real production seams rather than a re-implementation:
 *
 * <ul>
 *   <li>Route chains: {@code new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource)}
 *       (as {@code InputPolicyRuntimeParityTest.scanFirst}), asserting
 *       {@link ResourceMethodMeta#routeCanonicalizerChain()} /
 *       {@link ResourceMethodMeta#routeSanitizerChain()}, or that scanning throws for a route-level
 *       conflict ({@code same-method-conflict}, {@code override-skip-conflict},
 *       {@code supertype-skip-conflict}).</li>
 *   <li>Parameter chains: the package-private
 *       {@link ParameterExtractor#invocationPolicies(ResourceMethodMeta)} accessor (parameter 0), or
 *       that it throws for the parameter-level conflict ({@code same-parameter-conflict}).</li>
 * </ul>
 *
 * <p>Every non-conflict row is asserted by chain equality against the matrix's expectation — never
 * merely "no exception was thrown". Fixture carriers mirror {@link InvocationPolicyScenarios}'s own
 * carriers exactly (same interface/superclass shapes, same testkit stub classes {@link A}, {@link B},
 * {@link C}, {@link K}, {@link ComposedSanitize}), wrapped with the {@code @Path}/{@code @POST}
 * scaffolding {@link ResourceScanner} requires to discover a route at all. Every carrier's sole
 * parameter, {@code p}, carries no JAX-RS binding annotation, so it is always a {@code BODY}
 * parameter (unannotated parameter -&gt; BODY, policy-eligible per {@code ParameterExtractor}) —
 * uniform across all 19 rows.
 *
 * <p><b>Conflict rows</b> ({@code same-method-conflict}, {@code same-parameter-conflict},
 * {@code override-skip-conflict}, {@code supertype-skip-conflict}). Every conflict asserts the specific
 * {@link InvocationPolicyConflictException} type (a {@code ConfigurationException}) and that the
 * message names both declaration sites. The {@code override-skip-conflict} fixture reuses the exact
 * {@code IFoo}/{@code FooImpl} names {@code InvocationPolicyScenarios}'s own carrier does, so its
 * assertion pins the exact message literal via
 * {@link InvocationPolicyScenarios#expectedConflictMessage}; the other three rows use locally-named
 * fixture classes (distinct from the shared matrix's own carriers) and so can only assert that both
 * declaration sites are named, not the exact literal.
 */
class RestInvocationPolicyMatrixTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios#rows")
    @DisplayName("REST scanner/extractor reproduce the invocation-policy matrix")
    void matrixRowMatchesRealScannerAndExtractor(Row row) {
        Object resource = FIXTURES.get(row.id());
        assertThatFixtureExists(row.id(), resource);

        ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());

        boolean routeConflict = isConflict(row.canonicalize().route())
                || isConflict(row.sanitize().route());
        if (routeConflict) {
            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> scanner.scanResource(resource),
                    row.id() + ": expected a route-level invocation-policy conflict at scan");
            assertConflictSites(row.id(), ex);
            return;
        }

        List<ResourceMethodMeta> metas = scanner.scanResource(resource);
        assertEquals(1, metas.size(), row.id() + ": expected exactly one discovered method");
        ResourceMethodMeta meta = metas.get(0);

        assertEquals(
                chainValues(row.canonicalize().route()),
                meta.routeCanonicalizerChain(),
                row.id() + ": route canonicalizer chain");
        assertEquals(
                chainValues(row.sanitize().route()), meta.routeSanitizerChain(), row.id() + ": route sanitizer chain");

        boolean paramConflict = isConflict(row.canonicalize().param())
                || isConflict(row.sanitize().param());
        if (paramConflict) {
            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> ParameterExtractor.invocationPolicies(meta),
                    row.id() + ": expected a parameter-level invocation-policy conflict at scan");
            assertConflictSites(row.id(), ex);
            return;
        }

        EffectiveInputPolicies paramPolicies = ParameterExtractor.invocationPolicies(meta)[0];
        assertEquals(
                chainValues(row.canonicalize().param()),
                paramPolicies.canonicalizers(),
                row.id() + ": param canonicalizer chain");
        assertEquals(
                chainValues(row.sanitize().param()), paramPolicies.sanitizers(), row.id() + ": param sanitizer chain");
    }

    // --- Conflict assertion helpers ---

    /**
     * Asserts the conflict's axis (every conflict row in this matrix is SANITIZE-only — see the
     * class doc) and that its message names both declaration sites: the exact message literal for
     * {@code override-skip-conflict} (whose fixture class names — {@code IFoo}/{@code FooImpl} — match
     * {@link InvocationPolicyScenarios}'s own carriers), or a both-sites containment check for
     * {@code same-method-conflict}/{@code same-parameter-conflict}/{@code supertype-skip-conflict}
     * (whose fixture classes are named locally in this file and so cannot share the matrix's exact
     * literal).
     */
    private static void assertConflictSites(String rowId, InvocationPolicyConflictException ex) {
        assertEquals(PolicyAxis.SANITIZE, ex.axis(), rowId + ": every conflict row in this matrix is SANITIZE-only");
        switch (rowId) {
            case "same-method-conflict" ->
                assertConflictMessageNamesSites(ex, "SameMethodConflictResource.bar", "SameMethodConflictResource.bar");
            case "same-parameter-conflict" ->
                assertConflictMessageNamesSites(
                        ex, "SameParameterConflictResource.bar", "SameParameterConflictResource.bar");
            case "override-skip-conflict" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"),
                        ex.getMessage(),
                        rowId + ": exact message literal (fixture class names match the shared matrix carriers)");
            case "supertype-skip-conflict" ->
                assertConflictMessageNamesSites(ex, "SupertypeSkipConflictResource", "SupertypeSkipConflictBase");
            default -> throw new AssertionError("no conflict-site expectation registered for " + rowId);
        }
    }

    private static void assertConflictMessageNamesSites(
            InvocationPolicyConflictException ex, String additiveSite, String skipSite) {
        String message = ex.getMessage();
        assertTrue(message.contains(additiveSite), "message must name the additive declaration site: " + message);
        assertTrue(message.contains(skipSite), "message must name the skip declaration site: " + message);
    }

    // --- Outcome helpers ---

    private static boolean isConflict(Outcome outcome) {
        return outcome instanceof Outcome.Conflict;
    }

    private static List<Class<?>> chainValues(Outcome outcome) {
        return ((Outcome.Chain) outcome).values();
    }

    private static void assertThatFixtureExists(String id, Object resource) {
        if (resource == null) {
            throw new AssertionError("no fixture resource mapped for " + id);
        }
    }

    // --- Fixture registry ---
    // One @Path("/<scenario>") resource per scenario, each with a single @POST bar(String p) method,
    // whose shapes (class/method/parameter/interface/superclass) mirror InvocationPolicyScenarios's own
    // carriers exactly. The interface/superclass carriers are named to match InvocationPolicyScenarios
    // (e.g. override-skip-conflict's IFoo/FooImpl pair) so the message-shape literal
    // (IFoo.bar / FooImpl.bar) still applies conceptually even though this test does not pin exact
    // conflict messages for every row.

    private static final Map<String, Object> FIXTURES = Map.ofEntries(
            Map.entry("no-policy", new NoPolicyResource()),
            Map.entry("type-chain", new TypeChainResource()),
            Map.entry("method-chain", new MethodChainResource()),
            Map.entry("method-overrides-type", new MethodOverridesTypeResource()),
            Map.entry("method-skip-over-type-chain", new MethodSkipOverTypeChainResource()),
            Map.entry("method-skip-only", new MethodSkipOnlyResource()),
            Map.entry("parameter-overrides-type", new ParameterOverridesTypeResource()),
            Map.entry("parameter-skip-over-type-chain", new ParameterSkipOverTypeChainResource()),
            Map.entry("interface-method-chain", new InterfaceMethodChainResource()),
            Map.entry("superclass-type-chain", new SuperclassTypeChainResource()),
            Map.entry("composed-annotation", new ComposedAnnotationResource()),
            Map.entry("interface-parameter-chain", new InterfaceParameterChainResource()),
            Map.entry("independent-axes", new IndependentAxesResource()),
            Map.entry("same-method-conflict", new SameMethodConflictResource()),
            Map.entry("same-parameter-conflict", new SameParameterConflictResource()),
            Map.entry("interface-method-skip", new InterfaceMethodSkipResource()),
            Map.entry("override-skip-conflict", new FooImpl()),
            Map.entry("override-replaces-chain", new OverrideReplacesChainResource()),
            Map.entry("supertype-skip-conflict", new SupertypeSkipConflictResource()));

    // --- Carriers ---

    /** no-policy: no annotations anywhere. */
    @Path("/no-policy")
    static class NoPolicyResource {
        @POST
        public void bar(String p) {}
    }

    /** type-chain: class-level {@code @Sanitize(A)}. */
    @Path("/type-chain")
    @Sanitize(A.class)
    static class TypeChainResource {
        @POST
        public void bar(String p) {}
    }

    /** method-chain: method-level {@code @Sanitize(B)}. */
    @Path("/method-chain")
    static class MethodChainResource {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    /** method-overrides-type: class {@code @Sanitize(A)}, method {@code @Sanitize(B)} — method overrides class. */
    @Path("/method-overrides-type")
    @Sanitize(A.class)
    static class MethodOverridesTypeResource {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    /** method-skip-over-type-chain: class {@code @Sanitize(A)}, method {@code @SkipSanitization} — route opt-out. */
    @Path("/method-skip-over-type-chain")
    @Sanitize(A.class)
    static class MethodSkipOverTypeChainResource {
        @POST
        @SkipSanitization
        public void bar(String p) {}
    }

    /** method-skip-only: method-level {@code @SkipSanitization} only. */
    @Path("/method-skip-only")
    static class MethodSkipOnlyResource {
        @POST
        @SkipSanitization
        public void bar(String p) {}
    }

    /**
     * parameter-overrides-type: class {@code @Sanitize(A)}, parameter {@code @Sanitize(C)} — parameter overrides route.
     */
    @Path("/parameter-overrides-type")
    @Sanitize(A.class)
    static class ParameterOverridesTypeResource {
        @POST
        public void bar(@Sanitize(C.class) String p) {}
    }

    /**
     * parameter-skip-over-type-chain: class {@code @Sanitize(A)}, parameter {@code @SkipSanitization} — parameter opt-
     * out.
     */
    @Path("/parameter-skip-over-type-chain")
    @Sanitize(A.class)
    static class ParameterSkipOverTypeChainResource {
        @POST
        public void bar(@SkipSanitization String p) {}
    }

    /** interface-method-chain: interface method {@code @Sanitize(B)}, unannotated concrete override. */
    interface InterfaceMethodChainIface {
        @Sanitize(B.class)
        void bar(String p);
    }

    @Path("/interface-method-chain")
    static class InterfaceMethodChainResource implements InterfaceMethodChainIface {
        @Override
        @POST
        public void bar(String p) {}
    }

    /** superclass-type-chain: class-level {@code @Sanitize(A)} on the superclass; subclass declares nothing. */
    @Sanitize(A.class)
    static class SuperclassTypeChainBase {
        @POST
        public void bar(String p) {}
    }

    @Path("/superclass-type-chain")
    static class SuperclassTypeChainResource extends SuperclassTypeChainBase {}

    /** composed-annotation: method-level {@code @ComposedSanitize} (meta-annotated {@code @Sanitize(B)}). */
    @Path("/composed-annotation")
    static class ComposedAnnotationResource {
        @POST
        @ComposedSanitize
        public void bar(String p) {}
    }

    /** interface-parameter-chain: interface method's parameter carries {@code @Sanitize(C)}. */
    interface InterfaceParameterChainIface {
        void bar(@Sanitize(C.class) String p);
    }

    @Path("/interface-parameter-chain")
    static class InterfaceParameterChainResource implements InterfaceParameterChainIface {
        @Override
        @POST
        public void bar(String p) {}
    }

    /** independent-axes: class {@code @Canonicalize(K)}, method {@code @Sanitize(B)} — independent axes. */
    @Path("/independent-axes")
    @Canonicalize(K.class)
    static class IndependentAxesResource {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    /** same-method-conflict: method declares both {@code @Sanitize(B)} and {@code @SkipSanitization} — conflict. */
    @Path("/same-method-conflict")
    static class SameMethodConflictResource {
        @POST
        @Sanitize(B.class)
        @SkipSanitization
        public void bar(String p) {}
    }

    /**
     * same-parameter-conflict: parameter declares both {@code @Sanitize(C)} and {@code @SkipSanitization} — conflict.
     */
    @Path("/same-parameter-conflict")
    static class SameParameterConflictResource {
        @POST
        public void bar(@Sanitize(C.class) @SkipSanitization String p) {}
    }

    /** interface-method-skip: interface method {@code @SkipSanitization}, unannotated concrete override. */
    interface InterfaceMethodSkipIface {
        @SkipSanitization
        void bar(String p);
    }

    @Path("/interface-method-skip")
    static class InterfaceMethodSkipResource implements InterfaceMethodSkipIface {
        @Override
        @POST
        public void bar(String p) {}
    }

    /**
     * override-skip-conflict: override {@code @SkipSanitization} of an interface method
     * {@code @Sanitize(B)} — conflict. Named to match {@link InvocationPolicyScenarios}'s
     * {@code IFoo}/{@code FooImpl} pair so the message-shape literal ({@code IFoo.bar} /
     * {@code FooImpl.bar}) applies.
     */
    interface IFoo {
        @Sanitize(B.class)
        void bar(String p);
    }

    @Path("/override-skip-conflict")
    static class FooImpl implements IFoo {
        @Override
        @POST
        @SkipSanitization
        public void bar(String p) {}
    }

    /**
     * override-replaces-chain: override {@code @Sanitize(A)} of a superclass method {@code @Sanitize(B)} — replace, not
     * remove.
     */
    static class OverrideReplacesChainBase {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    @Path("/override-replaces-chain")
    static class OverrideReplacesChainResource extends OverrideReplacesChainBase {
        @Override
        @POST
        @Sanitize(A.class)
        public void bar(String p) {}
    }

    /**
     * supertype-skip-conflict: superclass {@code @SkipSanitization}, subclass {@code @Sanitize(A)} — conflict (class-
     * level).
     */
    @SkipSanitization
    static class SupertypeSkipConflictBase {
        @POST
        public void bar(String p) {}
    }

    @Path("/supertype-skip-conflict")
    @Sanitize(A.class)
    static class SupertypeSkipConflictResource extends SupertypeSkipConflictBase {}
}
