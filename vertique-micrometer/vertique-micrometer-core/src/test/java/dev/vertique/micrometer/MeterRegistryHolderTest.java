// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MeterRegistryHolder} — verifies the pre-bootstrap state guarantees
 * (NFR-TEL-003: empty composite no-op), bootstrap idempotency guard, stable-reference semantics,
 * late-wiring of pre-bootstrap meters, detach-on-shutdown, and reset-for-tests behaviour.
 */
class MeterRegistryHolderTest {

    @AfterEach
    void reset() {
        MeterRegistryHolder.resetForTests();
    }

    // --- Pre-bootstrap state ---

    @Test
    @DisplayName("registry() is non-null before any bootstrap call")
    void registryNonNullBeforeBootstrap() {
        assertNotNull(MeterRegistryHolder.registry());
        assertFalse(MeterRegistryHolder.bootstrapped());
    }

    @Test
    @DisplayName("recording to counter on empty composite is a no-op (counter.count() == 0)")
    void recordingToEmptyCompositeIsNoOp() {
        CompositeMeterRegistry registry = MeterRegistryHolder.registry();

        Counter counter = registry.counter("test.counter");
        counter.increment();

        // No child registries → the counter is a no-op; count() should remain 0
        assertEquals(0.0, counter.count(), 1e-9);
    }

    // --- Bootstrap idempotency ---

    @Test
    @DisplayName("bootstrap() twice → IllegalStateException on second call")
    void doubleBootstrapThrows() {
        MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
        MicrometerAssembly assembly = MicrometerAssembly.assemble(List.of(), config, new JsonObject());

        MeterRegistryHolder.bootstrap(assembly);

        assertTrue(MeterRegistryHolder.bootstrapped());
        assertThrows(IllegalStateException.class, () -> MeterRegistryHolder.bootstrap(assembly));
    }

    // --- Stable-reference semantics (Fix 1 — RED TESTS) ---

    @Nested
    @DisplayName("stable-reference semantics — registry() returns the SAME instance across bootstrap")
    class StableReference {

        /**
         * RED TEST: Captures a reference to registry() BEFORE bootstrap. After bootstrap,
         * the SAME reference must still be returned by registry(), and pre-bootstrap
         * meters must begin forwarding to the child backend (late-wiring).
         */
        @Test
        @DisplayName("registry() reference is stable across bootstrap — same instance before and after")
        void registryReferenceStableAcrossBootstrap() {
            // Capture reference BEFORE bootstrap
            MeterRegistry early = MeterRegistryHolder.registry();

            // Pre-bootstrap counter — currently a no-op
            Counter earlyCounter = early.counter("pre.bootstrap.counter");
            earlyCounter.increment(); // no-op: no children yet

            // Build an assembly with a SimpleMeterRegistry backend
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
            MicrometerAssembly assembly = assembleWithSimple("stableref", simple, config);

            // Bootstrap — under the OLD swap behavior registry() returns a DIFFERENT object
            MeterRegistryHolder.bootstrap(assembly);

            // Fix 1 contract: registry() must return the SAME instance as captured before bootstrap
            assertSame(
                    early,
                    MeterRegistryHolder.registry(),
                    "registry() must return the same outer instance across bootstrap");

            // Fix 1 contract: increment the same earlyCounter again — the inner simple backend
            // must show count >= 1 because Micrometer late-wires pre-existing meters to new children
            earlyCounter.increment();
            Counter childCounter = simple.find("pre.bootstrap.counter").counter();
            assertNotNull(childCounter, "pre-bootstrap meter must be visible in child after bootstrap");
            assertTrue(
                    childCounter.count() >= 1.0,
                    "pre-bootstrap meter must forward increments after late-wire; got " + childCounter.count());
        }

