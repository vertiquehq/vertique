// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.resilience.exception.ResiliencePolicyException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * TP-002 red proof for eager policy validation and fail-safe retry callbacks.
 *
 * <p>T002 production types are deliberately loaded reflectively until the policy implementation
 * exists. This follows the T011 proof convention: the test remains compilable against the
 * dependency baseline while still freezing the public API and its observable behavior.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ResiliencePolicyValidationTest {

    private static final String RESILIENCE_ANNOTATIONS = "dev.vertique.resilience.annotation.ResilienceAnnotations";
    private static final String RETRY_BACKOFF = "dev.vertique.resilience.RetryBackoff";
    private static final String RETRY_POLICY = "dev.vertique.resilience.RetryPolicy";
    private static final String CALLBACK_SECRET = "callback-secret-value";
    private static final String ATTEMPT_SECRET = "attempt-secret-value";
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final long AWAIT_TIMEOUT_MS = 5_000;

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    @Test
    @DisplayName("all public policy bounds reject their first invalid value")
    void rejectsEveryInvalidBound() {
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.TimeoutConfig", "ofMillis", new Class<?>[] {long.class}, 0L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.TimeoutConfig", "ofMillis", new Class<?>[] {long.class}, -1L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.TimeoutConfig",
                        "of",
                        new Class<?>[] {Duration.class},
                        Duration.ofNanos(1)));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.TimeoutConfig",
                        "of",
                        new Class<?>[] {Duration.class},
                        Duration.ofSeconds(Long.MAX_VALUE)));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(RETRY_BACKOFF, "fixed", new Class<?>[] {long.class}, -1L));
        assertInvocationThrows(
                NullPointerException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "custom",
                        new Class<?>[] {load("dev.vertique.resilience.BackoffStrategy")},
                        (Object) null));

        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        -1L,
                        2.0,
                        1_000L,
                        0L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        1L,
                        2.0,
                        -1L,
                        0L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        1L,
                        2.0,
                        1_000L,
                        -1L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        1L,
                        0.99,
                        1_000L,
                        0L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        1L,
                        Double.NaN,
                        1_000L,
                        0L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        1L,
                        Double.POSITIVE_INFINITY,
                        1_000L,
                        0L));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        RETRY_BACKOFF,
                        "exponential",
                        new Class<?>[] {long.class, double.class, long.class, long.class},
                        1L,
                        Double.NEGATIVE_INFINITY,
                        1_000L,
                        0L));

        final Object negativeRetryBuilder =
                invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(IllegalArgumentException.class, () -> {
            invoke(negativeRetryBuilder, "maxRetries", new Class<?>[] {int.class}, -1);
            build(negativeRetryBuilder);
        });

        final Object overRetryBuilder = invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(IllegalArgumentException.class, () -> {
            invoke(overRetryBuilder, "maxRetries", new Class<?>[] {int.class}, 101);
            build(overRetryBuilder);
        });
        final Object nullBackoffRetryBuilder =
                invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(NullPointerException.class, () -> {
            invoke(nullBackoffRetryBuilder, "backoff", new Class<?>[] {load(RETRY_BACKOFF)}, (Object) null);
            build(nullBackoffRetryBuilder);
        });
        final Object nullRetryOnEntryBuilder =
                invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        Set<Class<? extends Throwable>> nullEntryTypes = new HashSet<>();
        nullEntryTypes.add(null);
        assertInvocationThrows(NullPointerException.class, () -> {
            invoke(nullRetryOnEntryBuilder, "retryOn", new Class<?>[] {Set.class}, nullEntryTypes);
            build(nullRetryOnEntryBuilder);
        });
        final Object nullAbortOnEntryBuilder =
                invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(NullPointerException.class, () -> {
            invoke(nullAbortOnEntryBuilder, "abortOn", new Class<?>[] {Set.class}, nullEntryTypes);
            build(nullAbortOnEntryBuilder);
        });

        final Object zeroFailureCircuitBuilder =
                invokeStatic("dev.vertique.resilience.CircuitBreakerConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(IllegalArgumentException.class, () -> {
            invoke(zeroFailureCircuitBuilder, "maxFailures", new Class<?>[] {int.class}, 0);
            build(zeroFailureCircuitBuilder);
        });
        final Object zeroResetCircuitBuilder =
                invokeStatic("dev.vertique.resilience.CircuitBreakerConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(IllegalArgumentException.class, () -> {
            invoke(zeroResetCircuitBuilder, "resetTimeoutMs", new Class<?>[] {long.class}, 0L);
            build(zeroResetCircuitBuilder);
        });
        final Object negativeFailureCircuitBuilder =
                invokeStatic("dev.vertique.resilience.CircuitBreakerConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(IllegalArgumentException.class, () -> {
            invoke(negativeFailureCircuitBuilder, "maxFailures", new Class<?>[] {int.class}, -1);
            build(negativeFailureCircuitBuilder);
        });
        final Object negativeResetCircuitBuilder =
                invokeStatic("dev.vertique.resilience.CircuitBreakerConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(IllegalArgumentException.class, () -> {
            invoke(negativeResetCircuitBuilder, "resetTimeoutMs", new Class<?>[] {long.class}, -1L);
            build(negativeResetCircuitBuilder);
        });

        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic("dev.vertique.resilience.BulkheadConfig", "reject", new Class<?>[] {int.class}, 0));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic("dev.vertique.resilience.BulkheadConfig", "reject", new Class<?>[] {int.class}, -1));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        0,
                        1,
                        ONE_SECOND));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        -1,
                        1,
                        ONE_SECOND));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        0,
                        ONE_SECOND));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        -1,
                        ONE_SECOND));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        1_025,
                        ONE_SECOND));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        1,
                        Duration.ZERO));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        1,
                        Duration.ofMillis(-1)));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        1,
                        Duration.ofMillis(60_001)));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        1,
                        Duration.ofNanos(1)));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> invokeStatic(
                        "dev.vertique.resilience.BulkheadConfig",
                        "queue",
                        new Class<?>[] {int.class, int.class, Duration.class},
                        1,
                        1,
                        Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    @DisplayName("maxRetries accepts exactly zero and one hundred and rejects both adjacent values")
    void acceptsRetryBoundariesAndRejectsAdjacentValues() {
        Object zero = invoke(
                invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]),
                "maxRetries",
                new Class<?>[] {int.class},
                0);
        assertEquals(0, invoke(invoke(zero, "build", new Class<?>[0]), "maxRetries", new Class<?>[0]));

        Object hundred = invoke(
                invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]),
                "maxRetries",
                new Class<?>[] {int.class},
                100);
        assertEquals(100, invoke(invoke(hundred, "build", new Class<?>[0]), "maxRetries", new Class<?>[0]));

        Object negative = invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(
                IllegalArgumentException.class, () -> invoke(negative, "maxRetries", new Class<?>[] {int.class}, -1));
        Object over = invokeStatic("dev.vertique.resilience.RetryConfig", "builder", new Class<?>[0]);
        assertInvocationThrows(
                IllegalArgumentException.class, () -> invoke(over, "maxRetries", new Class<?>[] {int.class}, 101));
    }

    @Test
    @DisplayName("disabled tri-state overrides reject every sibling value")
    void rejectsDisabledOverridesWithSiblingValues() {
        assertInvocationThrows(
                IllegalArgumentException.class, () -> timeoutOverride(Optional.of(false), OptionalLong.of(1L)));

        for (Object[] sibling : retrySiblingValues()) {
            assertInvocationThrows(
                    IllegalArgumentException.class,
                    () -> retryOverride(
                            Optional.of(false),
                            (OptionalInt) sibling[0],
                            (Optional<?>) sibling[1],
                            (Optional<?>) sibling[2],
                            (Optional<?>) sibling[3],
                            (Optional<?>) sibling[4]));
        }

        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> circuitBreakerOverride(Optional.of(false), OptionalInt.of(1), OptionalLong.empty()));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> circuitBreakerOverride(Optional.of(false), OptionalInt.empty(), OptionalLong.of(1L)));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> bulkheadOverride(Optional.of(false), Optional.of(bulkheadReject(1))));
    }

    @Test
    @DisplayName("custom and scalar backoff fields are mutually exclusive")
    void rejectsEveryCustomAndScalarBackoffCombination() {
        Object custom = backoffProxy((proxy, method, args) -> 0L);
        Optional<?> customValue = Optional.of(custom);
        OptionalLong emptyLong = OptionalLong.empty();
        Optional<?> emptyDouble = Optional.empty();

        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> backoffOverride(customValue, OptionalLong.of(1L), emptyDouble, emptyLong, emptyLong));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> backoffOverride(customValue, emptyLong, Optional.of(2.0), emptyLong, emptyLong));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> backoffOverride(customValue, emptyLong, emptyDouble, OptionalLong.of(1L), emptyLong));
        assertInvocationThrows(
                IllegalArgumentException.class,
                () -> backoffOverride(customValue, emptyLong, emptyDouble, emptyLong, OptionalLong.of(1L)));
    }

    @Test
    @DisplayName("enabled tri-state concerns and incomplete scalar backoff fail during resolution")
    void rejectsEnabledIncompleteAndUninheritablePolicies(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        for (Object override : List.of(
                timeoutOverride(Optional.of(true), OptionalLong.empty()),
                retryOverride(
                        Optional.of(true),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                circuitBreakerOverride(Optional.of(true), OptionalInt.empty(), OptionalLong.empty()),
                bulkheadOverride(Optional.of(true), Optional.empty()))) {
            Object overrides = aggregateOverrides(override);
            assertInvocationThrows(ResiliencePolicyException.class, () -> resolve(overrides, defaultsNone()));
        }

        Object partialBackoff = backoffOverride(
                Optional.empty(), OptionalLong.of(1L), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
        Object retry = retryOverride(
                Optional.of(true),
                OptionalInt.of(3),
                Optional.of(partialBackoff),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertInvocationThrows(
                ResiliencePolicyException.class, () -> resolve(aggregateOverrides(retry), defaultsNone()));
    }

    @Test
    @DisplayName("retry-eligibility callback failures are cause-free and metadata-only")
    void isolatesRetryEligibilityCallbackFailure(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Object callback = retryPolicyProxy((proxy, method, args) -> {
            throw new CallbackFailure();
        });
        Object retryBuilder = retryBuilder(resilience, "callback-eligibility");
        invoke(retryBuilder, "retryOn", new Class<?>[] {Set.class}, Set.of());
        invoke(retryBuilder, "fallbackPolicy", new Class<?>[] {load(RETRY_POLICY)}, callback);
        Object retry = build(retryBuilder);
        AttemptFailure attemptFailure = new AttemptFailure();
        AtomicReference<Future<?>> resultReference = new AtomicReference<>();
        String capturedLogs = captureDefaultLogs(() -> {
            Future<?> result = executeRetry(retry, () -> Future.failedFuture(attemptFailure));
            resultReference.set(result);
            awaitFailure(result);
        });
        Future<?> result = resultReference.get();
        ResiliencePolicyException policyFailure = assertInstanceOfPolicyFailure(result);
        assertEquals(
                "dev.vertique.resilience.ResiliencePolicyValidationTest$CallbackFailure",
                policyFailure.callbackExceptionClass().orElseThrow());
        assertEquals(
                "dev.vertique.resilience.ResiliencePolicyValidationTest$AttemptFailure",
                policyFailure.attemptExceptionClass().orElseThrow());
        assertEquals(
                PolicyCallbackKind.RETRY_ELIGIBILITY,
                policyFailure.callbackKind().orElseThrow());
        assertCauseFreeAndRedacted(policyFailure, capturedLogs);
    }

    @Test
    @DisplayName("backoff callback failures are cause-free and metadata-only")
    void isolatesBackoffCallbackFailure(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Object eligibility = retryPolicyProxy((proxy, method, args) -> true);
        Object callback = backoffProxy((proxy, method, args) -> {
            throw new CallbackFailure();
        });
        Object retryBuilder = retryBuilder(resilience, "callback-backoff");
        invoke(retryBuilder, "fallbackPolicy", new Class<?>[] {load(RETRY_POLICY)}, eligibility);
        invoke(retryBuilder, "backoff", new Class<?>[] {load(RETRY_BACKOFF)}, retryBackoffCustom(callback));
        Object retry = build(retryBuilder);
        AttemptFailure attemptFailure = new AttemptFailure();
        AtomicReference<Future<?>> resultReference = new AtomicReference<>();
        String capturedLogs = captureDefaultLogs(() -> {
            Future<?> result = executeRetry(retry, () -> Future.failedFuture(attemptFailure));
            resultReference.set(result);
            awaitFailure(result);
        });
        Future<?> result = resultReference.get();
        ResiliencePolicyException policyFailure = assertInstanceOfPolicyFailure(result);
        assertEquals(
                "dev.vertique.resilience.ResiliencePolicyValidationTest$CallbackFailure",
                policyFailure.callbackExceptionClass().orElseThrow());
        assertEquals(
                "dev.vertique.resilience.ResiliencePolicyValidationTest$AttemptFailure",
                policyFailure.attemptExceptionClass().orElseThrow());
        assertEquals(PolicyCallbackKind.BACKOFF, policyFailure.callbackKind().orElseThrow());
        assertCauseFreeAndRedacted(policyFailure, capturedLogs);
    }

    @Test
    @DisplayName("a negative custom delay terminates retrying and returns the last attempt failure")
    void negativeCustomDelayStopsRetrying(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        AtomicInteger attempts = new AtomicInteger();
        Object eligibility = retryPolicyProxy((proxy, method, args) -> true);
        Object negativeDelay = backoffProxy((proxy, method, args) -> -1L);
        Object retryBuilder = retryBuilder(resilience, "negative-delay");
        invoke(retryBuilder, "maxRetries", new Class<?>[] {int.class}, 100);
        invoke(retryBuilder, "fallbackPolicy", new Class<?>[] {load(RETRY_POLICY)}, eligibility);
        invoke(retryBuilder, "backoff", new Class<?>[] {load(RETRY_BACKOFF)}, retryBackoffCustom(negativeDelay));
        Object retry = build(retryBuilder);
        AttemptFailure attemptFailure = new AttemptFailure();

        Future<?> result = executeRetry(retry, () -> {
            attempts.incrementAndGet();
            return Future.failedFuture(attemptFailure);
        });

        awaitFailure(result);
        assertSame(attemptFailure, result.cause());
        assertEquals(1, attempts.get(), "negative custom delay is the legacy stop-retrying sentinel");
    }

    @Test
    @DisplayName("fatal supplier errors fail the future identically and are rethrown on the selected context")
    void rethrowsFatalErrorsAfterCleanup(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Object retryBuilder = retryBuilder(resilience, "fatal-supplier");
        invoke(retryBuilder, "maxRetries", new Class<?>[] {int.class}, 3);
        Object retry = build(retryBuilder);
        FatalFailure fatal = new FatalFailure();
        AtomicReference<Future<?>> result = new AtomicReference<>();
        AtomicReference<Throwable> observedByContextHandler = new AtomicReference<>();
        CountDownLatch executionSubmitted = new CountDownLatch(1);
        CountDownLatch contextHandlerCalled = new CountDownLatch(1);

        vertx.runOnContext(ignored -> {
            Context context = Vertx.currentContext();
            context.exceptionHandler(error -> {
                observedByContextHandler.set(error);
                contextHandlerCalled.countDown();
            });
            result.set(executeRetry(retry, () -> {
                throw fatal;
            }));
            executionSubmitted.countDown();
        });

        assertTrue(executionSubmitted.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        awaitFailure(result.get());
        assertSame(fatal, result.get().cause());
        assertTrue(contextHandlerCalled.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertSame(fatal, observedByContextHandler.get());
    }

    @Test
    @DisplayName("retry callbacks remain synchronous, context-bound, bounded, and I/O-free")
    void evaluatesCallbacksSynchronouslyWithoutIo(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        AtomicReference<Context> supplierContext = new AtomicReference<>();
        AtomicReference<Context> eligibilityContext = new AtomicReference<>();
        AtomicReference<Context> backoffContext = new AtomicReference<>();
        AtomicInteger eligibilityCalls = new AtomicInteger();
        AtomicInteger backoffCalls = new AtomicInteger();
        Object eligibility = retryPolicyProxy((proxy, method, args) -> {
            eligibilityContext.set(Vertx.currentContext());
            eligibilityCalls.incrementAndGet();
            return true;
        });
        Object backoff = backoffProxy((proxy, method, args) -> {
            backoffContext.set(Vertx.currentContext());
            backoffCalls.incrementAndGet();
            return 0L;
        });
        Object retryBuilder = retryBuilder(resilience, "deterministic-callbacks");
        invoke(retryBuilder, "maxRetries", new Class<?>[] {int.class}, 1);
        invoke(retryBuilder, "fallbackPolicy", new Class<?>[] {load(RETRY_POLICY)}, eligibility);
        invoke(retryBuilder, "backoff", new Class<?>[] {load(RETRY_BACKOFF)}, retryBackoffCustom(backoff));
        Object retry = build(retryBuilder);
        AtomicInteger attempts = new AtomicInteger();

        Future<?> result = executeRetry(retry, () -> {
            supplierContext.set(Vertx.currentContext());
            if (attempts.incrementAndGet() == 1) {
                return Future.failedFuture(new AttemptFailure());
            }
            return Future.succeededFuture("ok");
        });

        assertEquals("ok", await(result));
        assertSame(supplierContext.get(), eligibilityContext.get());
        assertSame(supplierContext.get(), backoffContext.get());
        assertEquals(1, eligibilityCalls.get());
        assertEquals(1, backoffCalls.get());
    }

    private Object[][] retrySiblingValues() {
        Object custom = backoffProxy((proxy, method, args) -> 0L);
        Object policy = retryPolicyProxy((proxy, method, args) -> true);
        Set<Class<? extends Throwable>> types = Set.of(RuntimeException.class);
        return new Object[][] {
            {OptionalInt.of(1), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()},
            {
                OptionalInt.empty(),
                Optional.of(retryBackoffCustom(custom)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            },
            {OptionalInt.empty(), Optional.empty(), Optional.of(types), Optional.empty(), Optional.empty()},
            {OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.of(types), Optional.empty()},
            {OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(policy)}
        };
    }

    private Object retryBuilder(Resilience owner, String operationName) {
        return invokeStatic(
                "dev.vertique.resilience.Retry",
                "builder",
                new Class<?>[] {Resilience.class, String.class},
                owner,
                operationName);
    }

    private Object retryBackoffCustom(Object delegate) {
        return invokeStatic(
                RETRY_BACKOFF, "custom", new Class<?>[] {load("dev.vertique.resilience.BackoffStrategy")}, delegate);
    }

    private Object timeoutOverride(Optional<Boolean> enabled, OptionalLong timeoutMs) {
        return construct(
                "dev.vertique.resilience.TimeoutOverride",
                new Class<?>[] {Optional.class, OptionalLong.class},
                enabled,
                timeoutMs);
    }

    private Object retryOverride(
            Optional<Boolean> enabled,
            OptionalInt maxRetries,
            Optional<?> backoff,
            Optional<?> retryOn,
            Optional<?> abortOn,
            Optional<?> fallbackPolicy) {
        return construct(
                "dev.vertique.resilience.RetryOverride",
                new Class<?>[] {
                    Optional.class, OptionalInt.class, Optional.class, Optional.class, Optional.class, Optional.class
                },
                enabled,
                maxRetries,
                backoff,
                retryOn,
                abortOn,
                fallbackPolicy);
    }

    private Object backoffOverride(
            Optional<?> custom,
            OptionalLong initialDelayMs,
            Optional<?> multiplier,
            OptionalLong maxDelayMs,
            OptionalLong maxJitterMs) {
        return construct(
                "dev.vertique.resilience.BackoffOverride",
                new Class<?>[] {
                    Optional.class, OptionalLong.class, Optional.class, OptionalLong.class, OptionalLong.class
                },
                custom,
                initialDelayMs,
                multiplier,
                maxDelayMs,
                maxJitterMs);
    }

    private Object circuitBreakerOverride(
            Optional<Boolean> enabled, OptionalInt maxFailures, OptionalLong resetTimeoutMs) {
        return construct(
                "dev.vertique.resilience.CircuitBreakerOverride",
                new Class<?>[] {Optional.class, OptionalInt.class, OptionalLong.class},
                enabled,
                maxFailures,
                resetTimeoutMs);
    }

    private Object bulkheadOverride(Optional<Boolean> enabled, Optional<?> config) {
        return construct(
                "dev.vertique.resilience.BulkheadOverride",
                new Class<?>[] {Optional.class, Optional.class},
                enabled,
                config);
    }

    private Object aggregateOverrides(Object concernOverride) {
        Class<?> type = concernOverride.getClass();
        Optional<?> timeout =
                type.getSimpleName().equals("TimeoutOverride") ? Optional.of(concernOverride) : Optional.empty();
        Optional<?> retry =
                type.getSimpleName().equals("RetryOverride") ? Optional.of(concernOverride) : Optional.empty();
        Optional<?> circuitBreaker =
                type.getSimpleName().equals("CircuitBreakerOverride") ? Optional.of(concernOverride) : Optional.empty();
        Optional<?> bulkhead =
                type.getSimpleName().equals("BulkheadOverride") ? Optional.of(concernOverride) : Optional.empty();
        return construct(
                "dev.vertique.resilience.ResiliencePolicyOverrides",
                new Class<?>[] {Optional.class, Optional.class, Optional.class, Optional.class},
                timeout,
                retry,
                circuitBreaker,
                bulkhead);
    }

    private Object defaultsNone() {
        return invokeStatic("dev.vertique.resilience.ResilienceDefaults", "none", new Class<?>[0]);
    }

    private Object resolve(Object overrides, Object defaults) {
        Object resolver = invoke(resilience, "policyResolver", new Class<?>[0]);
        Object annotations = getStaticField(RESILIENCE_ANNOTATIONS, "NONE");
        return invoke(
                resolver,
                "resolve",
                new Class<?>[] {
                    load(RESILIENCE_ANNOTATIONS),
                    load("dev.vertique.resilience.ResiliencePolicyOverrides"),
                    load("dev.vertique.resilience.ResilienceDefaults")
                },
                annotations,
                overrides,
                defaults);
    }

    private Object bulkheadReject(int maxConcurrentCalls) {
        return invokeStatic(
                "dev.vertique.resilience.BulkheadConfig", "reject", new Class<?>[] {int.class}, maxConcurrentCalls);
    }

    private Object build(Object builder) {
        return invoke(builder, "build", new Class<?>[0]);
    }

    private Object invoke(Object receiver, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = receiver.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw propagate(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + receiver.getClass().getName() + "." + methodName, e);
        }
    }

    private Object invokeStatic(String typeName, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = load(typeName).getMethod(methodName, parameterTypes);
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw propagate(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + typeName + "." + methodName, e);
        }
    }

    private Object construct(String typeName, Class<?>[] parameterTypes, Object... args) {
        try {
            return load(typeName).getConstructor(parameterTypes).newInstance(args);
        } catch (InvocationTargetException e) {
            throw propagate(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct " + typeName, e);
        }
    }

    private Object getStaticField(String typeName, String fieldName) {
        try {
            return load(typeName).getField(fieldName).get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + typeName + "." + fieldName, e);
        }
    }

    private Class<?> load(String typeName) {
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Required T002 type is absent: " + typeName, e);
        }
    }

    private Object retryPolicyProxy(InvocationHandler handler) {
        return Proxy.newProxyInstance(
                load(RETRY_POLICY).getClassLoader(), new Class<?>[] {load(RETRY_POLICY)}, handler);
    }

    private Object backoffProxy(InvocationHandler handler) {
        return Proxy.newProxyInstance(
                load("dev.vertique.resilience.BackoffStrategy").getClassLoader(),
                new Class<?>[] {load("dev.vertique.resilience.BackoffStrategy")},
                handler);
    }

    private void assertInvocationThrows(Class<? extends Throwable> expectedType, Executable action) {
        Throwable failure = assertThrows(Throwable.class, action);
        if (failure instanceof AssertionError assertionFailure
                && assertionFailure.getCause() != null
                && assertionFailure.getMessage() != null
                && assertionFailure.getMessage().startsWith("Unable to")) {
            throw assertionFailure;
        }
        assertTrue(
                expectedType.isInstance(failure), () -> "expected " + expectedType.getName() + " but got " + failure);
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Unexpected checked reflection failure", failure);
    }

    private Future<?> executeRetry(Object retry, java.util.function.Supplier<Future<String>> operation) {
        return (Future<?>) invoke(retry, "execute", new Class<?>[] {java.util.function.Supplier.class}, operation);
    }

    private static void awaitFailure(Future<?> future) throws Exception {
        try {
            await(future);
            fail("expected failed future");
        } catch (ExecutionException expected) {
            // The typed cause is asserted by the caller.
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static ResiliencePolicyException assertInstanceOfPolicyFailure(Future<?> result) {
        assertTrue(result.failed(), "callback failure must fail the logical execution");
        assertTrue(result.cause() instanceof ResiliencePolicyException);
        return (ResiliencePolicyException) result.cause();
    }

    private static void assertCauseFreeAndRedacted(ResiliencePolicyException failure, String capturedLogs) {
        assertEquals(null, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains(CALLBACK_SECRET));
        assertFalse(failure.getMessage().contains(ATTEMPT_SECRET));
        assertFalse(failure.toString().contains(CALLBACK_SECRET));
        assertFalse(failure.toString().contains(ATTEMPT_SECRET));
        assertFalse(capturedLogs.contains(CALLBACK_SECRET));
        assertFalse(capturedLogs.contains(ATTEMPT_SECRET));
    }

    private static String captureDefaultLogs(Executable action) throws Exception {
        Logger root = Logger.getLogger("");
        CapturingHandler handler = new CapturingHandler();
        root.addHandler(handler);
        Level previousLevel = root.getLevel();
        root.setLevel(Level.ALL);
        try {
            action.execute();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new Exception(failure);
        } finally {
            root.setLevel(previousLevel);
            root.removeHandler(handler);
        }
        return handler.text();
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            StringWriter rendered = new StringWriter();
            PrintWriter writer = new PrintWriter(rendered);
            writer.println(record.getMessage());
            if (record.getThrown() != null) {
                record.getThrown().printStackTrace(writer);
            }
            writer.flush();
            records.add(rendered.toString());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String text() {
            return String.join("\n", records);
        }
    }

    static final class CallbackFailure extends RuntimeException {
        CallbackFailure() {
            super(CALLBACK_SECRET);
        }
    }

    static final class AttemptFailure extends RuntimeException {
        AttemptFailure() {
            super(ATTEMPT_SECRET);
        }
    }

    static final class FatalFailure extends VirtualMachineError {
        FatalFailure() {
            super("fatal-secret-value");
        }
    }
}
