// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;

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

    // --- TP-001 ---

    @Test
    void shouldEvictOnlySafelyReclaimableEntriesNeverActiveState() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LocalRateLimitRegistry registry = new LocalRateLimitRegistry(ALGORITHM, 2L, CLEANUP_INTERVAL_MS, clock);

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
                ALGORITHM, 1L, CLEANUP_INTERVAL_MS, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
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
        LocalRateLimitRegistry tight = new LocalRateLimitRegistry(ALGORITHM, 1L, CLEANUP_INTERVAL_MS, clock);
        LocalRateLimitRegistry roomy = new LocalRateLimitRegistry(ALGORITHM, 1_000L, CLEANUP_INTERVAL_MS, clock);
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