        /**
         * Fix 4 — tests the REAL detach path via {@link MeterRegistryHolder#detach(MicrometerAssembly)}.
         *
         * <p>After bootstrap + detach, increments on the outer must NOT reach the child backend,
         * and must not throw. The previous version of this test called {@code assembly.close()}
         * directly (which does NOT detach) — corrected to call the actual detach path.
         */
        @Test
        @DisplayName("after MeterRegistryHolder.detach(), increments do not reach the child backend; no exception")
        void incrementsAfterDetachDoNotReachClosedChild() {
            SimpleMeterRegistry simple = new SimpleMeterRegistry();
            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
            MicrometerAssembly assembly = assembleWithSimple("detach", simple, config);

            // Bootstrap — outer now has inner as child
            MeterRegistryHolder.bootstrap(assembly);
            MeterRegistry outer = MeterRegistryHolder.registry();

            // Record a baseline count before detach; this must reach the backend
            outer.counter("after.detach.counter").increment();
            Counter backendBefore = simple.find("after.detach.counter").counter();
            assertNotNull(backendBefore, "counter must be visible in backend before detach");
            double countBefore = backendBefore.count();
            assertTrue(countBefore >= 1.0, "baseline increment must have reached backend before detach");

            // Real detach path — mirrors what MicrometerMetricsContributor.onShutdown() does
            MeterRegistryHolder.detach(assembly);
            assembly.close();

            // After detach, increment on the outer must not throw
            assertDoesNotThrow(
                    () -> outer.counter("after.detach.counter").increment(), "Increment after detach must not throw");

            // After detach, the backend counter count must NOT have increased: the child
            // is no longer wired to the outer, so new increments do not reach it.
            // (The counter instance in 'simple' is closed, so find() may return null.)
            Counter backendAfter = simple.find("after.detach.counter").counter();
            if (backendAfter != null) {
                assertEquals(
                        countBefore,
                        backendAfter.count(),
                        1e-9,
                        "Backend counter must not receive increments after detach");
            }
            // else: null is acceptable — closed registry purges its meters
        }

        /**
         * RED TEST: registry() must return the same outer instance AFTER resetForTests(),
         * not a brand-new object. (Stable-instance contract over the entire test lifecycle.)
         */
        @Test
        @DisplayName("registry() returns the same outer instance after resetForTests()")
        void registrySameInstanceAfterReset() {
            MeterRegistry before = MeterRegistryHolder.registry();

            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
            MicrometerAssembly assembly = MicrometerAssembly.assemble(List.of(), config, new JsonObject());
            MeterRegistryHolder.bootstrap(assembly);

            MeterRegistryHolder.resetForTests();

            // Under old swap semantics, resetForTests() does REGISTRY.set(new CompositeMeterRegistry())
            // — the reference changes. Under the new contract it MUST NOT change.
            assertSame(
                    before,
                    MeterRegistryHolder.registry(),
                    "registry() must return the same outer instance after resetForTests()");
        }
    }

    // --- Fix 1: Filter bypass through the stable outer composite (RED TESTS) ---

    /**
     * Verifies that filters applied via {@link MicrometerAssembly} are NOT bypassed when meters are
     * recorded through the stable outer composite ({@link MeterRegistryHolder#registry()}).
     *
     * <p>Before Fix 1, Micrometer's {@link io.micrometer.core.instrument.composite.CompositeMeterRegistry}
     * flattens nested composites ({@code updateDescendants()} resolves a composite child to its
     * non-composite descendants), so meters created through OUTER register directly into the backends
     * — the inner composite's filters (cardinality guard, common tags) never run.
     *
     * <p>After Fix 1, a permanent delegating filter on OUTER enforces the same cardinality cap and
     * common-tag injection that the inner composite applies.
     */
    @Nested
    @DisplayName("Fix 1 — filter bypass through stable outer composite (cardinality + common tags)")
    class FilterBypass {

