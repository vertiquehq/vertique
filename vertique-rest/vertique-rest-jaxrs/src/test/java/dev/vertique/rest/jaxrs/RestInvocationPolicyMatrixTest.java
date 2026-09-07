// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.PolicyAxis;
import dev.vertique.input.processing.testkit.A;
import dev.vertique.input.processing.testkit.B;
import dev.vertique.input.processing.testkit.C;
import dev.vertique.input.processing.testkit.ComposedSanitize;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Row;
import dev.vertique.input.processing.testkit.K;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 (T017, issue #379) — proves that the real {@link ResourceScanner} and
 * {@link ParameterExtractor} reproduce every IP-01..IP-19 scenario from
 * {@link InvocationPolicyScenarios}, the single source of truth also consumed by the
 * {@code vertique-input-processing} resolver/adapter proofs.
 *
 * <p>Each row is driven through the real production seams rather than a re-implementation:
 *
 * <ul>
 *   <li>Route chains: {@code new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource)}
 *       (as {@code InputPolicyRuntimeParityTest.scanFirst}), asserting {@link ResourceMethodMeta#routeCanonicalizerChain()}
 *       / {@link ResourceMethodMeta#routeSanitizerChain()}, or that scanning throws for a route-level
 *       conflict (IP-14, IP-17, IP-19).</li>
 *   <li>Parameter chains: the package-private {@link ParameterExtractor#invocationPolicies(ResourceMethodMeta)}
 *       accessor (parameter 0), or that it throws for the parameter-level conflict (IP-15).</li>
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
 * <p><b>Expected initial (baseline) result</b>: every row passes except IP-15. Today's
 * {@code ResourceScanner} already fails fast on a route-level (method/class) additive+skip conflict
 * (IP-14, IP-17, IP-19), so the route-conflict assertion below only requires an
 * {@link IllegalStateException} — the broad type both the current bespoke checks and the target
 * {@code InvocationPolicyConflictException} (a subtype) satisfy — rather than pinning the exact
 * exception type or message text, since only the parameter-level conflict (IP-15) is the behavior
 * change this task makes: today's {@code ParameterExtractor} silently resolves the parameter chain to
 * {@code []} instead of rejecting the conflicting declaration.
 *
 * <p><b>Conflict rows (IP-14, IP-15, IP-17, IP-19).</b> Every conflict now asserts the specific
 * {@link InvocationPolicyConflictException} type (a subtype of the {@code IllegalStateException} the
 * pre-adoption bespoke checks threw) and that the message names both declaration sites. IP-17's
 * fixture reuses the exact {@code IFoo}/{@code FooImpl} names {@code InvocationPolicyScenarios}'s own
 * carrier does, so its assertion pins the exact contract message literal via
 * {@link InvocationPolicyScenarios#expectedConflictMessage}; the other three rows use locally-named
 * fixture classes (distinct from the shared matrix's own carriers) and so can only assert that both
 * declaration sites are named, not the exact literal.
 */
class RestInvocationPolicyMatrixTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")
    @DisplayName("REST scanner/extractor reproduce the IP-01..IP-19 matrix")
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
     * class doc) and that its message names both declaration sites: the exact contract literal for
     * IP-17 (whose fixture class names — {@code IFoo}/{@code FooImpl} — match
     * {@link InvocationPolicyScenarios}'s own carriers), or a both-sites containment check for
     * IP-14/IP-15/IP-19 (whose fixture classes are named locally in this file and so cannot share the
     * matrix's exact literal).
     */
    private static void assertConflictSites(String rowId, InvocationPolicyConflictException ex) {
        assertEquals(PolicyAxis.SANITIZE, ex.axis(), rowId + ": every conflict row in this matrix is SANITIZE-only");
        switch (rowId) {
            case "IP-14" -> assertConflictMessageNamesSites(ex, "Ip14Resource.bar", "Ip14Resource.bar");
            case "IP-15" -> assertConflictMessageNamesSites(ex, "Ip15Resource.bar", "Ip15Resource.bar");
            case "IP-17" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"),
                        ex.getMessage(),
                        rowId
                                + ": exact contract message literal (fixture class names match the shared matrix carriers)");
            case "IP-19" -> assertConflictMessageNamesSites(ex, "Ip19Resource", "Ip19Base");
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
    // One @Path("/ipNN") resource per scenario, each with a single @POST bar(String p) method, whose
    // shapes (class/method/parameter/interface/superclass) mirror InvocationPolicyScenarios's own
    // carriers exactly. The interface/superclass carriers are named to match InvocationPolicyScenarios
    // (e.g. IP-17's IFoo/FooImpl pair) so the contract's message-shape literal (IFoo.bar / FooImpl.bar)
    // still applies conceptually even though this test does not pin exact conflict messages.

    private static final Map<String, Object> FIXTURES = Map.ofEntries(
            Map.entry("IP-01", new Ip01Resource()),
            Map.entry("IP-02", new Ip02Resource()),
            Map.entry("IP-03", new Ip03Resource()),
            Map.entry("IP-04", new Ip04Resource()),
            Map.entry("IP-05", new Ip05Resource()),
            Map.entry("IP-06", new Ip06Resource()),
            Map.entry("IP-07", new Ip07Resource()),
            Map.entry("IP-08", new Ip08Resource()),
            Map.entry("IP-09", new Ip09Resource()),
            Map.entry("IP-10", new Ip10Resource()),
            Map.entry("IP-11", new Ip11Resource()),
            Map.entry("IP-12", new Ip12Resource()),
            Map.entry("IP-13", new Ip13Resource()),
            Map.entry("IP-14", new Ip14Resource()),
            Map.entry("IP-15", new Ip15Resource()),
            Map.entry("IP-16", new Ip16Resource()),
            Map.entry("IP-17", new FooImpl()),
            Map.entry("IP-18", new Ip18Resource()),
            Map.entry("IP-19", new Ip19Resource()));

    // --- Carriers ---

    /** IP-01: no annotations anywhere. */
    @Path("/ip01")
    static class Ip01Resource {
        @POST
        public void bar(String p) {}
    }

    /** IP-02: class-level {@code @Sanitize(A)}. */
    @Path("/ip02")
    @Sanitize(A.class)
    static class Ip02Resource {
        @POST
        public void bar(String p) {}
    }

    /** IP-03: method-level {@code @Sanitize(B)}. */
    @Path("/ip03")
    static class Ip03Resource {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    /** IP-04: class {@code @Sanitize(A)}, method {@code @Sanitize(B)} — method overrides class. */
    @Path("/ip04")
    @Sanitize(A.class)
    static class Ip04Resource {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    /** IP-05: class {@code @Sanitize(A)}, method {@code @SkipSanitization} — route opt-out. */
    @Path("/ip05")
    @Sanitize(A.class)
    static class Ip05Resource {
        @POST
        @SkipSanitization
        public void bar(String p) {}
    }

    /** IP-06: method-level {@code @SkipSanitization} only. */
    @Path("/ip06")
    static class Ip06Resource {
        @POST
        @SkipSanitization
        public void bar(String p) {}
    }

    /** IP-07: class {@code @Sanitize(A)}, parameter {@code @Sanitize(C)} — parameter overrides route. */
    @Path("/ip07")
    @Sanitize(A.class)
    static class Ip07Resource {
        @POST
        public void bar(@Sanitize(C.class) String p) {}
    }

    /** IP-08: class {@code @Sanitize(A)}, parameter {@code @SkipSanitization} — parameter opt-out. */
    @Path("/ip08")
    @Sanitize(A.class)
    static class Ip08Resource {
        @POST
        public void bar(@SkipSanitization String p) {}
    }

    /** IP-09: interface method {@code @Sanitize(B)}, unannotated concrete override. */
    interface Ip09Iface {
        @Sanitize(B.class)
        void bar(String p);
    }

    @Path("/ip09")
    static class Ip09Resource implements Ip09Iface {
        @Override
        @POST
        public void bar(String p) {}
    }

    /** IP-10: class-level {@code @Sanitize(A)} on the superclass; subclass declares nothing. */
    @Sanitize(A.class)
    static class Ip10Base {
        @POST
        public void bar(String p) {}
    }

    @Path("/ip10")
    static class Ip10Resource extends Ip10Base {}

    /** IP-11: method-level {@code @ComposedSanitize} (meta-annotated {@code @Sanitize(B)}). */
    @Path("/ip11")
    static class Ip11Resource {
        @POST
        @ComposedSanitize
        public void bar(String p) {}
    }

    /** IP-12: interface method's parameter carries {@code @Sanitize(C)}. */
    interface Ip12Iface {
        void bar(@Sanitize(C.class) String p);
    }

    @Path("/ip12")
    static class Ip12Resource implements Ip12Iface {
        @Override
        @POST
        public void bar(String p) {}
    }

    /** IP-13: class {@code @Canonicalize(K)}, method {@code @Sanitize(B)} — independent axes. */
    @Path("/ip13")
    @Canonicalize(K.class)
    static class Ip13Resource {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    /** IP-14: method declares both {@code @Sanitize(B)} and {@code @SkipSanitization} — conflict. */
    @Path("/ip14")
    static class Ip14Resource {
        @POST
        @Sanitize(B.class)
        @SkipSanitization
        public void bar(String p) {}
    }

    /** IP-15: parameter declares both {@code @Sanitize(C)} and {@code @SkipSanitization} — conflict. */
    @Path("/ip15")
    static class Ip15Resource {
        @POST
        public void bar(@Sanitize(C.class) @SkipSanitization String p) {}
    }

    /** IP-16: interface method {@code @SkipSanitization}, unannotated concrete override. */
    interface Ip16Iface {
        @SkipSanitization
        void bar(String p);
    }

    @Path("/ip16")
    static class Ip16Resource implements Ip16Iface {
        @Override
        @POST
        public void bar(String p) {}
    }

    /**
     * IP-17: override {@code @SkipSanitization} of an interface method {@code @Sanitize(B)} — conflict.
     * Named to match {@link InvocationPolicyScenarios}'s {@code IFoo}/{@code FooImpl} pair so the
     * contract's message-shape literal ({@code IFoo.bar} / {@code FooImpl.bar}) applies.
     */
    interface IFoo {
        @Sanitize(B.class)
        void bar(String p);
    }

    @Path("/ip17")
    static class FooImpl implements IFoo {
        @Override
        @POST
        @SkipSanitization
        public void bar(String p) {}
    }

    /** IP-18: override {@code @Sanitize(A)} of a superclass method {@code @Sanitize(B)} — replace, not remove. */
    static class Ip18Base {
        @POST
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    @Path("/ip18")
    static class Ip18Resource extends Ip18Base {
        @Override
        @POST
        @Sanitize(A.class)
        public void bar(String p) {}
    }

    /** IP-19: superclass {@code @SkipSanitization}, subclass {@code @Sanitize(A)} — conflict (class-level). */
    @SkipSanitization
    static class Ip19Base {
        @POST
        public void bar(String p) {}
    }

    @Path("/ip19")
    @Sanitize(A.class)
    static class Ip19Resource extends Ip19Base {}
}
