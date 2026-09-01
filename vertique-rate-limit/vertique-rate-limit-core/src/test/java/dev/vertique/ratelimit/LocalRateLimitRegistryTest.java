// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * TP-001/TP-002/TP-006: {@link LocalRateLimitRegistry}'s bounded eviction, {@code
 * CAPACITY_EXHAUSTED} classification through policy {@code failureMode}, and per-policy budget
 * isolation (contracts/rate-limit-runtime.md, "Local engine contract", R2 amendment).
 */
class LocalRateLimitRegistryTest {

    private static final TokenBucketRateLimit ALGORITHM =
            new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofMillis(1_000L)));

    /** Worst-case time-to-full for {@link #ALGORITHM} = capacity(10) * periodMs(1000) / tokens(10) = 1000ms. */
    private static final long WORST_CASE_TIME_TO_FULL_MS = 1_000L;

    private static final long CLEANUP_INTERVAL_MS = 500L;

    /** {@link #WORST_CASE_TIME_TO_FULL_MS} + {@link #CLEANUP_INTERVAL_MS} (this registry's retention slack). */
    private static final long SAFE_RECLAIM_THRESHOLD_MS = WORST_CASE_TIME_TO_FULL_MS + CLEANUP_INTERVAL_MS;

    private Logger registryLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureRegistryLogs() {
        registryLogger = (Logger) LoggerFactory.getLogger(LocalRateLimitRegistry.class);
        previousLevel = registryLogger.getLevel();
        registryLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
    }

    @AfterEach
    void releaseRegistryLogs() {
        registryLogger.detachAppender(appender);
        appender.stop();
        registryLogger.setLevel(previousLevel);
    }

    // --- TP-001 ---

    @Test
    void shouldEvictOnlySafelyReclaimableEntriesNeverActiveState() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LocalRateLimitRegistry registry =
                new LocalRateLimitRegistry("registry-test-policy", ALGORITHM, 2L, CLEANUP_INTERVAL_MS, clock);

        // "idle": created now, then the clock advances past its safe-reclaim threshold with no
        // further access — safely reclaimable.
        registry.consume("idle", 1L);
        clock.advanceTo(Instant.EPOCH.plusMillis(SAFE_RECLAIM_THRESHOLD_MS + 100L));

        // "active": created at the advanced clock, partially consumed — freshly touched, not
        // reclaimable.
        RateLimitBackendResult activeFirst = registry.consume("active", 3L);
        assertThat(activeFirst.consumed()).as("active bucket's own creation").isTrue();

        registry.sweep();

        RateLimitBackendResult fresh = registry.consume("fresh", 1L);
        assertThat(fresh.consumed())
                .as("'fresh' admission reuses the slot the sweep reclaimed from 'idle'")
                .isTrue();

        RateLimitBackendResult activeAfter = registry.consume("active", 1L);
        assertThat(activeAfter.consumed())
                .as("'active' still tracked after the sweep")
                .isTrue();
        assertThat(activeAfter.remaining())
                .as("'active' bucket state carried through the sweep unchanged (7 remaining minus this 1 cost)")
                .isEqualTo(6L);
    }

    // --- TP-002 ---

    @Test
    void shouldClassifyCapacityExhaustedThroughPolicyFailureMode() {
        LocalRateLimitRegistry openRegistry = registryWithOneActiveEntry();
        LocalRateLimitRegistry closedRegistry = registryWithOneActiveEntry();
        RateLimitPolicy openPolicy = policy("open-quota", RateLimitFailureMode.OPEN);
        RateLimitPolicy closedPolicy = policy("closed-quota", RateLimitFailureMode.CLOSED);

        RateLimitDecision openDecision = acquireSecondKey(openPolicy, openRegistry);
        RateLimitDecision closedDecision = acquireSecondKey(closedPolicy, closedRegistry);

        assertThat(openDecision.outcome()).as("OPEN row outcome").isEqualTo(RateLimitOutcome.BACKEND_FAILURE_OPEN);
        assertThat(openDecision.permitted()).as("OPEN row permitted()").isTrue();
        assertThat(closedDecision.outcome())
                .as("CLOSED row outcome")
                .isEqualTo(RateLimitOutcome.BACKEND_FAILURE_CLOSED);
        assertThat(closedDecision.permitted()).as("CLOSED row permitted()").isFalse();
    }

    private static LocalRateLimitRegistry registryWithOneActiveEntry() {
        LocalRateLimitRegistry registry = new LocalRateLimitRegistry(
                "registry-test-policy", ALGORITHM, 1L, CLEANUP_INTERVAL_MS, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        registry.consume("existing", 1L);
        return registry;
    }

    private static RateLimitDecision acquireSecondKey(RateLimitPolicy policy, LocalRateLimitRegistry registry) {
        Vertx vertx = Vertx.vertx();
        try {
            RateLimiters rateLimiters =
                    RateLimitersUnitFixtures.withBackend(vertx, new RegistryBackedBackend(registry), policy);
            RateLimiter limiter = rateLimiters.limiter(policy.name());
            Future<RateLimitDecision> decision = limiter.acquire(RateLimitKey.of("second"));
            assertThat(decision.succeeded())
                    .as("acquire never fails the future for a backend failure")
                    .isTrue();
            return decision.result();
        } finally {
            vertx.close();
        }
    }

    // --- TP-006 ---

    @Test
    void shouldIsolateCapacityExhaustionPerPolicyRegistry() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LocalRateLimitRegistry tight =
                new LocalRateLimitRegistry("tight-policy", ALGORITHM, 1L, CLEANUP_INTERVAL_MS, clock);
        LocalRateLimitRegistry roomy =
                new LocalRateLimitRegistry("roomy-policy", ALGORITHM, 1_000L, CLEANUP_INTERVAL_MS, clock);
        tight.consume("tight-existing", 1L);

        RateLimitBackendResult tightResult = tight.consume("tight-new", 1L);
        assertThat(tightResult.consumed()).as("'tight' at its own budget").isFalse();
        assertThat(tightResult.failureCode())
                .as("'tight' classified CAPACITY_EXHAUSTED")
                .contains(RateLimitFailureCode.CAPACITY_EXHAUSTED);

        for (int i = 0; i < 10; i++) {
            RateLimitBackendResult roomyResult = roomy.consume("roomy-" + i, 1L);
            assertThat(roomyResult.consumed())
                    .as("'roomy' request %d unaffected by 'tight's exhaustion", i)
                    .isTrue();
        }
    }

    // --- T021 W2: sweep throttling ---

    /**
     * External deep-review finding 2 (sweep cost bounding): before T021 W2, every at-capacity
     * admission ran a full {@code O(maxTrackedKeys)} scan of this registry unconditionally. This
     * amortizes that cost to at most once per {@code cleanupIntervalMs}: a second at-capacity
     * admission that lands within the interval of the previous sweep must skip the scan and return
     * {@code CAPACITY_EXHAUSTED} immediately, <em>even when an entry would in fact be reclaimable</em>
     * if the scan ran — the throttle, not "nothing was reclaimable", is what this proves.
     */
    @Test
    void shouldSkipTheScanOnASecondAtCapacityAdmissionWithinTheCleanupInterval() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LocalRateLimitRegistry registry =
                new LocalRateLimitRegistry("registry-test-policy", ALGORITHM, 2L, CLEANUP_INTERVAL_MS, clock);

        // t=0: "old" created.
        registry.consume("old", 1L);
        // t=100: "mid" created — still under budget (2), no sweep triggered yet.
        clock.advanceTo(Instant.EPOCH.plusMillis(100L));
        registry.consume("mid", 1L);

        // t=1500: "old" (elapsed 1500 >= SAFE_RECLAIM_THRESHOLD_MS) is now reclaimable; "mid"
        // (elapsed 1400) is not. At capacity (2/2) -- this is the first-ever sweep attempt, always
        // due -- reclaims "old" and admits "new1". This primes lastSweepAtMs = 1500.
        clock.advanceTo(Instant.EPOCH.plusMillis(SAFE_RECLAIM_THRESHOLD_MS));
        RateLimitBackendResult new1 = registry.consume("new1", 1L);
        assertThat(new1.consumed())
                .as("'new1' admitted once the first-ever sweep reclaims 'old'")
                .isTrue();

        // t=1600: only 100ms after the sweep above (< CLEANUP_INTERVAL_MS=500 -- throttled). "mid"
        // (created t=100) has now itself crossed its own reclaim threshold (elapsed 1500 >= 1500) --
        // if the scan ran, it would reclaim "mid". At capacity (2/2: "mid","new1") -- the throttle
        // must skip the scan, so "new2" is rejected even though "mid" is genuinely reclaimable.
        clock.advanceTo(Instant.EPOCH.plusMillis(SAFE_RECLAIM_THRESHOLD_MS + 100L));
        RateLimitBackendResult new2 = registry.consume("new2", 1L);
        assertThat(new2.consumed())
                .as("throttled: the scan that would reclaim 'mid' must be skipped within the cleanup interval "
                        + "of the previous sweep")
                .isFalse();
        assertThat(new2.failureCode()).contains(RateLimitFailureCode.CAPACITY_EXHAUSTED);
    }

    @Test
    void shouldSweepAgainOnceTheCleanupIntervalHasElapsedSinceTheLastSweep() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LocalRateLimitRegistry registry =
                new LocalRateLimitRegistry("registry-test-policy", ALGORITHM, 2L, CLEANUP_INTERVAL_MS, clock);

        registry.consume("old", 1L);
        clock.advanceTo(Instant.EPOCH.plusMillis(100L));
        registry.consume("mid", 1L);

        clock.advanceTo(Instant.EPOCH.plusMillis(SAFE_RECLAIM_THRESHOLD_MS));
        registry.consume("new1", 1L); // primes lastSweepAtMs = 1500, reclaims "old"

        // t=2000: exactly CLEANUP_INTERVAL_MS (500) after the last sweep -- due again. "mid"
        // (created t=100, elapsed 1900) is reclaimable.
        clock.advanceTo(Instant.EPOCH.plusMillis(SAFE_RECLAIM_THRESHOLD_MS + CLEANUP_INTERVAL_MS));
        RateLimitBackendResult new2 = registry.consume("new2", 1L);
        assertThat(new2.consumed())
                .as("once the cleanup interval has elapsed since the last sweep, the next at-capacity "
                        + "admission sweeps again and reclaims 'mid'")
                .isTrue();
    }

    // --- T021 W3: saturation observability ---

    /**
     * External deep-review finding 3: key-space saturation ({@code CAPACITY_EXHAUSTED}) previously
     * had no log signal anywhere. This proves exactly one {@code WARN} per saturation episode — not
     * one per rejected admission — and that a distinct later episode (after the registry has
     * recovered room and saturates again) warns again.
     */
    @Test
    void shouldWarnOncePerSaturationEpisodeAndAgainAfterADistinctLaterEpisode() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LocalRateLimitRegistry registry =
                new LocalRateLimitRegistry("warn-once-policy", ALGORITHM, 1L, CLEANUP_INTERVAL_MS, clock);
        registry.consume("existing", 1L);

        RateLimitBackendResult firstRejection = registry.consume("rejected-1", 1L);
        RateLimitBackendResult secondRejection = registry.consume("rejected-2", 1L);
        assertThat(firstRejection.consumed()).isFalse();
        assertThat(secondRejection.consumed()).isFalse();

        List<ILoggingEvent> warnEventsAfterFirstEpisode = warnEvents();
        assertThat(warnEventsAfterFirstEpisode)
                .as("two CAPACITY_EXHAUSTED admissions in the same episode must log exactly one WARN")
                .hasSize(1);
        String message = warnEventsAfterFirstEpisode.get(0).getFormattedMessage();
        assertThat(message).as("the WARN must name the saturated policy").contains("warn-once-policy");
        assertThat(message).as("the WARN must report the configured budget").contains("1");

        // Recover: advance past the safe-reclaim threshold and admit a new key -- "existing" is
        // reclaimed and the episode ends (saturationWarned cleared).
        clock.advanceTo(Instant.EPOCH.plusMillis(SAFE_RECLAIM_THRESHOLD_MS + 100L));
        RateLimitBackendResult recovered = registry.consume("recovered", 1L);
        assertThat(recovered.consumed())
                .as("admission succeeds once 'existing' is reclaimed")
                .isTrue();

        // A distinct, later saturation episode must warn again.
        RateLimitBackendResult thirdRejection = registry.consume("rejected-3", 1L);
        assertThat(thirdRejection.consumed()).isFalse();

        assertThat(warnEvents())
                .as("a distinct later saturation episode must log its own WARN")
                .hasSize(2);
    }

    private List<ILoggingEvent> warnEvents() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
    }

    // --- Shared fixtures ---

    private static RateLimitPolicy policy(String name, RateLimitFailureMode failureMode) {
        return new RateLimitPolicy(name, true, RateLimitMode.LOCAL, failureMode, "r1", 1L, ALGORITHM);
    }

    /** Adapts one already-constructed {@link LocalRateLimitRegistry} into a {@link RateLimitBackend}. */
    private static final class RegistryBackedBackend implements RateLimitBackend {
        private final LocalRateLimitRegistry registry;

        RegistryBackedBackend(LocalRateLimitRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            return Future.succeededFuture(registry.consume(request.storageKey(), request.cost()));
        }
    }

    /** A directly settable {@link Clock}, so elapsed time is asserted without any wall-clock wait. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("this fixture is UTC-only");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