        /**
         * RED TEST: cardinality cap is enforced through the OUTER registry.
         *
         * <p>Bootstrap an assembly with cap=2 and record 3 distinct {@code method} values on a
         * {@code vertique.x} counter through {@link MeterRegistryHolder#registry()} (the OUTER).
         * The backend must accumulate only 2 distinct meters; the third is denied.
         *
         * <p>CURRENTLY FAILS because the outer composite bypasses the inner's filters.
         */
        @Test
        @DisplayName("cardinality cap=2 enforced through OUTER: third method value is denied in backend")
        void cardinalityCapEnforcedThroughOuter() {
            SimpleMeterRegistry backend = new SimpleMeterRegistry();
            // cap=2 for all guarded tag keys (maxMeters=0 means no global cap)
            MetricsConfig config = new JsonObject()
                    .put(
                            "cardinality",
                            new JsonObject().put("maxTagValuesPerKey", 2).put("maxMeters", 0))
                    .mapTo(MetricsConfig.class);
            MicrometerAssembly assembly = assembleWithSimple("captest", backend, config);

            MeterRegistryHolder.bootstrap(assembly);
            MeterRegistry outer = MeterRegistryHolder.registry();

            // Record 3 distinct values for the "method" tag on a vertique.* meter
            outer.counter("vertique.x", "method", "GET").increment();
            outer.counter("vertique.x", "method", "POST").increment();
            outer.counter("vertique.x", "method", "PUT").increment(); // should be denied

            // Backend must have at most 2 distinct method values
            long distinctMethods = backend.getMeters().stream()
                    .filter(m -> m.getId().getName().equals("vertique.x"))
                    .map(m -> m.getId().getTag("method"))
                    .filter(v -> v != null)
                    .distinct()
                    .count();
            assertTrue(
                    distinctMethods <= 2,
                    "Cap=2 must deny the third method value; backend had " + distinctMethods + " distinct values");
        }

        /**
         * RED TEST: common {@code service} tag is injected through the OUTER registry.
         *
         * <p>Record a counter through {@link MeterRegistryHolder#registry()} after bootstrap with
         * {@code tags.service=my-svc}. The backend must see the counter with {@code service=my-svc}.
         *
         * <p>CURRENTLY FAILS because outer-created meters bypass the inner composite's commonTags filter.
         */
        @Test
        @DisplayName("service common-tag injected through OUTER: backend counter carries service=my-svc")
        void commonTagInjectedThroughOuter() {
            SimpleMeterRegistry backend = new SimpleMeterRegistry();
            MetricsConfig config = new JsonObject()
                    .put("tags", new JsonObject().put("service", "my-svc"))
                    .mapTo(MetricsConfig.class);
            MicrometerAssembly assembly = assembleWithSimple("tagtest", backend, config);

            MeterRegistryHolder.bootstrap(assembly);
            MeterRegistry outer = MeterRegistryHolder.registry();

            outer.counter("tagged.counter").increment();

            // The backend must have a counter with service=my-svc
            Counter tagged =
                    backend.find("tagged.counter").tag("service", "my-svc").counter();
            assertNotNull(
                    tagged, "Counter recorded through OUTER must carry service=my-svc tag in backend after Fix 1");
        }

