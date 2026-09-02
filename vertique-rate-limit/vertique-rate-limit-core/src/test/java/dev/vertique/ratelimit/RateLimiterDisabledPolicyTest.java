// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 1: {@link RateLimiter#acquire(RateLimitKey, long)} only ever
 * checked the {@code rateLimit.enabled} root kill switch — it never checked this handle's own
 * bound {@link RateLimitPolicy#enabled()}, so a policy explicitly declared {@code enabled=false}
 * still consumed/enforced against a bound backend exactly like an enabled one. Mirrors the
 * existing {@code rateLimit.enabled} kill-switch path (M2 review repair, {@link
 * RateLimitCoreModuleEnabledWiringTest}): a disabled policy must yield {@code DISABLED} (decision
 * -first, {@code permitted()==true}), never touch the bound backend, and still emit exactly one
 * {@link dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted} event.
 */
class RateLimiterDisabledPolicyTest {

    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    private static final RateLimitBackend UNREACHABLE_BACKEND = request -> {
        throw new AssertionError("a disabled policy must never reach the bound backend");
    };

    @Test
    void shouldYieldDisabledDecisionWithoutTouchingTheBackendWhenPolicyItselfIsDisabled() {
        RateLimitPolicy disabledPolicy = new RateLimitPolicy(
                "policy-disabled-quota",
                false,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
        RecordingObserver observer = new RecordingObserver();
        RateLimiters rateLimiters =
                RateLimitersUnitFixtures.withBackend(VERTX, UNREACHABLE_BACKEND, Set.of(observer), disabledPolicy);

        Future<RateLimitDecision> future =
                rateLimiters.limiter(disabledPolicy.name()).acquire(RateLimitKey.of("row-key"));

        assertThat(future.succeeded())
                .as("acquire() future succeeds (decision-first) for a policy-level disabled policy")
                .isTrue();
        RateLimitDecision decision = future.result();
        assertThat(decision.outcome())
                .as("outcome is DISABLED when the policy itself is disabled, even with rateLimit.enabled=true")
                .isEqualTo(RateLimitOutcome.DISABLED);
        assertThat(decision.permitted()).as("a DISABLED decision is permitted").isTrue();
        assertThat(observer.received())
                .as("an event is still emitted for a policy-level disabled decision, mirroring the root kill switch")
                .hasSize(1);
    }

    /**
     * External deep-review finding 1, gap (a) residual: {@code RateLimiters#newLimiter}
     * unconditionally resolved {@code backends.get(policy.mode())} and threw {@link
     * IllegalStateException} when unbound — even for a disabled policy, whose {@link
     * RateLimiter#acquire} never touches a backend at all. A disabled {@code CLUSTERED} policy in a
     * LOCAL-only deployment (no Redis module installed, {@code backends} has no {@code CLUSTERED}
     * entry) must still resolve its handle and yield {@code DISABLED}, mirroring {@code
     * validateBackendCoverage}'s own {@code policy.enabled() &&} exemption
     * (contracts/rate-limit-runtime.md, "Backend seam").
     */
    @Test
    void shouldTreatDisabledPolicyAsBackendIndependentDisabledDecision() {
        RateLimitPolicy disabledClusteredPolicy = new RateLimitPolicy(
                "policy-disabled-clustered-no-backend",
                false,
                RateLimitMode.CLUSTERED,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
        RateLimiters rateLimiters = new RateLimiters(
                Set.of(disabledClusteredPolicy), Map.of(), null, VERTX, Set.of(), ANONYMOUS_SUBJECT_RESOLVER, true);

        assertThatCode(() -> rateLimiters.limiter(disabledClusteredPolicy.name()))
                .as("a disabled policy's handle resolves even when no backend is bound for its mode")
                .doesNotThrowAnyException();

        Future<RateLimitDecision> future =
                rateLimiters.limiter(disabledClusteredPolicy.name()).acquire(RateLimitKey.of("row-key"));

        assertThat(future.succeeded())
                .as("acquire() succeeds without ever constructing, requiring, or invoking a RateLimitBackend")
                .isTrue();
        assertThat(future.result().outcome())
                .as("outcome is DISABLED for a disabled, backend-less policy")
                .isEqualTo(RateLimitOutcome.DISABLED);
    }

    /** Always reports no identity present; these fixtures never exercise subject resolution. */
    private static final dev.vertique.ratelimit.spi.RateLimitSubjectResolver ANONYMOUS_SUBJECT_RESOLVER =
            Optional::empty;

    /** Records every {@link RateLimitEvent} it receives; never throws. */
    private static final class RecordingObserver implements RateLimitObserver {
        private final List<RateLimitEvent> received = new ArrayList<>();

        @Override
        public void onEvent(RateLimitEvent event) {
            received.add(event);
        }

        List<RateLimitEvent> received() {
            return received;
        }
    }
}
