// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T007 proof for leaf-wise resilience override layering and precedence boundaries. */
@DisplayName("ResiliencePolicyOverrides")
class ResiliencePolicyOverridesTest {

    @Test
    @DisplayName("preserves exact optional identity for empty and single-sided concerns")
    void preservesExactOptionalIdentityForEmptyAndSingleSidedConcerns() {
        Optional<TimeoutOverride> lowerTimeout = Optional.of(new TimeoutOverride(Optional.empty(), OptionalLong.of(500L)));
        ResiliencePolicyOverrides lower = overrides(lowerTimeout, Optional.empty(), Optional.empty(), Optional.empty());
        ResiliencePolicyOverrides higher = ResiliencePolicyOverrides.none();

        ResiliencePolicyOverrides lowerResult = higher.over(lower);
        assertSame(lowerTimeout, lowerResult.timeout());

        Optional<TimeoutOverride> higherTimeout =
                Optional.of(new TimeoutOverride(Optional.of(true), OptionalLong.empty()));
        ResiliencePolicyOverrides higherOnly = overrides(higherTimeout, Optional.empty(), Optional.empty(), Optional.empty());
        ResiliencePolicyOverrides higherResult = higherOnly.over(ResiliencePolicyOverrides.none());
        assertSame(higherTimeout, higherResult.timeout());
    }

    @Test
    @DisplayName("merges timeout circuit breaker and bulkhead leaves independently")
    void mergesTimeoutCircuitBreakerAndBulkheadLeavesIndependently() {
        TimeoutOverride highTimeout = new TimeoutOverride(Optional.empty(), OptionalLong.of(1_500L));
        TimeoutOverride lowTimeout = new TimeoutOverride(Optional.of(true), OptionalLong.of(500L));
        CircuitBreakerOverride highCircuit =
                new CircuitBreakerOverride(Optional.empty(), OptionalInt.of(3), OptionalLong.empty());
        CircuitBreakerOverride lowCircuit =
                new CircuitBreakerOverride(Optional.of(true), OptionalInt.empty(), OptionalLong.of(9_000L));
        BulkheadConfig highBulkheadConfig = BulkheadConfig.reject(4);
        BulkheadConfig lowBulkheadConfig = BulkheadConfig.queue(2, 3, Duration.ofSeconds(2));
        BulkheadOverride highBulkhead = new BulkheadOverride(Optional.empty(), Optional.of(highBulkheadConfig));
        BulkheadOverride lowBulkhead = new BulkheadOverride(Optional.of(true), Optional.of(lowBulkheadConfig));

        ResiliencePolicyOverrides merged = overrides(
                        Optional.of(highTimeout),
                        Optional.empty(),
                        Optional.of(highCircuit),
                        Optional.of(highBulkhead))
                .over(overrides(
                        Optional.of(lowTimeout),
                        Optional.empty(),
                        Optional.of(lowCircuit),
                        Optional.of(lowBulkhead)));

        TimeoutOverride timeout = merged.timeout().orElseThrow();
        CircuitBreakerOverride circuit = merged.circuitBreaker().orElseThrow();
        BulkheadOverride bulkhead = merged.bulkhead().orElseThrow();
        assertAll(
                () -> assertEquals(Optional.of(true), timeout.enabled()),
                () -> assertEquals(OptionalLong.of(1_500L), timeout.timeoutMs()),
                () -> assertEquals(Optional.of(true), circuit.enabled()),
                () -> assertEquals(OptionalInt.of(3), circuit.maxFailures()),
                () -> assertEquals(OptionalLong.of(9_000L), circuit.resetTimeoutMs()),
                () -> assertEquals(Optional.of(true), bulkhead.enabled()),
                () -> assertSame(highBulkheadConfig, bulkhead.config().orElseThrow()));
    }

