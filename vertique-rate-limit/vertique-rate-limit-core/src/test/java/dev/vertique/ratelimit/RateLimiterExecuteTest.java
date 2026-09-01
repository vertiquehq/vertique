// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitRequestException;
import dev.vertique.ratelimit.exception.RateLimitRequestFailure;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * TP-003/TP-004: {@link RateLimiter#execute}/{@link KeyedRateLimiter#execute}'s guarded-wrapper
 * mapping and the cost-vs-capacity pre-engine validation (contracts/rate-limit-runtime.md, "Handle
 * semantics").
 */
class RateLimiterExecuteTest {

    private static final Vertx VERTX = Vertx.vertx();
    private static final String ACTION_RESULT = "action-result";

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    // --- TP-003 ---

    @Test
    void shouldEnforceT004ExecuteMappingMatrix() {
        shouldRunActionExactlyOnceWhenPermitted();
        shouldFailWithExceededExceptionWhenQuotaExceeded();
        shouldFailWithUnavailableExceptionWhenBackendFailureClosed();
        shouldRunActionExactlyOnceWhenBackendFailureOpen();
    }

    private static void shouldRunActionExactlyOnceWhenPermitted() {
        RateLimitPolicy policy = policy("permitted-quota", 2L, RateLimitFailureMode.OPEN);

        assertOnUntypedHandle(policy, RateLimitersUnitFixtures.withPolicies(VERTX, policy), "row-key", result -> {
            assertThat(result.invocations())
                    .as("PERMITTED (untyped) invocation count")
                    .isEqualTo(1);
            assertThat(result.future().succeeded())
                    .as("PERMITTED (untyped) future succeeded")
                    .isTrue();
            assertThat(result.future().result())
                    .as("PERMITTED (untyped) action result")
                    .isEqualTo(ACTION_RESULT);
        });
        assertOnKeyedHandle(policy, RateLimitersUnitFixtures.withPolicies(VERTX, policy), "row-key", result -> {
            assertThat(result.invocations())
                    .as("PERMITTED (keyed) invocation count")
                    .isEqualTo(1);
            assertThat(result.future().succeeded())
                    .as("PERMITTED (keyed) future succeeded")
                    .isTrue();
            assertThat(result.future().result())
                    .as("PERMITTED (keyed) action result")
                    .isEqualTo(ACTION_RESULT);
        });
    }

    private static void shouldFailWithExceededExceptionWhenQuotaExceeded() {
        RateLimitPolicy policy = policy("exceeded-quota", 1L, RateLimitFailureMode.OPEN);

        assertOnUntypedHandle(policy, preExhaustedUntyped(policy), "row-key", result -> {
            assertThat(result.invocations())
                    .as("QUOTA_EXCEEDED (untyped) invocation count")
                    .isEqualTo(0);
            assertThat(result.future().failed())
                    .as("QUOTA_EXCEEDED (untyped) future failed")
                    .isTrue();
            assertThat(result.future().cause())
                    .as("QUOTA_EXCEEDED (untyped) exception type")
                    .isInstanceOf(RateLimitExceededException.class);
        });
        assertOnKeyedHandle(policy, preExhaustedKeyed(policy, "row-key"), "row-key", result -> {
            assertThat(result.invocations())
                    .as("QUOTA_EXCEEDED (keyed) invocation count")
                    .isEqualTo(0);
            assertThat(result.future().failed())
                    .as("QUOTA_EXCEEDED (keyed) future failed")
                    .isTrue();
            assertThat(result.future().cause())
                    .as("QUOTA_EXCEEDED (keyed) exception type")
                    .isInstanceOf(RateLimitExceededException.class);
        });
    }

    private static void shouldFailWithUnavailableExceptionWhenBackendFailureClosed() {
        RateLimitPolicy policy = policy("closed-quota", 10L, RateLimitFailureMode.CLOSED);

        assertOnUntypedHandle(
                policy,
                RateLimitersUnitFixtures.withBackend(VERTX, CAPACITY_EXHAUSTED_BACKEND, policy),
                "row-key",
                result -> {
                    assertThat(result.invocations())
                            .as("BACKEND_FAILURE_CLOSED (untyped) invocation count")
                            .isEqualTo(0);
                    assertThat(result.future().failed())
                            .as("BACKEND_FAILURE_CLOSED (untyped) future failed")
                            .isTrue();
                    assertThat(result.future().cause())
                            .as("BACKEND_FAILURE_CLOSED (untyped) exception type")
                            .isInstanceOf(RateLimitUnavailableException.class);
                });
        assertOnKeyedHandle(
                policy,
                RateLimitersUnitFixtures.withBackend(VERTX, CAPACITY_EXHAUSTED_BACKEND, policy),
                "row-key",
                result -> {
                    assertThat(result.invocations())
                            .as("BACKEND_FAILURE_CLOSED (keyed) invocation count")
                            .isEqualTo(0);
                    assertThat(result.future().failed())
                            .as("BACKEND_FAILURE_CLOSED (keyed) future failed")
                            .isTrue();
                    assertThat(result.future().cause())
                            .as("BACKEND_FAILURE_CLOSED (keyed) exception type")
                            .isInstanceOf(RateLimitUnavailableException.class);
                });
    }

    private static void shouldRunActionExactlyOnceWhenBackendFailureOpen() {
        RateLimitPolicy policy = policy("open-quota", 10L, RateLimitFailureMode.OPEN);

        assertOnUntypedHandle(
                policy,
                RateLimitersUnitFixtures.withBackend(VERTX, CAPACITY_EXHAUSTED_BACKEND, policy),
                "row-key",
                result -> {
                    assertThat(result.invocations())
                            .as("BACKEND_FAILURE_OPEN (untyped) invocation count")
                            .isEqualTo(1);
                    assertThat(result.future().succeeded())
                            .as("BACKEND_FAILURE_OPEN (untyped) future succeeded")
                            .isTrue();
                    assertThat(result.future().result())
                            .as("BACKEND_FAILURE_OPEN (untyped) action result")
                            .isEqualTo(ACTION_RESULT);
                });
        assertOnKeyedHandle(
                policy,
                RateLimitersUnitFixtures.withBackend(VERTX, CAPACITY_EXHAUSTED_BACKEND, policy),
                "row-key",
                result -> {
                    assertThat(result.invocations())
                            .as("BACKEND_FAILURE_OPEN (keyed) invocation count")
                            .isEqualTo(1);
                    assertThat(result.future().succeeded())
                            .as("BACKEND_FAILURE_OPEN (keyed) future succeeded")
                            .isTrue();
                    assertThat(result.future().result())
                            .as("BACKEND_FAILURE_OPEN (keyed) action result")
                            .isEqualTo(ACTION_RESULT);
                });
    }

    // --- TP-004 ---

    @Test
    void shouldFailCostAboveCapacityBeforeEngineOnBothAcquireAndExecute() {
        RateLimitPolicy policy = policy("cost-check-quota", 10L, RateLimitFailureMode.OPEN);
        long cost = 11L;

        RateLimiters forAcquire = RateLimitersUnitFixtures.withBackend(VERTX, UNREACHABLE_BACKEND, policy);
        Future<RateLimitDecision> acquireResult =
                forAcquire.limiter(policy.name()).acquire(RateLimitKey.of("row-key"), cost);
        assertThat(acquireResult.failed())
                .as("acquire(cost > capacity) future failed")
                .isTrue();
        assertThat(acquireResult.cause())
                .as("acquire(cost > capacity) exception type")
                .isInstanceOf(RateLimitRequestException.class);
        assertThat(((RateLimitRequestException) acquireResult.cause()).reason())
                .as("acquire(cost > capacity) reason")
                .isEqualTo(RateLimitRequestFailure.COST_EXCEEDS_CAPACITY);

        AtomicInteger invocations = new AtomicInteger();
        RateLimiters forExecute = RateLimitersUnitFixtures.withBackend(VERTX, UNREACHABLE_BACKEND, policy);
        Future<String> executeResult = forExecute
                .limiter(policy.name())
                .execute(RateLimitKey.of("row-key"), cost, () -> {
                    invocations.incrementAndGet();
                    return Future.succeededFuture(ACTION_RESULT);
                });
        assertThat(executeResult.failed())
                .as("execute(cost > capacity) future failed")
                .isTrue();
        assertThat(executeResult.cause())
                .as("execute(cost > capacity) exception type")
                .isInstanceOf(RateLimitRequestException.class);
        assertThat(((RateLimitRequestException) executeResult.cause()).reason())
                .as("execute(cost > capacity) reason")
                .isEqualTo(RateLimitRequestFailure.COST_EXCEEDS_CAPACITY);
        assertThat(invocations.get())
                .as("execute(cost > capacity) action invocation count")
                .isEqualTo(0);
    }

    // --- Shared fixtures ---

    private static final RateLimitBackend CAPACITY_EXHAUSTED_BACKEND =
            request -> Future.succeededFuture(new RateLimitBackendResult(
                    false,
                    0L,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(RateLimitFailureCode.CAPACITY_EXHAUSTED)));

    private static final RateLimitBackend UNREACHABLE_BACKEND = request -> {
        throw new AssertionError("backend must not be reached for a cost above capacity");
    };

    private static RateLimitPolicy policy(String name, long capacity, RateLimitFailureMode failureMode) {
        return new RateLimitPolicy(
                name,
                true,
                RateLimitMode.LOCAL,
                failureMode,
                "r1",
                1L,
                new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(capacity, Duration.ofSeconds(60))));
    }

    /** Consumes this policy's entire capacity via the untyped handle before the row's decisive call. */
    private static RateLimiters preExhaustedUntyped(RateLimitPolicy policy) {
        RateLimiters rateLimiters = RateLimitersUnitFixtures.withPolicies(VERTX, policy);
        Future<RateLimitDecision> exhausting =
                rateLimiters.limiter(policy.name()).acquire(RateLimitKey.of("row-key"));
        assertThat(exhausting.result().outcome())
                .as("pre-exhaustion consumed the only token")
                .isEqualTo(RateLimitOutcome.PERMITTED);
        return rateLimiters;
    }

    /** Consumes this policy's entire capacity via the keyed handle before the row's decisive call. */
    private static RateLimiters preExhaustedKeyed(RateLimitPolicy policy, String key) {
        RateLimiters rateLimiters = RateLimitersUnitFixtures.withPolicies(VERTX, policy);
        Future<RateLimitDecision> exhausting = rateLimiters
                .limiter(policy.name(), (String k) -> RateLimitKey.of(k))
                .acquire(key);
        assertThat(exhausting.result().outcome())
                .as("pre-exhaustion consumed the only token")
                .isEqualTo(RateLimitOutcome.PERMITTED);
        return rateLimiters;
    }

    private static void assertOnUntypedHandle(
            RateLimitPolicy policy,
            RateLimiters rateLimiters,
            String key,
            java.util.function.Consumer<ExecuteResult> assertion) {
        AtomicInteger invocations = new AtomicInteger();
        Future<String> future = rateLimiters.limiter(policy.name()).execute(RateLimitKey.of(key), () -> {
            invocations.incrementAndGet();
            return Future.succeededFuture(ACTION_RESULT);
        });
        assertion.accept(new ExecuteResult(future, invocations.get()));
    }

    private static void assertOnKeyedHandle(
            RateLimitPolicy policy,
            RateLimiters rateLimiters,
            String input,
            java.util.function.Consumer<ExecuteResult> assertion) {
        AtomicInteger invocations = new AtomicInteger();
        Future<String> future = rateLimiters
                .limiter(policy.name(), (String k) -> RateLimitKey.of(k))
                .execute(input, () -> {
                    invocations.incrementAndGet();
                    return Future.succeededFuture(ACTION_RESULT);
                });
        assertion.accept(new ExecuteResult(future, invocations.get()));
    }

    /** One row's outcome: the guarded future plus the action's observed invocation count. */
    private record ExecuteResult(Future<String> future, int invocations) {}
}
