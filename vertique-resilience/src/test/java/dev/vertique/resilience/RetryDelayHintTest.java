// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.RetryExhausted;
import dev.vertique.resilience.spi.event.RetryScheduled;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof for the opt-in retry delay hint: a failure that implements {@link RetryDelayHint}
 * lengthens the retry delay up to {@code retryAfterMaxMs}, is refused above it, is reported to
 * observers, is counted in a known execution budget, and changes nothing while the maximum is 0.
 *
 * <p>The execution scenarios run real pipelines on a real Vert.x timer, as the existing retry
 * tests do. The lower bound on an observed gap is 10 ms below the configured delay to absorb timer
 * granularity; every configured delay is far larger than the competing sampled backoff, so the
 * bound still tells the hint and the backoff apart.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RetryDelayHintTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(8);
    private static final long TIMER_TOLERANCE_MS = 10L;
    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceObserver recorder = events::add;
    private final AtomicInteger pipelineCounter = new AtomicInteger();

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    /**
     * A hint within the maximum lengthens the delay to {@code max(sampled backoff, hint)},
     * observers see the chosen delay, and the first hint in the cause chain is the one used.
     */
    @Test
    @DisplayName("a hint within the maximum lengthens the delay and observers see it")
    void hintWithinTheMaximumLengthensTheDelayAndObserversSeeIt(Vertx vertx) throws Exception {
        // Given a retry policy of 2 retries, fixed backoff 10 ms and a hint maximum of 2000 ms
        resilience = Resilience.create(vertx, Set.of(recorder));
        RetryConfig config = retryConfig(2, RetryBackoff.fixed(10L), 2_000L);

        // When the first attempt fails with a 500 ms hint and the second succeeds
        List<Long> startedAtNanos = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        String result = await(pipeline(config).execute(() -> {
            startedAtNanos.add(System.nanoTime());
            return attempts.getAndIncrement() == 0
                    ? Future.failedFuture(new HintedFailure(Duration.ofMillis(500L)))
                    : Future.succeededFuture("recovered");
        }));

        // Then the second invocation starts at least 500 ms after the first
        assertEquals("recovered", result);
        assertEquals(2, attempts.get());
        assertTrue(
                millisBetween(startedAtNanos.get(0), startedAtNanos.get(1)) >= 500L - TIMER_TOLERANCE_MS,
                "the retry must wait for the hint, not the sampled backoff of 10 ms");
        // And the recorded RetryScheduled carries the larger delay, 500
        List<RetryScheduled> scheduled = retryScheduled();
        assertEquals(List.of(500L), delays(scheduled));
        assertEquals(1, scheduled.get(0).failedAttemptOrdinal());
        assertEquals(2, scheduled.get(0).nextAttemptOrdinal());

        // And with a hint of 5 ms the sampled backoff of 10 ms is the larger and is the recorded delay
        events.clear();
        AtomicInteger smallHintAttempts = new AtomicInteger();
        assertEquals(
                "ok",
                await(pipeline(config)
                        .execute(() -> smallHintAttempts.getAndIncrement() == 0
                                ? Future.failedFuture(new HintedFailure(Duration.ofMillis(5L)))
                                : Future.succeededFuture("ok"))));
        assertEquals(List.of(10L), delays(retryScheduled()));

        // And a failure without a hint is delayed by the sampled backoff alone
        events.clear();
        AtomicInteger plainAttempts = new AtomicInteger();
        assertEquals(
                "ok",
                await(pipeline(config)
                        .execute(() -> plainAttempts.getAndIncrement() == 0
                                ? Future.failedFuture(new IllegalStateException("no hint"))
                                : Future.succeededFuture("ok"))));
        assertEquals(List.of(10L), delays(retryScheduled()));

        // And a zero hint leaves the sampled backoff in force
        events.clear();
        AtomicInteger zeroHintAttempts = new AtomicInteger();
        assertEquals(
                "ok",
                await(pipeline(config)
                        .execute(() -> zeroHintAttempts.getAndIncrement() == 0
                                ? Future.failedFuture(new HintedFailure(Duration.ZERO))
                                : Future.succeededFuture("ok"))));
        assertEquals(List.of(10L), delays(retryScheduled()));

        // And a hint on a wrapped cause is found, and the first hint in the chain wins over a deeper one
        events.clear();
        AtomicInteger wrappedAttempts = new AtomicInteger();
        assertEquals(
                "ok",
                await(pipeline(config)
                        .execute(() -> wrappedAttempts.getAndIncrement() == 0
                                ? Future.failedFuture(new IllegalStateException(
                                        "wrapper", new HintedFailure(Duration.ofMillis(300L))))
                                : Future.succeededFuture("ok"))));
        assertEquals(List.of(300L), delays(retryScheduled()));

        events.clear();
        AtomicInteger nestedAttempts = new AtomicInteger();
        assertEquals(
                "ok",
                await(pipeline(config)
                        .execute(() -> nestedAttempts.getAndIncrement() == 0
                                ? Future.failedFuture(new HintedFailure(
                                        Duration.ofMillis(200L), new HintedFailure(Duration.ofMillis(1_500L))))
                                : Future.succeededFuture("ok"))));
        assertEquals(List.of(200L), delays(retryScheduled()), "the outer hint comes first in the cause chain");

        // And a hint equal to the maximum is within it and is honored
        events.clear();
        RetryConfig boundary = retryConfig(1, RetryBackoff.fixed(10L), 300L);
        AtomicInteger boundaryAttempts = new AtomicInteger();
        assertEquals(
                "ok",
                await(pipeline(boundary)
                        .execute(() -> boundaryAttempts.getAndIncrement() == 0
                                ? Future.failedFuture(new HintedFailure(Duration.ofMillis(300L)))
                                : Future.succeededFuture("ok"))));
        assertEquals(List.of(300L), delays(retryScheduled()));

        // And a negative sampled backoff still stops the retry even when a hint is within the maximum
        events.clear();
        RetryConfig negativeBackoff = retryConfig(2, RetryBackoff.custom(retryCount -> -1L), 2_000L);
        HintedFailure original = new HintedFailure(Duration.ofMillis(100L));
        AtomicInteger negativeAttempts = new AtomicInteger();
        ExecutionException stopped = assertThrows(
                ExecutionException.class,
                () -> await(pipeline(negativeBackoff).execute(() -> {
                    negativeAttempts.incrementAndGet();
                    return Future.failedFuture(original);
                })));
        assertSame(original, stopped.getCause());
        assertEquals(1, negativeAttempts.get());
        assertTrue(retryScheduled().isEmpty());
    }

    /**
     * With the maximum left at 0 the hint is ignored, delays and the known budget are the
     * ones recorded before the change, and the previous constructors still resolve the same policy.
     */
    @Test
    @DisplayName("a maximum of 0 leaves delays and budgets unchanged")
    void maximumZeroLeavesDelaysAndBudgetsUnchanged(Vertx vertx) throws Exception {
        // Given fixed backoff 100 ms, 2 retries, a 1000 ms timeout and the default retryAfterMaxMs
        resilience = Resilience.create(vertx, Set.of(recorder));
        RetryConfig config = RetryConfig.builder()
                .maxRetries(2)
                .backoff(RetryBackoff.fixed(100L))
                .build();
        assertEquals(0L, config.retryAfterMaxMs(), "the default maximum is 0, hints off");
        ResolvedResiliencePolicy policy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(1_000L)), Optional.of(config), Optional.empty(), Optional.empty());

        // When a supplier fails twice with a 5000 ms hint and then succeeds
        List<Long> startedAtNanos = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        String result =
                await(resilience.adapterSupport().pipeline(identity(), policy).execute(() -> {
                    startedAtNanos.add(System.nanoTime());
                    return attempts.getAndIncrement() < 2
                            ? Future.failedFuture(new HintedFailure(Duration.ofMillis(5_000L)))
                            : Future.succeededFuture("recovered");
                }));

        // Then both retries are delayed by 100 ms and the hint is ignored
        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
        assertEquals(List.of(100L, 100L), delays(retryScheduled()));
        assertTrue(millisBetween(startedAtNanos.get(0), startedAtNanos.get(1)) >= 100L - TIMER_TOLERANCE_MS);
        assertTrue(millisBetween(startedAtNanos.get(1), startedAtNanos.get(2)) >= 100L - TIMER_TOLERANCE_MS);
        assertTrue(
                millisBetween(startedAtNanos.get(0), startedAtNanos.get(2)) < 4_000L,
                "a honored 5000 ms hint would have delayed the retries far past this bound");

        // And the budget is Known 3200 ms: 3 attempts of 1000 plus 2 retries of 100
        DurationBound.Known budget = assertInstanceOf(
                DurationBound.Known.class, policy.executionBudget().activeExecution());
        assertEquals(3_200L, budget.valueMs());

        // And the previous RetryOverride and RetryPolicyConfig constructors still compile and resolve the same
        RetryOverride previousArity = new RetryOverride(
                Optional.empty(),
                OptionalInt.of(2),
                Optional.of(fixedLikeBackoff(100L)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        RetryConfig resolved = resolve(Optional.of(previousArity), ResilienceDefaults.none());
        assertEquals(2, resolved.maxRetries());
        assertEquals(0L, resolved.retryAfterMaxMs());
        assertEquals(
                3_200L,
                assertInstanceOf(
                                DurationBound.Known.class,
                                new ResolvedResiliencePolicy(
                                                Optional.of(TimeoutConfig.ofMillis(1_000L)),
                                                Optional.of(resolved),
                                                Optional.empty(),
                                                Optional.empty())
                                        .executionBudget()
                                        .activeExecution())
                        .valueMs());
        assertNull(new RetryPolicyConfig(2, 100L, 1.0d, 100L).retryAfterMaxMs());
    }

    /**
     * A hint above the maximum refuses the retry, surfaces the original failure after one
     * invocation with no wait, and emits {@code RetryExhausted} without {@code RetryScheduled}. A
     * hint accessor that throws or returns nothing usable counts as no hint.
     */
    @Test
    @DisplayName("a hint above the maximum refuses the retry and emits RetryExhausted")
    void hintAboveTheMaximumRefusesTheRetryAndEmitsRetryExhausted(Vertx vertx) throws Exception {
        // Given 2 retries, fixed backoff 10 ms, a maximum of 1000 ms and a failure hinting 30 s
        resilience = Resilience.create(vertx, Set.of(recorder));
        RetryConfig config = retryConfig(2, RetryBackoff.fixed(10L), 1_000L);
        HintedFailure original = new HintedFailure(Duration.ofSeconds(30L));
        AtomicInteger attempts = new AtomicInteger();

        // When the supplier runs through the pipeline
        long startedAt = System.nanoTime();
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> await(pipeline(config).execute(() -> {
                    attempts.incrementAndGet();
                    return Future.failedFuture(original);
                })));

        // Then the future fails with the original exception after exactly 1 invocation and no wait
        assertSame(original, failure.getCause());
        assertEquals(1, attempts.get());
        assertTrue(millisBetween(startedAt, System.nanoTime()) < 5_000L, "the 30 s hint must not be waited out");
        // And the observer recorded one RetryExhausted and no RetryScheduled
        assertTrue(retryScheduled().isEmpty());
        List<RetryExhausted> exhausted = events.stream()
                .filter(RetryExhausted.class::isInstance)
                .map(RetryExhausted.class::cast)
                .toList();
        assertEquals(1, exhausted.size());
        assertEquals(1, exhausted.get(0).attemptsMade());

        // And a hint of 1001 ms, just above the maximum, is refused the same way
        events.clear();
        AtomicInteger justAbove = new AtomicInteger();
        assertThrows(
                ExecutionException.class,
                () -> await(pipeline(config).execute(() -> {
                    justAbove.incrementAndGet();
                    return Future.failedFuture(new HintedFailure(Duration.ofMillis(1_001L)));
                })));
        assertEquals(1, justAbove.get());
        assertTrue(retryScheduled().isEmpty());

        // And a hint never makes an ineligible failure retryable
        events.clear();
        RetryConfig abortOn = RetryConfig.builder()
                .maxRetries(2)
                .backoff(RetryBackoff.fixed(10L))
                .abortOn(Set.of(HintedFailure.class))
                .retryAfterMaxMs(1_000L)
                .build();
        AtomicInteger aborted = new AtomicInteger();
        assertThrows(
                ExecutionException.class,
                () -> await(pipeline(abortOn).execute(() -> {
                    aborted.incrementAndGet();
                    return Future.failedFuture(new HintedFailure(Duration.ofMillis(5L)));
                })));
        assertEquals(1, aborted.get(), "a hint within the maximum must not override abortOn");
        assertTrue(retryScheduled().isEmpty());
    }

    /**
     * The known budget counts the hint maximum for fixed and exponential backoff, and a
     * custom backoff stays unknown.
     */
    @Test
    @DisplayName("a known budget counts the hint maximum and a custom backoff stays unknown")
    void knownBudgetCountsTheHintMaximumAndCustomStaysUnknown() {
        // Given a 1000 ms timeout, 2 retries and fixed backoff of 100 ms
        // When executionBudget() is read with retryAfterMaxMs 5000
        ResolvedResiliencePolicy fixed = retryPolicy(RetryBackoff.fixed(100L), 5_000L);
        // Then the Known active budget is 13000: 3 attempts of 1000 plus 2 retries of max(100, 5000)
        DurationBound.Known fixedBudget = assertInstanceOf(
                DurationBound.Known.class, fixed.executionBudget().activeExecution());
        assertEquals(13_000L, fixedBudget.valueMs());
        assertEquals(13_000L, fixed.executionBudget().total().maximumMs().orElseThrow());

        // And exponential backoff with maxDelayMs 400 gives Known 13000, each retry bound max(400, 5000)
        ResolvedResiliencePolicy exponential = retryPolicy(RetryBackoff.exponential(100L, 2.0d, 400L, 0L), 5_000L);
        assertEquals(
                13_000L,
                assertInstanceOf(
                                DurationBound.Known.class,
                                exponential.executionBudget().activeExecution())
                        .valueMs());

        // And a hint maximum below the existing per-retry bound leaves that bound: max(400, 50) = 400
        ResolvedResiliencePolicy belowBound = retryPolicy(RetryBackoff.exponential(100L, 2.0d, 400L, 0L), 50L);
        assertEquals(budgetOf(retryPolicy(RetryBackoff.exponential(100L, 2.0d, 400L, 0L), 0L)), budgetOf(belowBound));

        // And the custom backoff stays Unknown
        ResolvedResiliencePolicy custom = retryPolicy(RetryBackoff.custom(retryCount -> 100L), 5_000L);
        assertInstanceOf(DurationBound.Unknown.class, custom.executionBudget().activeExecution());

        // And with retryAfterMaxMs 0 the fixed-backoff budget is Known 3200
        ResolvedResiliencePolicy off = retryPolicy(RetryBackoff.fixed(100L), 0L);
        assertEquals(
                3_200L,
                assertInstanceOf(
                                DurationBound.Known.class, off.executionBudget().activeExecution())
                        .valueMs());
    }

    /**
     * Supporting proof: a hint accessor that throws, returns null, or returns a negative or
     * overflowing duration counts as no hint, so the sampled backoff alone applies.
     */
    @Test
    @DisplayName("a throwing, null or negative hint counts as no hint")
    void throwingNullOrNegativeHintCountsAsNoHint(Vertx vertx) throws Exception {
        // Given a policy that would refuse any hint above 100 ms and a sampled backoff of 20 ms
        resilience = Resilience.create(vertx, Set.of(recorder));
        RetryConfig config = retryConfig(1, RetryBackoff.fixed(20L), 100L);

        List<Supplier<Optional<Duration>>> unusableHints = List.of(
                () -> {
                    throw new IllegalStateException("accessor failure");
                },
                () -> null,
                () -> Optional.of(Duration.ofMillis(-5L)),
                () -> Optional.of(Duration.ofSeconds(Long.MAX_VALUE)),
                Optional::empty);

        for (int index = 0; index < unusableHints.size(); index++) {
            events.clear();
            Supplier<Optional<Duration>> hint = unusableHints.get(index);
            AtomicInteger attempts = new AtomicInteger();

            // When the first attempt fails with that unusable hint and the second succeeds
            String result = await(pipeline(config)
                    .execute(() -> attempts.getAndIncrement() == 0
                            ? Future.failedFuture(new HintedFailure(hint))
                            : Future.succeededFuture("recovered")));

            // Then the retry runs, delayed by the sampled backoff alone, with no exhaustion event
            assertEquals("recovered", result, "case " + index);
            assertEquals(2, attempts.get(), "case " + index);
            assertEquals(List.of(20L), delays(retryScheduled()), "case " + index);
            assertTrue(events.stream().noneMatch(RetryExhausted.class::isInstance), "case " + index);
        }
    }

    /**
     * Supporting proof: the tier key, the override component and the builder carry the maximum
     * through resolution, the previous constructors remain, and the key is validated and bound.
     */
    @Test
    @DisplayName("the policy key and the override carry the maximum and the previous constructors remain")
    void policyKeyAndOverrideCarryTheMaximumAndPreviousConstructorsRemain() {
        // Given the builder, the default and the validation of the maximum
        assertEquals(750L, RetryConfig.builder().retryAfterMaxMs(750L).build().retryAfterMaxMs());
        assertEquals(0L, RetryConfig.builder().build().retryAfterMaxMs());
        assertEquals(
                0L, RetryConfig.builder().retryAfterMaxMs(0L).build().retryAfterMaxMs(), "0 is the explicit off value");
        assertThrows(IllegalArgumentException.class, () -> RetryConfig.builder().retryAfterMaxMs(-1L));

        // When the key resilience.policies.<tier>.retry.retryAfterMaxMs is bound from configuration
        JsonObject root = new JsonObject()
                .put(
                        "resilience",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put(
                                                        "payments",
                                                        new JsonObject()
                                                                .put(
                                                                        "retry",
                                                                        new JsonObject()
                                                                                .put("maxRetries", 5)
                                                                                .put("retryAfterMaxMs", 2_000L)))));
        List<ResiliencePolicyConfig> parsed = PARSER.parseKeyedObject(
                JsonConfigPaths.navigateObject(root, "resilience", "policies"), "name", ResiliencePolicyConfig.class);

        // Then the bound record carries it, and the tier resolves it into the retry configuration
        RetryPolicyConfig bound = parsed.get(0).retry();
        assertEquals(5, bound.maxRetries());
        assertEquals(2_000L, bound.retryAfterMaxMs());
        ResiliencePolicyRegistry registry = ResiliencePolicyRegistry.of(parsed);
        RetryConfig resolvedTier = resolveTier(registry, ResiliencePolicyOverrides.none());
        assertEquals(5, resolvedTier.maxRetries());
        assertEquals(2_000L, resolvedTier.retryAfterMaxMs());

        // And a bound value that is omitted stays absent and resolves to 0
        assertNull(bound.toOverrides().backoff().orElse(null));
        assertNull(
                new RetryPolicyConfig(5, null, null, null, null).toOverrides().retryAfterMaxMs());
        assertEquals(
                0L,
                resolveTier(
                                ResiliencePolicyRegistry.of(List.of(new ResiliencePolicyConfig(
                                        "payments", null, new RetryPolicyConfig(5, null, null, null), null, null))),
                                ResiliencePolicyOverrides.none())
                        .retryAfterMaxMs());

        // And a negative value is rejected with the path of the key
        ConfigurationException negative = assertThrows(
                ConfigurationException.class,
                () -> new ResiliencePolicyConfig(
                        "payments", null, new RetryPolicyConfig(5, null, null, null, -1L), null, null));
        assertTrue(
                negative.getMessage().startsWith("resilience.policies.payments.retry.retryAfterMaxMs must"),
                negative.getMessage());
        assertEquals(
                0L, new RetryPolicyConfig(5, null, null, null, 0L).retryAfterMaxMs(), "0 is a valid value for the key");

        // And a transport override on the operation takes precedence over the tier value
        RetryOverride transport = new RetryOverride(
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                500L);
        assertEquals(500L, transport.retryAfterMaxMs());
        assertEquals(
                500L,
                resolveTier(
                                registry,
                                new ResiliencePolicyOverrides(
                                        Optional.empty(), Optional.of(transport), Optional.empty(), Optional.empty()))
                        .retryAfterMaxMs());

        // And the previous-arity constructors remain and delegate with the empty value
        RetryOverride previousOverride = new RetryOverride(
                Optional.empty(),
                OptionalInt.of(3),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertNull(previousOverride.retryAfterMaxMs());
        assertEquals(3, previousOverride.maxRetries().getAsInt());
        RetryPolicyConfig previousConfig = new RetryPolicyConfig(3, 10L, 2.0d, 100L);
        assertNull(previousConfig.retryAfterMaxMs());
        assertEquals(3, previousConfig.maxRetries());
        assertEquals(10L, previousConfig.delayMs());
        assertEquals(2.0d, previousConfig.backoffMultiplier());
        assertEquals(100L, previousConfig.maxDelayMs());
        assertEquals(
                previousConfig,
                new RetryPolicyConfig(3, 10L, 2.0d, 100L, null),
                "the previous arity equals the canonical constructor with the empty value");
    }

    /**
     * Supporting proof: the value is carried by every sibling-value check, resolution layer, merge
     * and tier-completion path, not only by the paths a single scenario happens to touch.
     */
    @Test
    @DisplayName("every with, copy and layering path carries the value")
    void everyWithCopyAndLayeringPathCarriesTheValue() {
        // Given overrides that differ only in retryAfterMaxMs
        RetryOverride high = retryOverride(Optional.empty(), OptionalInt.empty(), 1_000L);
        RetryOverride low = retryOverride(Optional.empty(), OptionalInt.of(4), 4_000L);
        RetryOverride lowWithoutValue = retryOverride(Optional.empty(), OptionalInt.of(4), null);
        RetryOverride highWithoutValue = retryOverride(Optional.empty(), OptionalInt.of(1), null);

        // When layered, then the higher value wins when present and the lower one is inherited otherwise
        assertEquals(1_000L, layered(high, low).retryAfterMaxMs(), "higher present wins");
        assertEquals(4_000L, layered(highWithoutValue, low).retryAfterMaxMs(), "absent higher takes the lower");
        assertNull(layered(highWithoutValue, lowWithoutValue).retryAfterMaxMs(), "absent on both stays absent");
        assertEquals(
                0L,
                layered(retryOverride(Optional.empty(), OptionalInt.empty(), 0L), low)
                        .retryAfterMaxMs(),
                "an explicit 0 is a value and wins over the lower one");
        assertEquals(4, layered(high, low).maxRetries().getAsInt(), "the other leaves still merge");

        // And the value counts as a sibling value: a disabled override cannot carry it
        assertThrows(
                IllegalArgumentException.class,
                () -> retryOverride(Optional.of(false), OptionalInt.empty(), 1_000L),
                "a disabled retry override cannot contain retry values");
        assertEquals(
                Optional.of(false),
                retryOverride(Optional.of(false), OptionalInt.empty(), null).enabled());

        // And a higher override that carries only the value overrides a disabled lower one's enabled flag
        RetryOverride disabledLower = retryOverride(Optional.of(false), OptionalInt.empty(), null);
        RetryOverride valueOnlyHigher = retryOverride(Optional.empty(), OptionalInt.empty(), 1_000L);
        RetryOverride mergedOverDisabled = layered(valueOnlyHigher, disabledLower);
        assertEquals(1_000L, mergedOverDisabled.retryAfterMaxMs());
        assertTrue(
                mergedOverDisabled.enabled().isEmpty(),
                "a higher override with a sibling value is not disabled by the lower one");

        // And a higher disabled override wins as a whole
        RetryOverride disabledHigher = retryOverride(Optional.of(false), OptionalInt.empty(), null);
        assertEquals(Optional.of(false), layered(disabledHigher, low).enabled());
        assertNull(layered(disabledHigher, low).retryAfterMaxMs());

        // And the resolver carries the value from the operation, then from the defaults, then 0
        RetryConfig defaults = RetryConfig.builder()
                .maxRetries(3)
                .backoff(RetryBackoff.fixed(5L))
                .retryAfterMaxMs(900L)
                .build();
        ResilienceDefaults withDefaults =
                new ResilienceDefaults(Optional.empty(), Optional.of(defaults), Optional.empty(), Optional.empty());
        assertEquals(900L, resolve(Optional.empty(), withDefaults).retryAfterMaxMs(), "defaults only");
        assertEquals(
                750L,
                resolve(Optional.of(retryOverride(Optional.empty(), OptionalInt.empty(), 750L)), withDefaults)
                        .retryAfterMaxMs(),
                "an override that carries only the value defines the concern and wins over the default");
        assertEquals(
                3,
                resolve(Optional.of(retryOverride(Optional.empty(), OptionalInt.empty(), 750L)), withDefaults)
                        .maxRetries(),
                "the other leaves still come from the defaults");
        assertEquals(
                0L,
                resolve(Optional.of(retryOverride(Optional.empty(), OptionalInt.empty(), 0L)), withDefaults)
                        .retryAfterMaxMs(),
                "an explicit 0 on the operation turns hints off over a default");
        assertEquals(
                900L,
                resolve(Optional.of(retryOverride(Optional.empty(), OptionalInt.of(2), null)), withDefaults)
                        .retryAfterMaxMs(),
                "an absent override value keeps the default");

        // And the tier completion carries the value through each branch of the completed override
        ResiliencePolicyRegistry registry = ResiliencePolicyRegistry.of(List.of(new ResiliencePolicyConfig(
                "payments", null, new RetryPolicyConfig(5, 100L, 2.0d, 1_000L, 2_000L), null, null)));
        ResilienceAnnotations anchor = anchorOnly("payments");
        assertEquals(
                2_000L,
                registry.layer(anchor, ResiliencePolicyOverrides.none())
                        .retry()
                        .orElseThrow()
                        .retryAfterMaxMs(),
                "the completed tier retry override");
        RetryOverride customBackoff = new RetryOverride(
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(new BackoffOverride(
                        Optional.of(retryCount -> 7L),
                        OptionalLong.empty(),
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertEquals(
                2_000L,
                resolveTier(
                                registry,
                                new ResiliencePolicyOverrides(
                                        Optional.empty(),
                                        Optional.of(customBackoff),
                                        Optional.empty(),
                                        Optional.empty()))
                        .retryAfterMaxMs(),
                "a transport override with a custom backoff still inherits the tier value");
        assertEquals(
                2_000L,
                registry.layer(
                                anchor,
                                new ResiliencePolicyOverrides(
                                        Optional.empty(),
                                        Optional.of(retryOverride(Optional.empty(), OptionalInt.of(1), null)),
                                        Optional.empty(),
                                        Optional.empty()))
                        .retry()
                        .orElseThrow()
                        .retryAfterMaxMs(),
                "a transport override without the value inherits the tier value");

        // And the tier record converts to an override with the value and without losing the other leaves
        RetryOverride converted = new RetryPolicyConfig(5, 100L, 2.0d, 1_000L, 2_000L).toOverrides();
        assertEquals(2_000L, converted.retryAfterMaxMs());
        assertEquals(5, converted.maxRetries().getAsInt());
        assertEquals(100L, converted.backoff().orElseThrow().initialDelayMs().getAsLong());
        assertNull(
                new RetryPolicyConfig(5, 100L, 2.0d, 1_000L, null).toOverrides().retryAfterMaxMs());

        // And a retry configuration resolved with a value counts it in the resolved policy's budget
        RetryConfig resolved = resolve(
                Optional.of(retryOverride(Optional.empty(), OptionalInt.of(2), 5_000L)),
                new ResilienceDefaults(
                        Optional.empty(),
                        Optional.of(RetryConfig.builder()
                                .backoff(RetryBackoff.fixed(100L))
                                .build()),
                        Optional.empty(),
                        Optional.empty()));
        assertEquals(5_000L, resolved.retryAfterMaxMs());
        ResolvedResiliencePolicy policy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(1_000L)), Optional.of(resolved), Optional.empty(), Optional.empty());
        assertEquals(
                13_000L,
                assertInstanceOf(
                                DurationBound.Known.class,
                                policy.executionBudget().activeExecution())
                        .valueMs());
    }

    private ResiliencePipeline pipeline(RetryConfig config) {
        ResolvedResiliencePolicy policy =
                new ResolvedResiliencePolicy(Optional.empty(), Optional.of(config), Optional.empty(), Optional.empty());
        return resilience.adapterSupport().pipeline(identity(), policy);
    }

    private AdapterOperationIdentity identity() {
        return new AdapterOperationIdentity("retry.hint", List.of("scenario", "n" + pipelineCounter.incrementAndGet()));
    }

    private static RetryConfig retryConfig(int maxRetries, RetryBackoff backoff, long retryAfterMaxMs) {
        return RetryConfig.builder()
                .maxRetries(maxRetries)
                .backoff(backoff)
                .retryAfterMaxMs(retryAfterMaxMs)
                .build();
    }

    private static ResolvedResiliencePolicy retryPolicy(RetryBackoff backoff, long retryAfterMaxMs) {
        return new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(1_000L)),
                Optional.of(retryConfig(2, backoff, retryAfterMaxMs)),
                Optional.empty(),
                Optional.empty());
    }

    private static ExecutionBudget budgetOf(ResolvedResiliencePolicy policy) {
        return policy.executionBudget();
    }

    private static BackoffOverride fixedLikeBackoff(long delayMs) {
        return new BackoffOverride(
                Optional.empty(),
                OptionalLong.of(delayMs),
                Optional.of(1.0d),
                OptionalLong.of(delayMs),
                OptionalLong.of(0L));
    }

    private static RetryOverride retryOverride(
            Optional<Boolean> enabled, OptionalInt maxRetries, Long retryAfterMaxMs) {
        return new RetryOverride(
                enabled,
                maxRetries,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                retryAfterMaxMs);
    }

    private static RetryOverride layered(RetryOverride higher, RetryOverride lower) {
        return new ResiliencePolicyOverrides(Optional.empty(), Optional.of(higher), Optional.empty(), Optional.empty())
                .over(new ResiliencePolicyOverrides(
                        Optional.empty(), Optional.of(lower), Optional.empty(), Optional.empty()))
                .retry()
                .orElseThrow();
    }

    private RetryConfig resolve(Optional<RetryOverride> override, ResilienceDefaults defaults) {
        ResiliencePolicyResolver resolver = new ResiliencePolicyResolver();
        return resolver.resolve(
                        ResilienceAnnotations.NONE,
                        new ResiliencePolicyOverrides(Optional.empty(), override, Optional.empty(), Optional.empty()),
                        defaults)
                .retry()
                .orElseThrow();
    }

    private static RetryConfig resolveTier(ResiliencePolicyRegistry registry, ResiliencePolicyOverrides transport) {
        ResilienceAnnotations annotations = anchorOnly("payments");
        return new ResiliencePolicyResolver()
                .resolve(annotations, registry.layer(annotations, transport), ResilienceDefaults.none())
                .retry()
                .orElseThrow();
    }

    private static ResilienceAnnotations anchorOnly(String policy) {
        return new ResilienceAnnotations(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(policy));
    }

    private List<RetryScheduled> retryScheduled() {
        return events.stream()
                .filter(RetryScheduled.class::isInstance)
                .map(RetryScheduled.class::cast)
                .toList();
    }

    private static List<Long> delays(List<RetryScheduled> scheduled) {
        return scheduled.stream().map(RetryScheduled::delayMs).toList();
    }

    private static long millisBetween(long fromNanos, long toNanos) {
        return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A failure that exposes a retry delay hint through a configurable accessor. */
    private static final class HintedFailure extends RuntimeException implements RetryDelayHint {
        private final transient Supplier<Optional<Duration>> hint;

        HintedFailure(Duration hint) {
            this(() -> Optional.of(hint), null);
        }

        HintedFailure(Duration hint, Throwable cause) {
            this(() -> Optional.of(hint), cause);
        }

        HintedFailure(Supplier<Optional<Duration>> hint) {
            this(hint, null);
        }

        private HintedFailure(Supplier<Optional<Duration>> hint, Throwable cause) {
            super("hinted failure", cause);
            this.hint = hint;
        }

        @Override
        public Optional<Duration> retryAfter() {
            return hint.get();
        }
    }
}