        /**
         * RED TEST: resetForTests + re-bootstrap with a DIFFERENT cap does NOT accumulate filters.
         *
         * <p>Bootstraps with cap=2, resets, re-bootstraps with cap=5. The new cap must govern:
         * 5 distinct values must all be accepted; the 6th must be denied. If filters accumulate
         * across resets (append-only concern), the old cap=2 would fire first and deny the 3rd value.
         *
         * <p>CURRENTLY FAILS (when Fix 1 filter mechanism is active) if filters accumulate on OUTER.
         * Also demonstrates a companion concern: if cap was effectively 0 before Fix 1, this test
         * was vacuously "passing" because no filter ran — after Fix 1 the filter must run AND respect
         * the new cap, not the stale old one.
         */
        @Test
        @DisplayName("reset + re-bootstrap with cap=5: 5 values accepted, 6th denied (no filter accumulation)")
        void resetAndRebootstrapRespectsNewCap() {
            // --- First bootstrap: cap=2 ---
            SimpleMeterRegistry backend1 = new SimpleMeterRegistry();
            MetricsConfig config1 = new JsonObject()
                    .put(
                            "cardinality",
                            new JsonObject().put("maxTagValuesPerKey", 2).put("maxMeters", 0))
                    .mapTo(MetricsConfig.class);
            MicrometerAssembly assembly1 = assembleWithSimple("resetcap1", backend1, config1);
            MeterRegistryHolder.bootstrap(assembly1);
            MeterRegistryHolder.resetForTests();

            // --- Second bootstrap: cap=5 ---
            SimpleMeterRegistry backend2 = new SimpleMeterRegistry();
            MetricsConfig config2 = new JsonObject()
                    .put(
                            "cardinality",
                            new JsonObject().put("maxTagValuesPerKey", 5).put("maxMeters", 0))
                    .mapTo(MetricsConfig.class);
            MicrometerAssembly assembly2 = assembleWithSimple("resetcap2", backend2, config2);
            MeterRegistryHolder.bootstrap(assembly2);

            MeterRegistry outer = MeterRegistryHolder.registry();
            // Record 6 distinct method values — cap=5 must accept 5, deny 6th
            String[] methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"};
            for (String m : methods) {
                outer.counter("vertique.y", "method", m).increment();
            }

            long distinctMethods = backend2.getMeters().stream()
                    .filter(m -> m.getId().getName().equals("vertique.y"))
                    .map(m -> m.getId().getTag("method"))
                    .filter(v -> v != null)
                    .distinct()
                    .count();
            assertEquals(
                    5,
                    distinctMethods,
                    "After re-bootstrap with cap=5, exactly 5 distinct method values must be accepted in backend");
        }
    }

    // --- Reset ---

    @Test
    @DisplayName("resetForTests() clears bootstrapped flag and outer registry remains usable")
    void resetRestoresUnbootstrappedState() {
        MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);
        MicrometerAssembly assembly = MicrometerAssembly.assemble(List.of(), config, new JsonObject());

        MeterRegistryHolder.bootstrap(assembly);
        assertTrue(MeterRegistryHolder.bootstrapped());

        MeterRegistryHolder.resetForTests();

        assertFalse(MeterRegistryHolder.bootstrapped());
        MeterRegistry afterReset = MeterRegistryHolder.registry();
        assertNotNull(afterReset);
        // The outer composite must be usable after reset (no exception when recording)
        assertDoesNotThrow(() -> afterReset.counter("after.reset").increment());
    }

    // --- Helpers ---

    /**
     * Builds a {@link MicrometerAssembly} backed by a single {@link SimpleMeterRegistry} backend
     * under the given name, using the supplied config.
     *
     * @param name   backend name
     * @param simple the SimpleMeterRegistry to use as the backend
     * @param config metrics config
     * @return a fully assembled registry
     */
    private static MicrometerAssembly assembleWithSimple(
            String name, SimpleMeterRegistry simple, MetricsConfig config) {
        MeterRegistryProvider provider = new SimpleProvider(name, simple);
        return MicrometerAssembly.assemble(List.of(provider), config, new JsonObject());
    }

    /**
     * A minimal {@link MeterRegistryProvider} backed by a given {@link SimpleMeterRegistry}.
     */
    static final class SimpleProvider implements MeterRegistryProvider {

        private final String name;
        private final SimpleMeterRegistry registry;

        SimpleProvider(String name, SimpleMeterRegistry registry) {
            this.name = name;
            this.registry = registry;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public MeterRegistryBackend create(io.vertx.core.json.JsonObject cfg) {
            SimpleMeterRegistry reg = this.registry;
            return new MeterRegistryBackend() {
                @Override
                public io.micrometer.core.instrument.MeterRegistry registry() {
                    return reg;
                }

                @Override
                public void close() {
                    reg.close();
                }
            };
        }
    }
}