    @Test
    @DisplayName("merges retry siblings and every scalar backoff leaf")
    void mergesRetrySiblingsAndEveryScalarBackoffLeaf() {
        Set<Class<? extends Throwable>> higherRetryOn = Set.of(IllegalStateException.class);
        Set<Class<? extends Throwable>> lowerAbortOn = Set.of(IllegalArgumentException.class);
        RetryPolicy higherFallback = (error, retryCount) -> true;
        RetryPolicy lowerFallback = (error, retryCount) -> false;
        BackoffOverride higherBackoff = new BackoffOverride(
                Optional.empty(),
                OptionalLong.of(100L),
                Optional.empty(),
                OptionalLong.of(4_000L),
                OptionalLong.empty());
        BackoffOverride lowerBackoff = new BackoffOverride(
                Optional.empty(),
                OptionalLong.of(50L),
                Optional.of(2.0d),
                OptionalLong.of(3_000L),
                OptionalLong.of(700L));
        RetryOverride higherRetry = new RetryOverride(
                Optional.empty(),
                OptionalInt.of(7),
                Optional.of(higherBackoff),
                Optional.of(higherRetryOn),
                Optional.empty(),
                Optional.of(higherFallback));
        RetryOverride lowerRetry = new RetryOverride(
                Optional.of(true),
                OptionalInt.of(5),
                Optional.of(lowerBackoff),
                Optional.empty(),
                Optional.of(lowerAbortOn),
                Optional.of(lowerFallback));

        RetryOverride merged = overrides(Optional.empty(), Optional.of(higherRetry), Optional.empty(), Optional.empty())
                .over(overrides(Optional.empty(), Optional.of(lowerRetry), Optional.empty(), Optional.empty()))
                .retry()
                .orElseThrow();
        BackoffOverride backoff = merged.backoff().orElseThrow();
        assertAll(
                () -> assertEquals(Optional.of(true), merged.enabled()),
                () -> assertEquals(OptionalInt.of(7), merged.maxRetries()),
                () -> assertEquals(higherRetryOn, merged.retryOn().orElseThrow()),
                () -> assertEquals(lowerAbortOn, merged.abortOn().orElseThrow()),
                () -> assertSame(higherFallback, merged.fallbackPolicy().orElseThrow()),
                () -> assertEquals(OptionalLong.of(100L), backoff.initialDelayMs()),
                () -> assertEquals(Optional.of(2.0d), backoff.multiplier()),
                () -> assertEquals(OptionalLong.of(4_000L), backoff.maxDelayMs()),
                () -> assertEquals(OptionalLong.of(700L), backoff.maxJitterMs()));

        RetryOverride higherSiblingWithLowerDisabled = new RetryOverride(
                Optional.empty(), OptionalInt.of(2), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        RetryOverride disabledLower = new RetryOverride(
                Optional.of(false), OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(
                Optional.empty(),
                overrides(Optional.empty(), Optional.of(higherSiblingWithLowerDisabled), Optional.empty(), Optional.empty())
                        .over(overrides(Optional.empty(), Optional.of(disabledLower), Optional.empty(), Optional.empty()))
                        .retry()
                        .orElseThrow()
                        .enabled());
    }

    @Test
    @DisplayName("higher disabled concerns win as complete records")
    void higherDisabledConcernsWinAsCompleteRecords() {
        Optional<TimeoutOverride> higherTimeout = Optional.of(new TimeoutOverride(Optional.of(false), OptionalLong.empty()));
        Optional<RetryOverride> higherRetry = Optional.of(new RetryOverride(
                Optional.of(false), OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        Optional<CircuitBreakerOverride> higherCircuit =
                Optional.of(new CircuitBreakerOverride(Optional.of(false), OptionalInt.empty(), OptionalLong.empty()));
        Optional<BulkheadOverride> higherBulkhead = Optional.of(new BulkheadOverride(Optional.of(false), Optional.empty()));
        ResiliencePolicyOverrides higher = overrides(higherTimeout, higherRetry, higherCircuit, higherBulkhead);
        ResiliencePolicyOverrides lower = overrides(
                Optional.of(new TimeoutOverride(Optional.of(true), OptionalLong.of(2_000L))),
                Optional.of(new RetryOverride(
                        Optional.of(true), OptionalInt.of(4), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())),
                Optional.of(new CircuitBreakerOverride(Optional.of(true), OptionalInt.of(8), OptionalLong.of(12_000L))),
                Optional.of(new BulkheadOverride(Optional.of(true), Optional.of(BulkheadConfig.reject(8)))));

        ResiliencePolicyOverrides merged = higher.over(lower);
        assertAll(
                () -> assertSame(higherTimeout, merged.timeout()),
                () -> assertSame(higherRetry, merged.retry()),
                () -> assertSame(higherCircuit, merged.circuitBreaker()),
                () -> assertSame(higherBulkhead, merged.bulkhead()));
    }

    @Test
    @DisplayName("higher custom backoff wins without lower scalar or custom fields")
    void higherCustomBackoffWinsWithoutLowerScalarOrCustomFields() {
        BackoffStrategy higherStrategy = retryCount -> 11L;
        BackoffStrategy lowerStrategy = retryCount -> 22L;
        BackoffOverride higherCustom = new BackoffOverride(
                Optional.of(higherStrategy), OptionalLong.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
        BackoffOverride lowerCustom = new BackoffOverride(
                Optional.of(lowerStrategy), OptionalLong.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
        BackoffOverride higherScalar = new BackoffOverride(
                Optional.empty(), OptionalLong.of(100L), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());

        Optional<BackoffOverride> customResult = mergeBackoffs(higherCustom, lowerCustom);
        Optional<BackoffOverride> scalarResult = mergeBackoffs(higherScalar, lowerCustom);
        assertAll(
                () -> assertSame(higherCustom, customResult.orElseThrow()),
                () -> assertSame(higherStrategy, customResult.orElseThrow().custom().orElseThrow()),
                () -> assertSame(higherScalar, scalarResult.orElseThrow()),
                () -> assertEquals(OptionalLong.of(100L), scalarResult.orElseThrow().initialDelayMs()),
                () -> assertEquals(Optional.empty(), scalarResult.orElseThrow().custom()));
    }

    private static Optional<BackoffOverride> mergeBackoffs(BackoffOverride higher, BackoffOverride lower) {
        RetryOverride higherRetry = new RetryOverride(
                Optional.empty(), OptionalInt.empty(), Optional.of(higher), Optional.empty(), Optional.empty(), Optional.empty());
        RetryOverride lowerRetry = new RetryOverride(
                Optional.empty(), OptionalInt.empty(), Optional.of(lower), Optional.empty(), Optional.empty(), Optional.empty());
        return overrides(Optional.empty(), Optional.of(higherRetry), Optional.empty(), Optional.empty())
                .over(overrides(Optional.empty(), Optional.of(lowerRetry), Optional.empty(), Optional.empty()))
                .retry()
                .orElseThrow()
                .backoff();
    }

    private static ResiliencePolicyOverrides overrides(
            Optional<TimeoutOverride> timeout,
            Optional<RetryOverride> retry,
            Optional<CircuitBreakerOverride> circuitBreaker,
            Optional<BulkheadOverride> bulkhead) {
        return new ResiliencePolicyOverrides(timeout, retry, circuitBreaker, bulkhead);
    }
}
