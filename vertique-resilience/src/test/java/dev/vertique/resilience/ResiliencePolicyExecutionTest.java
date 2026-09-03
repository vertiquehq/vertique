// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.ResilienceAdapterSupport;
import dev.vertique.resilience.annotation.CircuitBreakerDeclaration;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.RetryDeclaration;
import dev.vertique.resilience.annotation.TimeoutDeclaration;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 contract proof for policy resolution, retry execution, budgets, and the structured
 * adapter facade.
 *
 * <p>The scenarios intentionally cross the public resolver and execution boundaries. They keep
 * retry eligibility and backoff ordinals visible, compare the resolved budget with the actual
 * retry plan, and prove that unsupported later concerns fail before execution is admitted.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ResiliencePolicyExecutionTest {

    private static final long MAX = Long.MAX_VALUE;
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    private Resilience resilience;
    private Vertx ownedVertx;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
        if (ownedVertx != null) {
            await(ownedVertx.close());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("precedenceCases")
    @DisplayName("resolver applies operation, annotation, and default precedence at field level")
    void resolverUsesOperationAnnotationAndDefaultPrecedence(
            String caseName,
            ResilienceAnnotations annotations,
            ResiliencePolicyOverrides operationOverrides,
            ResilienceDefaults defaults,
            boolean expectedEnabled,
            int expectedMaxRetries,
            long expectedInitialDelayMs,
            boolean expectedExponentialBackoff,
            double expectedMultiplier,
            long expectedMaxDelayMs,
            long expectedMaxJitterMs) {
        resilience = createOwnedRuntime();

        ResolvedResiliencePolicy resolved =
                resilience.policyResolver().resolve(annotations, operationOverrides, defaults);

        assertEquals(expectedEnabled, resolved.retry().isPresent(), caseName);
        if (expectedEnabled) {
            RetryConfig retry = resolved.retry().orElseThrow();
            assertEquals(expectedMaxRetries, retry.maxRetries(), caseName);
            if (expectedExponentialBackoff) {
                RetryBackoff.Exponential backoff = assertInstanceOf(RetryBackoff.Exponential.class, retry.backoff());
                assertEquals(expectedInitialDelayMs, backoff.initialDelayMs(), caseName);
                assertEquals(expectedMultiplier, backoff.multiplier(), caseName);
                assertEquals(expectedMaxDelayMs, backoff.maxDelayMs(), caseName);
                assertEquals(expectedMaxJitterMs, backoff.maxJitterMs(), caseName);
            } else {
                RetryBackoff.Fixed backoff = assertInstanceOf(RetryBackoff.Fixed.class, retry.backoff());
                assertEquals(expectedInitialDelayMs, backoff.delayMs(), caseName);
            }
        }
    }

    @Test
    @DisplayName("resolver applies field precedence independently across timeout, breaker, and bulkhead")
    void resolverAppliesConcernPrecedenceAcrossResolvedPolicy() {
        resilience = createOwnedRuntime();
        ResilienceAnnotations annotations = new ResilienceAnnotations(
                Optional.of(new TimeoutDeclaration(2L, TimeUnit.SECONDS)),
                Optional.of(new CircuitBreakerDeclaration(4, 100L, 200L)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        ResiliencePolicyOverrides overrides = new ResiliencePolicyOverrides(
                Optional.of(new TimeoutOverride(Optional.empty(), OptionalLong.of(7L))),
                Optional.empty(),
                Optional.of(new CircuitBreakerOverride(Optional.empty(), OptionalInt.of(2), OptionalLong.empty())),
                Optional.of(new BulkheadOverride(Optional.empty(), Optional.of(BulkheadConfig.reject(1)))));
        ResilienceDefaults defaults = new ResilienceDefaults(
                Optional.of(TimeoutConfig.ofMillis(50L)),
                Optional.empty(),
                Optional.of(CircuitBreakerConfig.builder()
                        .maxFailures(9)
                        .resetTimeoutMs(900L)
                        .build()),
                Optional.of(BulkheadConfig.reject(3)));

        ResolvedResiliencePolicy resolved = resilience.policyResolver().resolve(annotations, overrides, defaults);

        assertEquals(7L, resolved.timeout().orElseThrow().timeoutMs());
        assertEquals(2, resolved.circuitBreaker().orElseThrow().maxFailures());
        assertEquals(
                1,
                assertInstanceOf(
                                BulkheadConfig.Reject.class, resolved.bulkhead().orElseThrow())
                        .maxConcurrentCalls());
    }

    @Test
    @DisplayName("annotation metadata resolves through the canonical method-over-type path")
    void resolverConsumesCanonicalAnnotationMetadata() throws Exception {
        resilience = createOwnedRuntime();
        Method method = AnnotatedPolicy.class.getDeclaredMethod("operation");

        ResolvedResiliencePolicy resolved = resilience
                .policyResolver()
                .resolve(
                        ResilienceAnnotations.resolve(method),
                        ResiliencePolicyOverrides.none(),
                        ResilienceDefaults.none());

        RetryConfig retry = resolved.retry().orElseThrow();
        assertEquals(2, retry.maxRetries());
        RetryBackoff.Exponential backoff = assertInstanceOf(RetryBackoff.Exponential.class, retry.backoff());
        assertEquals(7L, backoff.initialDelayMs());
        assertEquals(19L, backoff.maxDelayMs());
        BulkheadConfig.Queue bulkhead =
                assertInstanceOf(BulkheadConfig.Queue.class, resolved.bulkhead().orElseThrow());
        assertEquals(3, bulkhead.maxConcurrentCalls());
        assertEquals(2, bulkhead.maxQueueSize());
        assertEquals(125L, bulkhead.queueTimeoutMs());
    }

    @Test
    @DisplayName("operation scalar backoff inherits annotation inline fields over an annotation custom class")
    void operationScalarBackoffInheritsAnnotationInlineFieldsOverCustomClass() {
        resilience = createOwnedRuntime();
        ResilienceAnnotations annotations = new ResilienceAnnotations(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new RetryDeclaration(3, 20L, 2.0, 200L, AnnotationBackoff.class, List.of(), List.of())),
                Optional.empty(),
                Optional.empty());
        RetryOverride operation = new RetryOverride(
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(new BackoffOverride(
                        Optional.empty(),
                        OptionalLong.of(5L),
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        ResiliencePolicyOverrides overrides = new ResiliencePolicyOverrides(
                Optional.empty(), Optional.of(operation), Optional.empty(), Optional.empty());
        RetryConfig defaultsRetry = RetryConfig.builder()
                .maxRetries(9)
                .backoff(RetryBackoff.exponential(70L, 3.0, 700L, 9L))
                .build();
        ResilienceDefaults defaults = new ResilienceDefaults(
                Optional.empty(), Optional.of(defaultsRetry), Optional.empty(), Optional.empty());

        RetryBackoff.Exponential backoff = assertInstanceOf(
                RetryBackoff.Exponential.class,
                resilience
                        .policyResolver()
                        .resolve(annotations, overrides, defaults)
                        .retry()
                        .orElseThrow()
                        .backoff());
        assertEquals(5L, backoff.initialDelayMs());
        assertEquals(2.0, backoff.multiplier());
        assertEquals(200L, backoff.maxDelayMs());
        assertEquals(9L, backoff.maxJitterMs());
    }

    @Test
    @DisplayName("fixed and exponential backoff preserve bounded fields and deterministic zero jitter")
    void fixedAndExponentialBackoffExposeDeterministicSchedules() {
        RetryBackoff.Fixed fixed = RetryBackoff.fixed(25L);
        assertEquals(25L, fixed.delayMs());

        RetryBackoff.Exponential exponential = RetryBackoff.exponential(10L, 2.0, 25L, 0L);
        assertEquals(10L, exponential.initialDelayMs());
        assertEquals(2.0, exponential.multiplier());
        assertEquals(25L, exponential.maxDelayMs());
        assertEquals(0L, exponential.maxJitterMs());

        RetryBackoff.Exponential capped = RetryBackoff.exponential(50L, 2.0, 25L, 4L);
        assertEquals(25L, capped.maxDelayMs(), "the cap may be below the initial delay");
        assertEquals(4L, capped.maxJitterMs());
    }

    /**
     * The stable TP-001 identifier from the task contract. It proves that resolver output,
     * direct retry, inline retry, prebuilt retry, callback ordinals, and the known budget agree.
     */
    @Test
    @DisplayName("resolved retry schedule matches direct and pipeline execution budget")
    void resolvedRetryScheduleMatchesExecutionBudget(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        List<Integer> eligibilityOrdinals = new ArrayList<>();
        List<Integer> backoffOrdinals = new ArrayList<>();

        RetryConfig retryConfig = RetryConfig.builder()
                .maxRetries(2)
                .backoff(RetryBackoff.fixed(5L))
                .build();
        ResolvedResiliencePolicy resolved = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(10L)), Optional.of(retryConfig), Optional.empty(), Optional.empty());

        DurationBound.Known activeBudget = assertInstanceOf(
                DurationBound.Known.class, resolved.executionBudget().activeExecution());
        assertEquals(40L, activeBudget.valueMs(), "three attempt fences plus two retry delays");
        assertFalse(activeBudget.saturated());
        assertEquals(40L, resolved.executionBudget().total().maximumMs().orElseThrow());

        Retry direct = Retry.builder(resilience, "direct-retry")
                .maxRetries(2)
                .backoff(RetryBackoff.custom(retryCount -> {
                    backoffOrdinals.add(retryCount);
                    return 0L;
                }))
                .fallbackPolicy((failure, retryCount) -> {
                    eligibilityOrdinals.add(retryCount);
                    return true;
                })
                .build();
        AtomicInteger directAttempts = new AtomicInteger();
        assertEquals("direct-success", await(direct.execute(failingThenSuccess(directAttempts, "direct-success", 2))));
        assertEquals(3, directAttempts.get());
        assertEquals(List.of(0, 1), eligibilityOrdinals);
        assertEquals(List.of(0, 1), backoffOrdinals);

        ResiliencePipeline inline = resilience
                .pipeline("inline-retry")
                .retry(retry -> retry.maxRetries(2).backoff(RetryBackoff.fixed(0L)))
                .build();
        AtomicInteger inlineAttempts = new AtomicInteger();
        assertEquals("inline-success", await(inline.execute(failingThenSuccess(inlineAttempts, "inline-success", 2))));
        assertEquals(3, inlineAttempts.get());

        Retry prebuiltRetry = Retry.builder(resilience, "prebuilt-retry")
                .maxRetries(2)
                .backoff(RetryBackoff.fixed(0L))
                .build();
        ResiliencePipeline prebuilt =
                resilience.pipeline("pipeline-retry").retry(prebuiltRetry).build();
        AtomicInteger prebuiltAttempts = new AtomicInteger();
        assertEquals(
                "prebuilt-success",
                await(prebuilt.execute(failingThenSuccess(prebuiltAttempts, "prebuilt-success", 2))));
        assertEquals(3, prebuiltAttempts.get());
    }

    @Test
    @DisplayName("timeout fences each attempt while retry delay remains outside the timeout")
    void timeoutIsPerAttemptAndBackoffIsOutsideTheTimeout(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = resilience
                .pipeline("per-attempt-timeout")
                .timeout(timeout -> timeout.duration(Duration.ofMillis(25L)))
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(100L)))
                .build();

        Promise<String> firstAttempt = Promise.promise();
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong secondAttemptStartedAt = new AtomicLong();
        long executionStartedAt = System.nanoTime();

        Future<String> result = pipeline.execute(() -> {
            if (attempts.getAndIncrement() == 0) {
                firstStarted.countDown();
                return firstAttempt.future();
            }
            secondAttemptStartedAt.set(System.nanoTime());
            return Future.succeededFuture("recovered");
        });

        assertTrue(firstStarted.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals("recovered", await(result));
        assertEquals(2, attempts.get(), "the timed-out first attempt must be retried");
        assertTrue(
                Duration.ofNanos(secondAttemptStartedAt.get() - executionStartedAt)
                                .toMillis()
                        >= 80L,
                "retry delay must not be consumed by the first per-attempt timeout");
        assertFalse(firstAttempt.future().isComplete(), "timeout must not cancel the timed-out supplier future");

        firstAttempt.complete("late");
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("concurrent logical executions keep independent Vert.x retry state")
    void concurrentExecutionsDoNotShareRetryState(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = resilience
                .pipeline("concurrent-retry")
                .retry(retry -> retry.maxRetries(1)
                        .backoff(RetryBackoff.fixed(0L))
                        .retryOn(Set.of(IllegalStateException.class)))
                .build();

        List<Future<String>> results = new ArrayList<>();
        List<AtomicInteger> attempts = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            AtomicInteger executionAttempts = new AtomicInteger();
            attempts.add(executionAttempts);
            results.add(pipeline.execute(() -> executionAttempts.getAndIncrement() == 0
                    ? Future.failedFuture(new IllegalStateException("retryable"))
                    : Future.succeededFuture("ok")));
        }

        for (Future<String> result : results) {
            assertEquals("ok", await(result));
        }
        for (AtomicInteger executionAttempts : attempts) {
            assertEquals(2, executionAttempts.get());
        }
    }

    @Test
    @DisplayName("known, unknown, and saturated budgets retain agreement without arithmetic wraparound")
    void executionBudgetUsesUnknownAndSaturatingArithmetic() {
        RetryConfig exponentialRetry = RetryConfig.builder()
                .maxRetries(2)
                .backoff(RetryBackoff.exponential(10L, 2.0, 25L, 0L))
                .build();
        ResolvedResiliencePolicy exponentialPolicy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(10L)),
                Optional.of(exponentialRetry),
                Optional.empty(),
                Optional.empty());
        DurationBound.Known exponentialBudget = assertInstanceOf(
                DurationBound.Known.class, exponentialPolicy.executionBudget().activeExecution());
        assertEquals(60L, exponentialBudget.valueMs(), "three 10 ms attempt fences plus 10 ms and 20 ms delays");
        assertEquals(
                0L, exponentialPolicy.executionBudget().queueWait().maximumMs().orElseThrow());
        assertEquals(
                60L, exponentialPolicy.executionBudget().total().maximumMs().orElseThrow());

        RetryConfig customRetry = RetryConfig.builder()
                .maxRetries(2)
                .backoff(RetryBackoff.custom(retryCount -> 1L))
                .build();
        ResolvedResiliencePolicy unknownPolicy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(10L)), Optional.of(customRetry), Optional.empty(), Optional.empty());
        assertInstanceOf(
                DurationBound.Unknown.class, unknownPolicy.executionBudget().activeExecution());
        assertInstanceOf(
                DurationBound.Unknown.class, unknownPolicy.executionBudget().total());

        ResolvedResiliencePolicy unboundedPolicy = new ResolvedResiliencePolicy(
                Optional.empty(), Optional.of(exponentialRetry), Optional.empty(), Optional.empty());
        assertInstanceOf(
                DurationBound.Unbounded.class, unboundedPolicy.executionBudget().activeExecution());

        ResolvedResiliencePolicy queuedPolicy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(10L)),
                Optional.of(exponentialRetry),
                Optional.empty(),
                Optional.of(BulkheadConfig.queue(1, 1, Duration.ofMillis(250L))));
        assertEquals(
                250L, queuedPolicy.executionBudget().queueWait().maximumMs().orElseThrow());
        assertEquals(310L, queuedPolicy.executionBudget().total().maximumMs().orElseThrow());

        RetryConfig saturatingRetry = RetryConfig.builder()
                .maxRetries(100)
                .backoff(RetryBackoff.fixed(MAX))
                .build();
        ResolvedResiliencePolicy saturatedPolicy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(MAX)),
                Optional.of(saturatingRetry),
                Optional.empty(),
                Optional.empty());
        DurationBound.Known saturatedActive = assertInstanceOf(
                DurationBound.Known.class, saturatedPolicy.executionBudget().activeExecution());
        assertEquals(MAX, saturatedActive.valueMs());
        assertTrue(saturatedActive.saturated());

        ExecutionBudget budget =
                new ExecutionBudget(new DurationBound.Known(MAX, true), new DurationBound.Known(500L, false));
        DurationBound.Known total = assertInstanceOf(DurationBound.Known.class, budget.total());
        assertEquals(MAX, total.valueMs());
        assertTrue(total.saturated(), "adding queue wait must remain saturated instead of wrapping");
    }

    @Test
    @DisplayName("structured adapter identity derives one canonical key and executes retry policies")
    void structuredPipelineUsesCanonicalIdentityAndNoRawKeyOverload(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        AdapterOperationIdentity identity =
                new AdapterOperationIdentity("service.operation", List.of("default", "inventory", "read"));
        ResilienceAdapterSupport support = resilience.adapterSupport();
        ResiliencePipeline pipeline = support.pipeline(identity, timeoutAndRetryPolicy());

        assertEquals(
                "service:operation:6fe68204f884ba5740cc4498e63604642053075392e3052099c9dd13637d45d9",
                pipeline.operationKey());
        assertNotNull(ResilienceAdapterSupport.class.getMethod(
                "pipeline", AdapterOperationIdentity.class, ResolvedResiliencePolicy.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> ResilienceAdapterSupport.class.getMethod(
                        "pipeline", String.class, ResolvedResiliencePolicy.class));
        assertTrue(Arrays.stream(ResilienceAdapterSupport.class.getDeclaredMethods())
                .noneMatch(method -> Modifier.isPublic(method.getModifiers())
                        && method.getReturnType() == String.class
                        && (method.getName().equals("derive")
                                || method.getName().equals("operationKey"))));

        AtomicInteger attempts = new AtomicInteger();
        assertEquals("adapter-success", await(pipeline.execute(failingThenSuccess(attempts, "adapter-success", 1))));
        assertEquals(2, attempts.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredPolicyCases")
    @DisplayName("structured facade accepts each non-empty timeout/retry policy and rejects empty policy")
    void structuredFacadeRequiresAtLeastOneTimeoutOrRetryConcern(
            String caseName, ResolvedResiliencePolicy policy, int expectedAttempts) throws Exception {
        resilience = createOwnedRuntime();
        AdapterOperationIdentity identity =
                new AdapterOperationIdentity("service.operation", List.of("structured", caseName));

        if (expectedAttempts < 0) {
            assertThrows(
                    IllegalStateException.class,
                    () -> resilience.adapterSupport().pipeline(identity, policy),
                    caseName);
            return;
        }

        ResiliencePipeline pipeline = resilience.adapterSupport().pipeline(identity, policy);
        AtomicInteger attempts = new AtomicInteger();
        assertEquals(
                "structured-success",
                await(pipeline.execute(failingThenSuccess(attempts, "structured-success", expectedAttempts - 1))),
                caseName);
        assertEquals(expectedAttempts, attempts.get(), caseName);
    }

    @Test
    @DisplayName("breaker-bearing structured policies are rejected at factory construction")
    void structuredPipelineRejectsBreakerPolicyAtFactoryConstruction() {
        resilience = createOwnedRuntime();
        ResolvedResiliencePolicy policy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(10L)),
                Optional.empty(),
                Optional.of(CircuitBreakerConfig.builder().build()),
                Optional.empty());

        assertThrows(ResiliencePolicyException.class, () -> resilience
                .adapterSupport()
                .pipeline(new AdapterOperationIdentity("service.operation", List.of("breaker")), policy));
    }

    @Test
    @DisplayName("bulkhead-bearing structured policies are rejected at factory construction")
    void structuredPipelineRejectsBulkheadPolicyAtFactoryConstruction() {
        resilience = createOwnedRuntime();
        ResolvedResiliencePolicy policy = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(10L)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(BulkheadConfig.reject(1)));

        assertThrows(ResiliencePolicyException.class, () -> resilience
                .adapterSupport()
                .pipeline(new AdapterOperationIdentity("service.operation", List.of("bulkhead")), policy));
    }

    @Test
    @DisplayName("runtime close cancels a pending retry delay and settles the logical execution once")
    void closeCancelsRetryDelayAndPreventsFurtherAttempts(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Retry retry = Retry.builder(resilience, "close-retry")
                .maxRetries(1)
                .backoff(RetryBackoff.fixed(60_000L))
                .build();
        CountDownLatch firstAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        Future<String> result = retry.execute(() -> {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            return Future.failedFuture(new IllegalStateException("first failure"));
        });

        assertTrue(firstAttempt.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        Future<Void> close = resilience.close();
        await(close);

        try {
            await(result);
        } catch (ExecutionException expected) {
            assertInstanceOf(ResilienceClosedException.class, expected.getCause());
        }
        assertEquals(1, attempts.get(), "close must prevent the scheduled retry attempt");
        assertSame(close, resilience.close());
    }

    @Test
    @DisplayName("abortOn wins over retryOn and exhaustion preserves the last application failure")
    void abortOnWinsAndExhaustionPreservesLastFailure(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Retry retry = Retry.builder(resilience, "abort-precedence")
                .maxRetries(3)
                .backoff(RetryBackoff.fixed(0L))
                .retryOn(Set.of(RuntimeException.class))
                .abortOn(Set.of(IllegalArgumentException.class))
                .build();
        IllegalArgumentException terminalFailure = new IllegalArgumentException("terminal");
        AtomicInteger attempts = new AtomicInteger();

        Future<String> result = retry.execute(() -> {
            if (attempts.getAndIncrement() == 0) {
                return Future.failedFuture(new IllegalStateException("eligible"));
            }
            return Future.failedFuture(terminalFailure);
        });

        try {
            await(result);
        } catch (ExecutionException expectedFailure) {
            assertSame(terminalFailure, expectedFailure.getCause());
        }
        assertEquals(2, attempts.get());
        assertSame(terminalFailure, result.cause());
    }

    private static Stream<Arguments> precedenceCases() {
        RetryDeclaration annotationRetry =
                new RetryDeclaration(3, 20L, 2.0, 200L, BackoffStrategy.Default.class, List.of(), List.of());
        RetryConfig defaultRetry = RetryConfig.builder()
                .maxRetries(9)
                .backoff(RetryBackoff.fixed(70L))
                .build();

        RetryOverride operationFields = new RetryOverride(
                Optional.empty(),
                OptionalInt.of(1),
                Optional.of(new BackoffOverride(
                        Optional.empty(),
                        OptionalLong.of(5L),
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.of(0L))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        RetryOverride operationDisable = new RetryOverride(
                Optional.of(false),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        return Stream.of(
                Arguments.of(
                        "operation fields inherit missing scalar values from annotation",
                        annotations(annotationRetry),
                        retryOverrides(operationFields),
                        defaults(defaultRetry),
                        true,
                        1,
                        5L,
                        true,
                        2.0,
                        200L,
                        0L),
                Arguments.of(
                        "annotation wins over complete defaults",
                        annotations(annotationRetry),
                        ResiliencePolicyOverrides.none(),
                        defaults(defaultRetry),
                        true,
                        3,
                        20L,
                        true,
                        2.0,
                        200L,
                        1_000L),
                Arguments.of(
                        "complete defaults enable only when supplied as the lower layer",
                        ResilienceAnnotations.NONE,
                        ResiliencePolicyOverrides.none(),
                        defaults(defaultRetry),
                        true,
                        9,
                        70L,
                        false,
                        0.0,
                        0L,
                        0L),
                Arguments.of(
                        "operation disable blocks annotation and default inheritance",
                        annotations(annotationRetry),
                        retryOverrides(operationDisable),
                        defaults(defaultRetry),
                        false,
                        0,
                        0L,
                        false,
                        0.0,
                        0L,
                        0L));
    }

    private static Stream<Arguments> structuredPolicyCases() {
        RetryConfig retry = RetryConfig.builder()
                .maxRetries(1)
                .backoff(RetryBackoff.fixed(0L))
                .build();
        return Stream.of(
                Arguments.of(
                        "timeout-only",
                        new ResolvedResiliencePolicy(
                                Optional.of(TimeoutConfig.ofMillis(100L)),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()),
                        1),
                Arguments.of(
                        "retry-only",
                        new ResolvedResiliencePolicy(
                                Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty()),
                        2),
                Arguments.of(
                        "timeout-and-retry",
                        new ResolvedResiliencePolicy(
                                Optional.of(TimeoutConfig.ofMillis(100L)),
                                Optional.of(retry),
                                Optional.empty(),
                                Optional.empty()),
                        2),
                Arguments.of(
                        "empty",
                        new ResolvedResiliencePolicy(
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        -1));
    }

    private Resilience createOwnedRuntime() {
        ownedVertx = Vertx.vertx();
        return resilience = Resilience.create(ownedVertx);
    }

    private static ResilienceAnnotations annotations(RetryDeclaration retry) {
        return new ResilienceAnnotations(
                Optional.empty(), Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty());
    }

    private static ResiliencePolicyOverrides retryOverrides(RetryOverride retry) {
        return new ResiliencePolicyOverrides(Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty());
    }

    private static ResilienceDefaults defaults(RetryConfig retry) {
        return new ResilienceDefaults(Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty());
    }

    private ResolvedResiliencePolicy timeoutAndRetryPolicy() {
        RetryConfig retry = RetryConfig.builder()
                .maxRetries(1)
                .backoff(RetryBackoff.fixed(0L))
                .build();
        return new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(100L)), Optional.of(retry), Optional.empty(), Optional.empty());
    }

    private static <T> Supplier<Future<T>> failingThenSuccess(
            AtomicInteger attempts, T success, int failuresBeforeSuccess) {
        return () -> {
            int attempt = attempts.getAndIncrement();
            if (attempt < failuresBeforeSuccess) {
                return Future.failedFuture(new IllegalStateException("attempt-" + attempt));
            }
            return Future.succeededFuture(success);
        };
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @dev.vertique.resilience.annotation.Retry(maxRetries = 5, delayMs = 99, maxDelayMs = 300)
    @dev.vertique.resilience.annotation.Bulkhead(
            maxConcurrentCalls = 3,
            mode = dev.vertique.resilience.annotation.Bulkhead.Mode.QUEUE,
            maxQueueSize = 2,
            queueTimeoutMs = 125)
    private static final class AnnotatedPolicy {

        @dev.vertique.resilience.annotation.Retry(maxRetries = 2, delayMs = 7, maxDelayMs = 19)
        private void operation() {}
    }

    public static final class AnnotationBackoff implements BackoffStrategy {

        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }
}
