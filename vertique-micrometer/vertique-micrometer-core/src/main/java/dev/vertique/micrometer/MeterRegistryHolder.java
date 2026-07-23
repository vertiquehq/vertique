// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global holder for the application's {@link CompositeMeterRegistry}.
 *
 * <p>The holder owns a single stable <em>outer</em> {@link CompositeMeterRegistry} for the JVM
 * lifetime. {@link #registry()} always returns that same instance — it is never replaced, not even
 * by {@link #resetForTests()}. This guarantees that any consumer that resolved the registry
 * (via Dagger injection or a direct call) before bootstrap continues to work correctly after
 * bootstrap: Micrometer's composite late-wires pre-existing meters to newly added children.
 *
 * <p>Before bootstrap the outer composite has no children — recording to it is a no-op
 * (NFR-TEL-003). After {@link #bootstrap(MicrometerAssembly)} is called exactly once, the
 * assembly's inner composite is added as a child of the stable outer composite. Filters (cardinality
 * guard, common tags) live on the inner composite and are applied when the inner composite
 * registers the forwarded meters; but because Micrometer's {@link CompositeMeterRegistry} flattens
 * nested composites when resolving children, meters created through the stable outer register
 * <em>directly</em> into the leaf backends — bypassing the inner composite's filters.
 *
 * <p><b>Fix 1 — permanent delegating filter on OUTER:</b> to close this bypass, the stable outer
 * is created with a single permanent {@link MeterFilter} installed at class-init time. This
 * delegating filter reads from {@link #ACTIVE_FILTERS} atomically, so no late-filter WARN is
 * emitted and filters cannot accumulate across test resets. The semantics mirror Micrometer's own
 * filter-chain application:
 * <ul>
 *   <li>{@code accept(id)} — iterates the active list, returning the first non-NEUTRAL reply
 *       (DENY or ACCEPT), falling back to NEUTRAL if no filter decides.</li>
 *   <li>{@code map(id)} — folds the id through every active filter in order.</li>
 *   <li>{@code configure(id, config)} — folds {@link DistributionStatisticConfig} through every
 *       active filter in order, replacing with each non-null result (mirrors
 *       {@link io.micrometer.core.instrument.MeterRegistry}'s own chain semantics).</li>
 * </ul>
 *
 * <p><b>Documented edge:</b> Micrometer caches each registered meter's pre-filter→post-filter id
 * mapping, so a meter created through the outer <em>before</em> bootstrap keeps its unfiltered id
 * (no common tags) even after bootstrap; new meters and new tag combinations map through the live
 * chain. In the supported {@code VertiqueApplication} flow no meters exist pre-bootstrap, so this
 * edge does not apply in practice.
 *
 * <p>{@link #resetForTests()} removes the inner child, closes the prior assembly, clears
 * {@code BOOTSTRAPPED}, and clears {@link #ACTIVE_FILTERS} — but <em>keeps the same outer
 * instance</em>. Tests that need meter isolation should use distinct meter names between test runs;
 * the stable outer instance is intentional (pre-bootstrap meters re-forward after a reset cycle,
 * which is the correct stable-identity behaviour).
 *
 * <p>This class is package-private; application code that needs a {@link io.micrometer.core.instrument.MeterRegistry}
 * should obtain it through Dagger injection (provided by the {@code MicrometerModule} Dagger module)
 * rather than calling {@link #registry()} directly.
 *
 * @see MicrometerModule
 * @see MicrometerMetricsContributor
 */
final class MeterRegistryHolder {

    /**
     * The active filter list published by the most recent {@link #bootstrap(MicrometerAssembly)}.
     *
     * <p>Before bootstrap this is an empty list — the delegating filter on OUTER is NEUTRAL for all
     * meters, which is correct because the outer has no children yet (no-op recording). After
     * bootstrap it contains the full ordered filter list from the assembly (common-tags filter,
     * cardinality guards, optional global cap). After {@link #resetForTests()} it is reset to empty.
     */
    private static final AtomicReference<List<MeterFilter>> ACTIVE_FILTERS = new AtomicReference<>(List.of());

    /**
     * The stable outer composite — its identity never changes after class load.
     * Bootstrap adds the assembly's inner composite as a child; reset removes it.
     *
     * <p>A single permanent delegating filter is installed here at class-init time (before any meter
     * can be registered, so no late-filter WARN fires). It delegates every call to the current
     * {@link #ACTIVE_FILTERS} list, so the filter effectively activates at bootstrap and deactivates
     * at reset without ever being re-added to the registry config.
     */
    private static final CompositeMeterRegistry OUTER = createOuter();

    /** Tracks the current assembly so it can be closed on reset or shutdown. */
    private static final AtomicReference<MicrometerAssembly> CURRENT_ASSEMBLY = new AtomicReference<>(null);

    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

    /** Prevent instantiation. */
    private MeterRegistryHolder() {}

    // --- Class-init helper ---

    /**
     * Creates the stable outer composite and installs exactly one permanent delegating
     * {@link MeterFilter} on it before any meter can be registered.
     *
     * <p>Installing at class-init guarantees:
     * <ul>
     *   <li>No late-filter WARN (Micrometer warns when a filter is added after meters exist).</li>
     *   <li>The filter is installed exactly once — test re-bootstraps update {@link #ACTIVE_FILTERS}
     *       rather than appending new filters, so filters cannot accumulate.</li>
     * </ul>
     *
     * @return the configured outer composite; never {@code null}
     */
    private static CompositeMeterRegistry createOuter() {
        CompositeMeterRegistry outer = new CompositeMeterRegistry();
        outer.config().meterFilter(new MeterFilter() {

            /**
             * Iterates the active filter list and returns the first non-NEUTRAL reply. Falls back
             * to NEUTRAL when the list is empty (pre-bootstrap) or all filters are NEUTRAL.
             * Mirrors {@link io.micrometer.core.instrument.MeterRegistry}'s accept chain semantics:
             * first DENY or ACCEPT wins; NEUTRAL is skipped.
             */
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                for (MeterFilter filter : ACTIVE_FILTERS.get()) {
                    MeterFilterReply reply = filter.accept(id);
                    if (reply != MeterFilterReply.NEUTRAL) {
                        return reply;
                    }
                }
                return MeterFilterReply.NEUTRAL;
            }

            /**
             * Folds the meter id through every active filter in order, passing each filter's output
             * as the next filter's input. Mirrors {@link io.micrometer.core.instrument.MeterRegistry}'s
             * map chain semantics.
             */
            @Override
            public Meter.Id map(Meter.Id id) {
                Meter.Id mapped = id;
                for (MeterFilter filter : ACTIVE_FILTERS.get()) {
                    mapped = filter.map(mapped);
                }
                return mapped;
            }

            /**
             * Folds {@link DistributionStatisticConfig} through every active filter in order,
             * replacing config with each non-null result. Mirrors
             * {@link io.micrometer.core.instrument.MeterRegistry}'s configure chain semantics:
             * null return means "no opinion" and is skipped.
             */
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                DistributionStatisticConfig current = config;
                for (MeterFilter filter : ACTIVE_FILTERS.get()) {
                    DistributionStatisticConfig result = filter.configure(id, current);
                    if (result != null) {
                        current = result;
                    }
                }
                return current;
            }
        });
        return outer;
    }

    // --- Public API ---

    /**
     * Returns the application-wide composite meter registry.
     *
     * <p>Before bootstrap this is an empty composite (recording is a no-op; NFR-TEL-003). After
     * {@link #bootstrap(MicrometerAssembly)} the assembly's inner composite is a child, so all
     * meters recorded through this instance are forwarded to every registered backend. The returned
     * instance is always the same object — its identity is stable for the JVM lifetime.
     *
     * @return the stable outer composite registry; never {@code null}
     */
    static CompositeMeterRegistry registry() {
        return OUTER;
    }

    /**
     * Returns {@code true} if {@link #bootstrap} has been called and completed successfully.
     *
     * @return {@code true} after a successful bootstrap; {@code false} before
     */
    static boolean bootstrapped() {
        return BOOTSTRAPPED.get();
    }

    /**
     * Bootstraps the metrics subsystem by activating the assembly's filters on the stable outer
     * composite and adding the assembly's inner composite as a child.
     *
     * <p>This method is called once during application startup. A second call from any thread
     * throws {@link IllegalStateException} — callers are expected to preflight with
     * {@link #bootstrapped()} when needed.
     *
     * <p>The filter list from the assembly is published to {@link #ACTIVE_FILTERS} <em>before</em>
     * the inner composite is added as a child, so every meter registered after this point maps
     * through the live chain. Meters created through the outer <em>before</em> bootstrap keep
     * their already-cached unfiltered id mapping when Micrometer late-wires them into the new
     * child (see the class-level "Documented edge"). The delegating filter reads
     * {@link #ACTIVE_FILTERS} once per callback ({@code accept}/{@code map}/{@code configure}),
     * so a registration racing a bootstrap/reset swap could observe a mixed chain — impossible in
     * the supported flow, where no meters exist pre-bootstrap and bootstrap runs exactly once.
     *
     * @param assembly the fully assembled registry; must not be {@code null}
     * @throws IllegalStateException if bootstrap has already been called
     */
    static void bootstrap(MicrometerAssembly assembly) {
        if (!BOOTSTRAPPED.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "MeterRegistryHolder has already been bootstrapped; bootstrap() must be called exactly once");
        }
        CURRENT_ASSEMBLY.set(assembly);
        // Activate filters on OUTER before adding the inner as a child — the delegating filter
        // reads from ACTIVE_FILTERS atomically so new meters immediately use the live chain.
        ACTIVE_FILTERS.set(assembly.filters());
        // Add the assembly's inner composite as a child of the stable outer.
        // Micrometer's composite late-wires any meters that were registered on OUTER before
        // bootstrap to the newly added child, so pre-bootstrap meters start forwarding immediately.
        OUTER.add(assembly.composite());
    }

    /**
     * Removes the current inner composite child from the stable outer composite when the assembly
     * is being closed.
     *
     * <p>After this call, increments on the stable outer no longer forward to the (closed) inner.
     * {@link #ACTIVE_FILTERS} is left as-is (post-shutdown the outer has no children — all
     * increments become no-ops); call {@link #resetForTests()} to clear it in test environments.
     * The {@code BOOTSTRAPPED} latch remains set; call {@link #resetForTests()} to clear it in
     * test environments.
     *
     * <p>This method is package-private and is called by {@link MicrometerMetricsContributor#onShutdown()}.
     *
     * @param assembly the assembly whose inner composite should be removed; must not be {@code null}
     */
    static void detach(MicrometerAssembly assembly) {
        OUTER.remove(assembly.composite());
    }

    /**
     * Tears down any existing assembly, removes its inner composite from the stable outer, clears
     * the active filter list, and restores the pre-bootstrap flag. The stable outer instance is
     * <em>kept</em>.
     *
     * <p><b>For test use only.</b> Call from {@code @AfterEach} to isolate test cases.
     */
    static void resetForTests() {
        MicrometerAssembly prev = CURRENT_ASSEMBLY.getAndSet(null);
        if (prev != null) {
            // Remove the inner child from the outer BEFORE closing the assembly
            OUTER.remove(prev.composite());
            try {
                prev.close();
            } catch (Exception ignored) {
                // best-effort teardown in test reset
            }
        }
        BOOTSTRAPPED.set(false);
        // Clear active filters so the next bootstrap starts clean — prevents filter accumulation
        // across test resets, which would cause the old cap to fire even after re-bootstrap with a
        // different cap.
        ACTIVE_FILTERS.set(List.of());
        // OUTER is intentionally NOT replaced — stable-instance is the contract.
    }
}
