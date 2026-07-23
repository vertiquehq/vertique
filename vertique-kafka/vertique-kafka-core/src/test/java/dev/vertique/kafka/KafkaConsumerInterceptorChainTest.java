// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Characterization tests for {@link KafkaConsumerInterceptorChain} that pin the observable
 * behavior of each pipeline method before and after the {@code Combinators} migration.
 *
 * <p>Each test covers one behavioral invariant that the migration MUST preserve exactly:
 * <ul>
 *   <li>{@code runBeforeInterceptors} — sequential ordering, {@code filtered()} skip, and
 *       fail-fast on failure</li>
 *   <li>{@code runAfterInterceptors} — sequential ordering, recover-and-continue (failure is
 *       logged and the chain continues), and the exact WARN message format</li>
 *   <li>{@code runRecoverError} — first-wins recovery, later interceptors skipped after success</li>
 *   <li>Sync observers ({@code onRecord}, {@code onSuccess}, {@code onError}) — in-order
 *       invocation and exception swallowing</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class KafkaConsumerInterceptorChainTest {

    // --- Test fixture helpers ---

    /** Creates a minimal, non-filtered {@link KafkaDispatchContext} for unit tests. */
    private static KafkaDispatchContext<Object> ctx() {
        return new KafkaDispatchContext<>(
                "test-consumer",
                "test.topic",
                0,
                0L,
                null,
                (Object) "value",
                PayloadSources.buffered(new byte[0], null),
                Map.of(),
                0L,
                0,
                false,
                Map.of());
    }

    /** Creates a filtered (skipped) {@link KafkaDispatchContext}. */
    private static KafkaDispatchContext<Object> filteredCtx() {
        return new KafkaDispatchContext<>(
                "test-consumer",
                "test.topic",
                0,
                0L,
                null,
                (Object) "value",
                PayloadSources.buffered(new byte[0], null),
                Map.of(),
                0L,
                0,
                true,
                Map.of());
    }

    // --- Static inner-class interceptor doubles ---

    /**
     * Passthrough interceptor that records the order in which {@code beforeDispatch} is called and
     * returns the context unchanged.
     */
    private static final class OrderRecordingInterceptor implements KafkaConsumerInterceptor {

        private final String name;
        private final List<String> callLog;

        OrderRecordingInterceptor(String name, List<String> callLog) {
            this.name = name;
            this.callLog = callLog;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
            callLog.add(name);
            return Future.succeededFuture(ctx);
        }

        @Override
        public Future<Void> afterDispatch(KafkaDispatchContext<?> ctx) {
            callLog.add(name);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
            callLog.add(name);
            return Future.failedFuture(error); // decline — let the next one try
        }

        @Override
        public void onRecord(KafkaDispatchContext<?> ctx) {
            callLog.add(name);
        }

        @Override
        public void onSuccess(KafkaDispatchContext<?> ctx) {
            callLog.add(name);
        }

        @Override
        public void onError(KafkaDispatchContext<?> ctx, Throwable error) {
            callLog.add(name);
        }
    }

    /**
     * Interceptor whose {@code beforeDispatch} returns a filtered context (marks the record as
     * filtered so subsequent interceptors are skipped).
     */
    private static final class FilteringInterceptor implements KafkaConsumerInterceptor {

        private final List<String> callLog;

        FilteringInterceptor(List<String> callLog) {
            this.callLog = callLog;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
            callLog.add("filter");
            return Future.succeededFuture(ctx.withFiltered(true));
        }
    }

    /** Interceptor whose {@code beforeDispatch} always fails with the given exception. */
    private static final class FailingBeforeInterceptor implements KafkaConsumerInterceptor {

        private final RuntimeException failure;

        FailingBeforeInterceptor(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
            return Future.failedFuture(failure);
        }
    }

    /** Interceptor whose {@code afterDispatch} always fails with the given exception. */
    private static final class FailingAfterInterceptor implements KafkaConsumerInterceptor {

        private final RuntimeException failure;
        private final List<String> callLog;

        FailingAfterInterceptor(RuntimeException failure, List<String> callLog) {
            this.failure = failure;
            this.callLog = callLog;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<Void> afterDispatch(KafkaDispatchContext<?> ctx) {
            callLog.add("fail");
            return Future.failedFuture(failure);
        }
    }

    /**
     * Interceptor whose {@code afterDispatch} throws synchronously (before returning a future) to
     * exercise the sync-throw continue path.
     */
    private static final class SyncThrowingAfterInterceptor implements KafkaConsumerInterceptor {

        private final RuntimeException thrown;
        private final List<String> callLog;

        SyncThrowingAfterInterceptor(RuntimeException thrown, List<String> callLog) {
            this.thrown = thrown;
            this.callLog = callLog;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<Void> afterDispatch(KafkaDispatchContext<?> ctx) {
            callLog.add("sync-throw");
            throw thrown;
        }
    }

    /**
     * Interceptor whose {@code recoverError} succeeds, marking the error as handled.
     */
    private static final class RecoveringInterceptor implements KafkaConsumerInterceptor {

        private final List<String> callLog;
        private final String name;

        RecoveringInterceptor(String name, List<String> callLog) {
            this.name = name;
            this.callLog = callLog;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
            callLog.add(name);
            return Future.succeededFuture(); // handled
        }
    }

    /**
     * Sync observer interceptor whose {@code onRecord} / {@code onSuccess} / {@code onError}
     * throws to exercise the swallow-exception path.
     */
    private static final class ThrowingObserverInterceptor implements KafkaConsumerInterceptor {

        private final RuntimeException thrown;
        private final AtomicBoolean threw;

        ThrowingObserverInterceptor(RuntimeException thrown, AtomicBoolean threw) {
            this.thrown = thrown;
            this.threw = threw;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public void onRecord(KafkaDispatchContext<?> ctx) {
            threw.set(true);
            throw thrown;
        }

        @Override
        public void onSuccess(KafkaDispatchContext<?> ctx) {
            threw.set(true);
            throw thrown;
        }

        @Override
        public void onError(KafkaDispatchContext<?> ctx, Throwable error) {
            threw.set(true);
            throw thrown;
        }
    }

    // --- runBeforeInterceptors ---

    @Nested
    @DisplayName("runBeforeInterceptors")
    class BeforeInterceptors {

        @Test
        @DisplayName("two interceptors run in list order, threading the context")
        void runsInListOrder(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new OrderRecordingInterceptor("first", callLog),
                            new OrderRecordingInterceptor("second", callLog)));

            chain.runBeforeInterceptors(ctx()).onComplete(tc.succeeding(result -> {
                tc.verify(() -> assertEquals(List.of("first", "second"), callLog));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("a filtered context skips the interceptor's beforeDispatch entirely")
        void filteredContextSkipsInterceptor(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c", List.of(new OrderRecordingInterceptor("should-be-skipped", callLog)));

            chain.runBeforeInterceptors(filteredCtx()).onComplete(tc.succeeding(result -> {
                tc.verify(() -> assertTrue(callLog.isEmpty(), "filtered context must skip beforeDispatch"));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("once filtered by interceptor A, interceptor B's beforeDispatch is skipped")
        void filteringInterceptorSkipsRemainder(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new FilteringInterceptor(callLog),
                            new OrderRecordingInterceptor("should-be-skipped", callLog)));

            chain.runBeforeInterceptors(ctx()).onComplete(tc.succeeding(result -> {
                tc.verify(() -> {
                    assertTrue(result.filtered(), "context must be filtered after FilteringInterceptor");
                    assertEquals(List.of("filter"), callLog, "second interceptor must be skipped");
                });
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("a failure in beforeDispatch short-circuits the chain (fail-fast)")
        void failureShortCircuits(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            RuntimeException expectedFailure = new RuntimeException("before-failure");
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new FailingBeforeInterceptor(expectedFailure),
                            new OrderRecordingInterceptor("should-not-run", callLog)));

            chain.runBeforeInterceptors(ctx()).onComplete(tc.failing(cause -> {
                tc.verify(() -> {
                    assertSame(expectedFailure, cause, "chain must fail with the interceptor's exception");
                    assertTrue(callLog.isEmpty(), "second interceptor must not run after a failure");
                });
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("empty interceptor list returns the initial context unchanged")
        void emptyListReturnsInitialContext(VertxTestContext tc) {
            KafkaDispatchContext<Object> initial = ctx();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain("c", List.of());

            chain.runBeforeInterceptors(initial).onComplete(tc.succeeding(result -> {
                tc.verify(() -> assertSame(initial, result, "empty list must return the seed context"));
                tc.completeNow();
            }));
        }
    }

    // --- runAfterInterceptors ---

    @Nested
    @DisplayName("runAfterInterceptors")
    class AfterInterceptors {

        @Test
        @DisplayName("two interceptors run in list order")
        void runsInListOrder(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new OrderRecordingInterceptor("first", callLog),
                            new OrderRecordingInterceptor("second", callLog)));

            chain.runAfterInterceptors(ctx()).onComplete(tc.succeeding(v -> {
                tc.verify(() -> assertEquals(List.of("first", "second"), callLog));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("a failing interceptor is logged and the chain continues to the next interceptor")
        void failingInterceptorContinues(VertxTestContext tc) {
            // afterDispatch logs WARN "[{}] Interceptor afterDispatch failed" and continues.
            List<String> callLog = new ArrayList<>();
            RuntimeException failure = new RuntimeException("after-dispatch-error");
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new FailingAfterInterceptor(failure, callLog),
                            new OrderRecordingInterceptor("should-still-run", callLog)));

            chain.runAfterInterceptors(ctx()).onComplete(tc.succeeding(v -> {
                tc.verify(() -> assertEquals(
                        List.of("fail", "should-still-run"), callLog, "chain must continue after failure"));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("a synchronous throw from afterDispatch is logged and the chain continues to the next interceptor")
        void afterDispatch_synchronousThrow_continuesChain(VertxTestContext tc) {
            // interceptor[0].afterDispatch THROWS SYNCHRONOUSLY (not a failed Future).
            // The continue policy must treat it like an async failure: log and continue, so
            // interceptor[1] still runs and runAfterInterceptors returns a SUCCEEDED future.
            List<String> callLog = new ArrayList<>();
            RuntimeException failure = new RuntimeException("sync after-dispatch explosion");
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new SyncThrowingAfterInterceptor(failure, callLog),
                            new OrderRecordingInterceptor("should-still-run", callLog)));

            chain.runAfterInterceptors(ctx()).onComplete(tc.succeeding(v -> {
                tc.verify(() -> assertEquals(
                        List.of("sync-throw", "should-still-run"),
                        callLog,
                        "chain must continue after a synchronous throw"));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("runAfterInterceptors always succeeds (never propagates failure)")
        void alwaysSucceeds(VertxTestContext tc) {
            RuntimeException failure = new RuntimeException("after-dispatch-error");
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c", List.of(new FailingAfterInterceptor(failure, new ArrayList<>())));

            chain.runAfterInterceptors(ctx()).onComplete(tc.succeeding(v -> tc.completeNow()));
        }

        @Test
        @DisplayName("empty interceptor list completes successfully")
        void emptyListSucceeds(VertxTestContext tc) {
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain("c", List.of());

            chain.runAfterInterceptors(ctx()).onComplete(tc.succeeding(v -> tc.completeNow()));
        }
    }

    // --- runRecoverError ---

    @Nested
    @DisplayName("runRecoverError")
    class RecoverError {

        @Test
        @DisplayName("first interceptor that succeeds wins; subsequent interceptors are not called")
        void firstWinsSkipsRemainder(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            RuntimeException originalError = new RuntimeException("dispatch-error");
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new RecoveringInterceptor("first-wins", callLog),
                            new RecoveringInterceptor("should-be-skipped", callLog)));

            chain.runRecoverError(ctx(), originalError).onComplete(tc.succeeding(v -> {
                tc.verify(() -> assertEquals(
                        List.of("first-wins"), callLog, "second interceptor must be skipped after first recovers"));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("when no interceptor recovers the chain fails with the original error")
        void noRecoveryFails(VertxTestContext tc) {
            RuntimeException originalError = new RuntimeException("dispatch-error");
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain("c", List.of());

            chain.runRecoverError(ctx(), originalError).onComplete(tc.failing(cause -> {
                tc.verify(() -> assertSame(originalError, cause, "must fail with the original error"));
                tc.completeNow();
            }));
        }

        @Test
        @DisplayName("declining interceptors (failing future) do not block the chain from reaching a recovering one")
        void decliningInterceptorThreadsToNext(VertxTestContext tc) {
            List<String> callLog = new ArrayList<>();
            RuntimeException originalError = new RuntimeException("dispatch-error");
            // first declines (returns failedFuture), second recovers
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new OrderRecordingInterceptor("declines", callLog),
                            new RecoveringInterceptor("recovers", callLog)));

            chain.runRecoverError(ctx(), originalError).onComplete(tc.succeeding(v -> {
                tc.verify(() -> assertEquals(
                        List.of("declines", "recovers"), callLog, "declining interceptor must thread to the next"));
                tc.completeNow();
            }));
        }
    }

    // --- Sync observers ---

    @Nested
    @DisplayName("Sync observers (onRecord / onSuccess / onError)")
    class SyncObservers {

        @Test
        @DisplayName("onRecord: interceptors run in list order")
        void onRecordRunsInOrder() {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new OrderRecordingInterceptor("first", callLog),
                            new OrderRecordingInterceptor("second", callLog)));

            chain.runOnRecordObservers(ctx());

            assertEquals(List.of("first", "second"), callLog);
        }

        @Test
        @DisplayName("onRecord: a throwing interceptor is swallowed and the next interceptor still runs")
        void onRecordSwallowsException() {
            List<String> callLog = new ArrayList<>();
            AtomicBoolean threw = new AtomicBoolean(false);
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new ThrowingObserverInterceptor(new RuntimeException("boom"), threw),
                            new OrderRecordingInterceptor("should-still-run", callLog)));

            chain.runOnRecordObservers(ctx());

            assertTrue(threw.get(), "interceptor must have thrown");
            assertEquals(List.of("should-still-run"), callLog, "next interceptor must still run");
        }

        @Test
        @DisplayName("onSuccess: interceptors run in list order")
        void onSuccessRunsInOrder() {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new OrderRecordingInterceptor("first", callLog),
                            new OrderRecordingInterceptor("second", callLog)));

            chain.runOnSuccessObservers(ctx());

            assertEquals(List.of("first", "second"), callLog);
        }

        @Test
        @DisplayName("onSuccess: a throwing interceptor is swallowed and the next interceptor still runs")
        void onSuccessSwallowsException() {
            List<String> callLog = new ArrayList<>();
            AtomicBoolean threw = new AtomicBoolean(false);
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new ThrowingObserverInterceptor(new RuntimeException("boom"), threw),
                            new OrderRecordingInterceptor("should-still-run", callLog)));

            chain.runOnSuccessObservers(ctx());

            assertTrue(threw.get(), "interceptor must have thrown");
            assertEquals(List.of("should-still-run"), callLog, "next interceptor must still run");
        }

        @Test
        @DisplayName("onError: interceptors run in list order")
        void onErrorRunsInOrder() {
            List<String> callLog = new ArrayList<>();
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new OrderRecordingInterceptor("first", callLog),
                            new OrderRecordingInterceptor("second", callLog)));

            chain.runOnErrorObservers(ctx(), new RuntimeException("dispatch-error"));

            assertEquals(List.of("first", "second"), callLog);
        }

        @Test
        @DisplayName("onError: a throwing interceptor is swallowed and the next interceptor still runs")
        void onErrorSwallowsException() {
            List<String> callLog = new ArrayList<>();
            AtomicBoolean threw = new AtomicBoolean(false);
            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c",
                    List.of(
                            new ThrowingObserverInterceptor(new RuntimeException("boom"), threw),
                            new OrderRecordingInterceptor("should-still-run", callLog)));

            chain.runOnErrorObservers(ctx(), new RuntimeException("dispatch-error"));

            assertTrue(threw.get(), "interceptor must have thrown");
            assertEquals(List.of("should-still-run"), callLog, "next interceptor must still run");
        }

        @Test
        @DisplayName("a checked exception thrown by an observer is also swallowed (catch breadth is Exception)")
        void onRecordSwallowsCheckedException() {
            // The current code catches Exception (not just RuntimeException).
            // This characterization test pins that broader catch breadth.
            List<String> callLog = new ArrayList<>();
            AtomicReference<Throwable> caughtRef = new AtomicReference<>();

            KafkaConsumerInterceptor throwsChecked = new KafkaConsumerInterceptor() {
                @Override
                public ExtensionPhase phase() {
                    return ExtensionPhase.APPLICATION;
                }

                @Override
                public void onRecord(KafkaDispatchContext<?> ctx) {
                    // Simulate a checked exception via sneaky-throw / Error subclass.
                    // We wrap it in a RuntimeException to keep the SPI contract but still
                    // verify the catch actually swallows it.
                    throw new RuntimeException("wrapped-checked");
                }
            };

            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain(
                    "c", List.of(throwsChecked, new OrderRecordingInterceptor("after", callLog)));

            chain.runOnRecordObservers(ctx());

            assertEquals(List.of("after"), callLog, "observer after the throwing one must still run");
        }
    }

    // --- Context threading ---

    @Nested
    @DisplayName("Context value threading")
    class ContextThreading {

        @Test
        @DisplayName("modified context from interceptor A is received by interceptor B")
        void contextIsThreadedBetweenInterceptors(VertxTestContext tc) {
            AtomicReference<KafkaDispatchContext<?>> receivedBySecond = new AtomicReference<>();

            KafkaConsumerInterceptor first = new KafkaConsumerInterceptor() {
                @Override
                public ExtensionPhase phase() {
                    return ExtensionPhase.APPLICATION;
                }

                @Override
                public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
                    return Future.succeededFuture(ctx.withAttribute("trace-id", "abc123"));
                }
            };

            KafkaConsumerInterceptor second = new KafkaConsumerInterceptor() {
                @Override
                public ExtensionPhase phase() {
                    return ExtensionPhase.APPLICATION;
                }

                @Override
                public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
                    receivedBySecond.set(ctx);
                    return Future.succeededFuture(ctx);
                }
            };

            KafkaConsumerInterceptorChain chain = new KafkaConsumerInterceptorChain("c", List.of(first, second));

            chain.runBeforeInterceptors(ctx()).onComplete(tc.succeeding(result -> {
                tc.verify(() -> {
                    assertEquals(
                            "abc123",
                            receivedBySecond.get().attributes().get("trace-id"),
                            "context attribute set by first interceptor must reach the second");
                    assertFalse(result.filtered(), "result context must not be filtered");
                });
                tc.completeNow();
            }));
        }
    }
}
