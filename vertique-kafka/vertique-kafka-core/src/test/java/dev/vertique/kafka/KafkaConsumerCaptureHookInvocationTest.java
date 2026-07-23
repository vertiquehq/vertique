// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.kafka.interceptor.KafkaConsumerCaptureHook;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
import dev.vertique.kafka.interceptor.KafkaRawRecordDisposition;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.services.ServiceRequestSender;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KafkaConsumerCaptureHook} contract and the hook-invocation logic
 * in the terminal-outcome dispatch pipeline.
 *
 * <p>Covered:
 * <ul>
 *   <li>Default no-op implementation compiles and does not throw (both hook methods)</li>
 *   <li>Implements {@link OrderedExtension} (phase/priority/orderKey)</li>
 *   <li>Sorting multiple hooks by {@link OrderedExtension#comparator()}</li>
 *   <li>The hook is invoked with the correct outcome for each error strategy, and
 *       side effects (commit / seek / resume) remain unchanged (verified via
 *       {@link KafkaTerminalOutcomeTest.CapturingConsumerControl})</li>
 *   <li>A throwing hook does not propagate the exception to the caller</li>
 *   <li>{@link KafkaConsumerCaptureHook#onPreDispatchTerminalOutcome} is invoked with
 *       {@link dev.vertique.kafka.interceptor.KafkaTerminalOutcome#SKIP} for pre-filter and
 *       no-route exits, and with the outcome from the error handler for deserialization failures</li>
 *   <li>A record filtered by an interceptor's {@code beforeDispatch} (post-deserialization) notifies
 *       hooks exactly once via {@link KafkaConsumerCaptureHook#onTerminalOutcome} with
 *       {@link KafkaTerminalOutcome#SKIP}, exercised against the real {@code KafkaConsumerVerticle}
 *       dispatch pipeline (not just the mirrored invocation helpers above)</li>
 *   <li>When the error handler's own future fails (rather than the dispatch/deserialization it is
 *       handling), hooks are still notified exactly once, with
 *       {@link KafkaTerminalOutcome#ERROR_HANDLER_FAILED}, on both the pre-dispatch and
 *       post-dispatch notification variants</li>
 * </ul>
 *
 * <p>Verticle-level SUCCESS and RECOVERED paths are tested in
 * {@link KafkaInterceptorIT} (integration level) and in the error-handler unit tests.
 * The hook-invocation guarding (try/catch per hook) is exercised by the
 * "throwing hook safety" test below.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class KafkaConsumerCaptureHookInvocationTest {

    // --- Recording hook ---

    /**
     * Capture hook that records all (context, outcome) pairs observed via
     * {@link #onTerminalOutcome}.
     */
    static final class RecordingCaptureHook implements KafkaConsumerCaptureHook {

        record Observed(KafkaDispatchContext<?> ctx, KafkaTerminalOutcome outcome) {}

        final List<Observed> observations = new CopyOnWriteArrayList<>();
        private final int hookPriority;

        RecordingCaptureHook(int priority) {
            this.hookPriority = priority;
        }

        @Override
        public int priority() {
            return hookPriority;
        }

        @Override
        public void onTerminalOutcome(KafkaDispatchContext<?> ctx, KafkaTerminalOutcome outcome) {
            observations.add(new Observed(ctx, outcome));
        }
    }

    /**
     * Capture hook that records all (disposition, outcome) pairs observed via
     * {@link KafkaConsumerCaptureHook#onPreDispatchTerminalOutcome}.
     */
    static final class RecordingPreDispatchHook implements KafkaConsumerCaptureHook {

        record PreDispatchObserved(KafkaRawRecordDisposition disposition, KafkaTerminalOutcome outcome) {}

        final List<PreDispatchObserved> observations = new CopyOnWriteArrayList<>();

        @Override
        public void onPreDispatchTerminalOutcome(KafkaRawRecordDisposition disposition, KafkaTerminalOutcome outcome) {
            observations.add(new PreDispatchObserved(disposition, outcome));
        }
    }

    /** Capture hook that always throws — used to verify robustness. */
    static final class ThrowingCaptureHook implements KafkaConsumerCaptureHook {

        final List<KafkaTerminalOutcome> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onTerminalOutcome(KafkaDispatchContext<?> ctx, KafkaTerminalOutcome outcome) {
            seen.add(outcome);
            throw new RuntimeException("hook exploded");
        }
    }

    // --- Minimal ConsumerControl test double (reuse from KafkaTerminalOutcomeTest) ---

    static final class CapturingConsumerControl implements KafkaErrorHandler.ConsumerControl {

        boolean commitCalled = false;
        boolean pauseCalled = false;
        boolean scheduleResumeCalled = false;
        long lastDelayMs = -1;
        final List<long[]> seekCalls = new ArrayList<>();

        @Override
        public void pause() {
            pauseCalled = true;
        }

        @Override
        public void scheduleResume(long delayMs) {
            scheduleResumeCalled = true;
            lastDelayMs = delayMs;
        }

        @Override
        public Future<Void> seekToOffset(String topic, int partition, long offset) {
            seekCalls.add(new long[] {partition, offset});
            return Future.succeededFuture();
        }

        @Override
        public void commitIfManual(KafkaConsumerRecord<String, byte[]> record) {
            commitCalled = true;
        }
    }

    // --- Helpers ---

    static ConsumerEntry entryFor(ErrorStrategy strategy, String dlqTopic) {
        return KafkaTerminalOutcomeTest.entryFor(strategy, dlqTopic);
    }

    @SuppressWarnings("unchecked")
    static KafkaConsumerRecord<String, byte[]> fakeRecord() {
        KafkaConsumerRecord<String, byte[]> rec = mock(KafkaConsumerRecord.class);
        when(rec.topic()).thenReturn("t");
        when(rec.partition()).thenReturn(0);
        when(rec.offset()).thenReturn(0L);
        return rec;
    }

    /**
     * Invokes hooks in priority order, swallowing any exception from each hook. Mirrors the
     * pattern that {@link KafkaConsumerVerticle} uses internally.
     */
    static void invokeHooks(
            List<KafkaConsumerCaptureHook> sortedHooks, KafkaDispatchContext<?> ctx, KafkaTerminalOutcome outcome) {
        for (KafkaConsumerCaptureHook hook : sortedHooks) {
            try {
                hook.onTerminalOutcome(ctx, outcome);
            } catch (Exception ex) {
                // intentionally swallowed — hooks must not break dispatch
            }
        }
    }

    /**
     * Invokes {@link KafkaConsumerCaptureHook#onPreDispatchTerminalOutcome} on each hook in order,
     * swallowing exceptions. Mirrors the pre-dispatch notification pattern in
     * {@link KafkaConsumerVerticle}.
     */
    static void invokePreDispatchHooks(
            List<KafkaConsumerCaptureHook> sortedHooks,
            KafkaRawRecordDisposition disposition,
            KafkaTerminalOutcome outcome) {
        for (KafkaConsumerCaptureHook hook : sortedHooks) {
            try {
                hook.onPreDispatchTerminalOutcome(disposition, outcome);
            } catch (Exception ex) {
                // intentionally swallowed — hooks must not break dispatch
            }
        }
    }

    /** Builds a minimal {@link KafkaRawRecordDisposition} for testing. */
    static KafkaRawRecordDisposition rawDisposition(String consumerName, String topic, int partition, long offset) {
        return new KafkaRawRecordDisposition(
                consumerName, topic, partition, offset, "test-key", Map.of(), PayloadSources.absent(), 0L, 0);
    }

    // --- KafkaConsumerCaptureHook contract ---

    @Nested
    @DisplayName("KafkaConsumerCaptureHook contract")
    class HookContract {

        @Test
        @DisplayName("default onTerminalOutcome is a no-op — does not throw")
        void defaultNoOp() {
            KafkaConsumerCaptureHook hook = new KafkaConsumerCaptureHook() {};
            KafkaDispatchContext<?> ctx =
                    new KafkaDispatchContext<>("c", "t", 0, 1L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());
            hook.onTerminalOutcome(ctx, KafkaTerminalOutcome.SUCCESS);
        }

        @Test
        @DisplayName("implements OrderedExtension — default phase is APPLICATION")
        void implementsOrderedExtension() {
            KafkaConsumerCaptureHook hook = new KafkaConsumerCaptureHook() {};
            assertEquals(ExtensionPhase.APPLICATION, hook.phase());
        }

        @Test
        @DisplayName("hooks are sortable by OrderedExtension comparator")
        void sortableByComparator() {
            RecordingCaptureHook high = new RecordingCaptureHook(100);
            RecordingCaptureHook low = new RecordingCaptureHook(0);

            List<KafkaConsumerCaptureHook> hooks = new ArrayList<>(List.of(high, low));
            hooks.sort(OrderedExtension.comparator());

            assertSame(low, hooks.get(0), "lower priority must sort first");
            assertSame(high, hooks.get(1));
        }

        @Test
        @DisplayName("SYSTEM_FIRST phase hook sorts before APPLICATION hook regardless of priority")
        void systemFirstPhaseBeforeApplication() {
            KafkaConsumerCaptureHook sysFirst = new KafkaConsumerCaptureHook() {
                @Override
                public ExtensionPhase phase() {
                    return ExtensionPhase.SYSTEM_FIRST;
                }

                @Override
                public int priority() {
                    return Integer.MAX_VALUE;
                }
            };
            KafkaConsumerCaptureHook app = new KafkaConsumerCaptureHook() {
                @Override
                public int priority() {
                    return Integer.MIN_VALUE;
                }
            };

            List<KafkaConsumerCaptureHook> hooks = new ArrayList<>(List.of(app, sysFirst));
            hooks.sort(OrderedExtension.comparator());

            assertSame(sysFirst, hooks.get(0), "SYSTEM_FIRST must sort before APPLICATION");
        }
    }

    // --- Hook invocation with correct outcomes ---

    @Nested
    @DisplayName("hook invocation for error outcomes")
    class HookInvocationForErrors {

        private final RuntimeException cause = new RuntimeException("test-error");

        @Test
        @DisplayName("hook is invoked with SKIP outcome after SKIP strategy — side effects preserved")
        void skipStrategyInvokesHookWithSkip(VertxTestContext ctx) {
            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry entry = entryFor(ErrorStrategy.SKIP, null);
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);

            KafkaConsumerRecord<String, byte[]> rec = fakeRecord();
            CapturingConsumerControl control = new CapturingConsumerControl();

            KafkaDispatchContext<?> dispatchCtx =
                    new KafkaDispatchContext<>("test", "t", 0, 0L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
                ctx.verify(() -> {
                    invokeHooks(List.of(hook), dispatchCtx, outcome);
                    assertEquals(1, hook.observations.size());
                    assertEquals(
                            KafkaTerminalOutcome.SKIP, hook.observations.get(0).outcome());
                    assertTrue(control.commitCalled, "commitIfManual must still be called");
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("hook is invoked with DLQ_PUBLISHED after successful DLQ publish")
        void dlqPublishedInvokesHook(VertxTestContext ctx, @Mock KafkaProducerFactory factory) {
            when(factory.sendForDlq(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Future.succeededFuture());

            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry entry = entryFor(ErrorStrategy.DEAD_LETTER, "dlq");
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord();
            CapturingConsumerControl control = new CapturingConsumerControl();
            KafkaDispatchContext<?> dispatchCtx =
                    new KafkaDispatchContext<>("test", "t", 0, 0L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
                ctx.verify(() -> {
                    invokeHooks(List.of(hook), dispatchCtx, outcome);
                    assertEquals(1, hook.observations.size());
                    assertEquals(
                            KafkaTerminalOutcome.DLQ_PUBLISHED,
                            hook.observations.get(0).outcome());
                    // The outcome arrives AFTER the async DLQ publish settles
                    assertTrue(control.commitCalled, "commit must have been called before outcome emitted");
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("hook is invoked with DLQ_FAILED after failed DLQ publish")
        void dlqFailedInvokesHook(VertxTestContext ctx, @Mock KafkaProducerFactory factory) {
            when(factory.sendForDlq(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("kafka-down")));

            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry entry = entryFor(ErrorStrategy.DEAD_LETTER, "dlq");
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord();
            CapturingConsumerControl control = new CapturingConsumerControl();
            KafkaDispatchContext<?> dispatchCtx =
                    new KafkaDispatchContext<>("test", "t", 0, 0L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
                ctx.verify(() -> {
                    invokeHooks(List.of(hook), dispatchCtx, outcome);
                    assertEquals(1, hook.observations.size());
                    assertEquals(
                            KafkaTerminalOutcome.DLQ_FAILED,
                            hook.observations.get(0).outcome());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("hook is invoked with RETRY_SCHEDULED — pause/seek/resume side effects preserved")
        void retryScheduledInvokesHook(VertxTestContext ctx) {
            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry entry = entryFor(ErrorStrategy.RETRY, null);
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord();
            CapturingConsumerControl control = new CapturingConsumerControl();
            KafkaDispatchContext<?> dispatchCtx =
                    new KafkaDispatchContext<>("test", "t", 0, 0L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
                ctx.verify(() -> {
                    invokeHooks(List.of(hook), dispatchCtx, outcome);
                    assertEquals(1, hook.observations.size());
                    assertEquals(
                            KafkaTerminalOutcome.RETRY_SCHEDULED,
                            hook.observations.get(0).outcome());
                    assertTrue(control.pauseCalled, "pause must still be called");
                    assertTrue(control.scheduleResumeCalled, "scheduleResume must still be called");
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Pre-dispatch terminal outcome ---

    @Nested
    @DisplayName("PreDispatchTerminalOutcome hook invocation")
    class PreDispatchTerminalOutcomeTests {

        @Test
        @DisplayName("pre-filter skip invokes hook with SKIP outcome")
        void preFilterSkip_invokesHookWithSkip() {
            RecordingPreDispatchHook hook = new RecordingPreDispatchHook();
            KafkaRawRecordDisposition disposition = rawDisposition("consumer1", "topic.src", 0, 5L);

            // The pre-filter exit path calls notifyPreDispatchTerminalOutcome(disposition, SKIP)
            invokePreDispatchHooks(List.of(hook), disposition, KafkaTerminalOutcome.SKIP);

            assertEquals(1, hook.observations.size(), "hook must be invoked once");
            assertEquals(KafkaTerminalOutcome.SKIP, hook.observations.get(0).outcome());
            assertEquals("consumer1", hook.observations.get(0).disposition().consumerName());
            assertEquals("topic.src", hook.observations.get(0).disposition().topic());
            assertEquals(5L, hook.observations.get(0).disposition().offset());
        }

        @Test
        @DisplayName("no-route (ROUTER kind) invokes hook with SKIP outcome")
        void noRoute_invokesHookWithSkip() {
            RecordingPreDispatchHook hook = new RecordingPreDispatchHook();
            KafkaRawRecordDisposition disposition = rawDisposition("router-consumer", "events.in", 2, 99L);

            // The no-route exit path also calls notifyPreDispatchTerminalOutcome(disposition, SKIP)
            invokePreDispatchHooks(List.of(hook), disposition, KafkaTerminalOutcome.SKIP);

            assertEquals(1, hook.observations.size(), "hook must be invoked once");
            assertEquals(KafkaTerminalOutcome.SKIP, hook.observations.get(0).outcome());
            assertEquals(
                    "router-consumer", hook.observations.get(0).disposition().consumerName());
            assertEquals(2, hook.observations.get(0).disposition().partition());
        }

        @Test
        @DisplayName("deserialization failure invokes hook with outcome from handleError")
        void deserFailure_invokesHookWithOutcomeFromErrorHandler(VertxTestContext ctx) {
            RecordingPreDispatchHook hook = new RecordingPreDispatchHook();
            ConsumerEntry entry = entryFor(ErrorStrategy.SKIP, null);
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);

            KafkaConsumerRecord<String, byte[]> rec = fakeRecord();
            CapturingConsumerControl control = new CapturingConsumerControl();
            KafkaRawRecordDisposition disposition = rawDisposition("test", "t", 0, 0L);

            RuntimeException deserException = new RuntimeException("deser-failure");

            // Mirrors the deser-failure exit path: handleError returns the outcome Future,
            // then notifyPreDispatchTerminalOutcome is called on success.
            handler.handleError(rec, new byte[0], Map.of(), deserException, control)
                    .onComplete(ctx.succeeding(outcome -> {
                        ctx.verify(() -> {
                            invokePreDispatchHooks(List.of(hook), disposition, outcome);
                            assertEquals(1, hook.observations.size(), "hook must be invoked once");
                            assertEquals(
                                    KafkaTerminalOutcome.SKIP,
                                    hook.observations.get(0).outcome(),
                                    "SKIP strategy must yield SKIP outcome");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("default onPreDispatchTerminalOutcome is a no-op — does not throw")
        void defaultNoOp() {
            KafkaConsumerCaptureHook hook = new KafkaConsumerCaptureHook() {};
            KafkaRawRecordDisposition disposition = rawDisposition("c", "t", 0, 0L);
            // must not throw
            hook.onPreDispatchTerminalOutcome(disposition, KafkaTerminalOutcome.SKIP);
        }

        @Test
        @DisplayName("a throwing hook does not block subsequent hooks in onPreDispatchTerminalOutcome")
        void throwingPreDispatchHookDoesNotBlockOtherHooks() {
            RecordingPreDispatchHook recordingHook = new RecordingPreDispatchHook();
            KafkaConsumerCaptureHook throwingHook = new KafkaConsumerCaptureHook() {
                @Override
                public void onPreDispatchTerminalOutcome(
                        KafkaRawRecordDisposition disposition, KafkaTerminalOutcome outcome) {
                    throw new RuntimeException("pre-dispatch hook exploded");
                }
            };

            KafkaRawRecordDisposition disposition = rawDisposition("c", "t", 0, 0L);

            List<KafkaConsumerCaptureHook> hooks = List.of(throwingHook, recordingHook);
            invokePreDispatchHooks(hooks, disposition, KafkaTerminalOutcome.SKIP);

            assertEquals(1, recordingHook.observations.size(), "recording hook must still run after throwing hook");
            assertEquals(
                    KafkaTerminalOutcome.SKIP, recordingHook.observations.get(0).outcome());
        }
    }

    // --- Verticle-level dispatch-pipeline hook notification (real dispatch pipeline) ---

    /**
     * Exercises the real {@code KafkaConsumerVerticle} dispatch pipeline for records that exit
     * inside the {@code runBeforeInterceptors(...).onComplete(...)} callback — either because an
     * interceptor filters the record, or because a {@code beforeDispatch} interceptor itself
     * fails — rather than the mirrored invocation helpers used elsewhere in this class. The
     * verticle's dispatch collaborators ({@code dispatcher}, {@code errorHandler},
     * {@code interceptorChain}) and {@code consumer} are normally built inside {@code start()},
     * which requires a live Kafka broker connection; this class wires them directly via
     * reflection so the pipeline runs deterministically offline.
     */
    @Nested
    @DisplayName("Verticle-level dispatch-pipeline hook notification")
    class VerticleDispatchPipelineHookNotification {

        /**
         * Sets a private field on the given {@link KafkaConsumerVerticle} instance via reflection.
         *
         * @param verticle the verticle instance to modify
         * @param name     the private field name
         * @param value    the value to assign
         * @throws ReflectiveOperationException if the field cannot be found or set
         */
        private static void setField(KafkaConsumerVerticle verticle, String name, Object value)
                throws ReflectiveOperationException {
            Field field = KafkaConsumerVerticle.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(verticle, value);
        }

        /**
         * Invokes the private {@code processRecord} method on the given verticle via reflection.
         *
         * @param verticle the verticle instance to invoke
         * @param record   the record to process
         * @throws ReflectiveOperationException if the method cannot be found or invoked
         */
        private static void invokeProcessRecord(
                KafkaConsumerVerticle verticle, KafkaConsumerRecord<String, byte[]> record)
                throws ReflectiveOperationException {
            Method method = KafkaConsumerVerticle.class.getDeclaredMethod("processRecord", KafkaConsumerRecord.class);
            method.setAccessible(true);
            method.invoke(verticle, record);
        }

        /**
         * Builds a mocked {@link KafkaErrorHandler} whose {@code handleError(...)} future always
         * fails, for exercising the "error handler's own future fails" hook-notification path.
         *
         * <p>Mockito bypasses the real constructor, so the mock's {@code retryCounts} field (read
         * directly by {@link KafkaConsumerVerticle}) is backfilled via reflection to avoid an
         * unrelated {@code NullPointerException} on that field.
         *
         * @return a mock error handler whose {@code handleError(...)} always returns a failed future
         * @throws ReflectiveOperationException if the {@code retryCounts} field cannot be backfilled
         */
        private static KafkaErrorHandler mockFailingErrorHandler() throws ReflectiveOperationException {
            KafkaErrorHandler handler = mock(KafkaErrorHandler.class);
            Field retryCounts = KafkaErrorHandler.class.getDeclaredField("retryCounts");
            retryCounts.setAccessible(true);
            retryCounts.set(handler, new java.util.concurrent.ConcurrentHashMap<String, Integer>());
            when(handler.handleError(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            anyMap(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("error handler boom")));
            return handler;
        }

        @Test
        @DisplayName("interceptor-filtered record notifies hooks with SKIP exactly once and still commits")
        void interceptorFilteredRecordNotifiesHooksWithSkip(Vertx vertx) throws ReflectiveOperationException {
            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry entry = entryFor(ErrorStrategy.SKIP, null);

            KafkaConsumerInterceptor filteringInterceptor = new KafkaConsumerInterceptor() {
                @Override
                public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
                    return Future.succeededFuture(ctx.withFiltered(true));
                }
            };

            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            // The filtered path never reaches event-bus dispatch, so a bare (unstubbed) mock is
            // used here rather than KafkaTestSupport.requestSender(vertx) — that helper stubs
            // ServiceSupervisor.isAvailable(), which strict Mockito flags as unused once dispatch
            // never happens.
            ServiceRequestSender requestSender = mock(ServiceRequestSender.class);

            KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                    entry,
                    List.of(filteringInterceptor),
                    Set.of(hook),
                    producerFactory,
                    requestSender,
                    KafkaTestSupport.noOpTargetResolver(),
                    KafkaTestSupport.eventBusClient(vertx),
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            // Wire the collaborators that start() normally builds after a successful broker
            // connection, so the real dispatchRecord() pipeline runs without one.
            @SuppressWarnings("unchecked")
            KafkaConsumer<String, byte[]> mockConsumer = mock(KafkaConsumer.class);
            when(mockConsumer.commit(anyMap())).thenReturn(Future.succeededFuture(Map.of()));
            setField(verticle, "consumer", mockConsumer);
            setField(verticle, "errorHandler", new KafkaErrorHandler(entry, producerFactory));
            setField(
                    verticle,
                    "dispatcher",
                    new KafkaRecordDispatcher(
                            entry,
                            Map.of(),
                            KafkaTestSupport.jsonSerdeRegistry(),
                            requestSender,
                            KafkaTestSupport.noOpTargetResolver(),
                            KafkaTestSupport.eventBusClient(vertx),
                            KafkaTestSupport.noOpInboundExecutionContextScope(),
                            KafkaTestSupport.noOpEnvelopeBuilder()));
            setField(
                    verticle,
                    "interceptorChain",
                    new KafkaConsumerInterceptorChain(entry.name(), List.of(filteringInterceptor)));

            KafkaConsumerRecord<String, byte[]> record = fakeRecord();
            when(record.value()).thenReturn(null); // tombstone value — bypasses the (unset) deserializer
            when(record.key()).thenReturn("k");
            when(record.headers()).thenReturn(null);

            invokeProcessRecord(verticle, record);

            assertEquals(1, hook.observations.size(), "hook must be notified exactly once for a filtered record");
            assertEquals(
                    KafkaTerminalOutcome.SKIP,
                    hook.observations.get(0).outcome(),
                    "interceptor-filtered records must notify SKIP");
            verify(mockConsumer).commit(anyMap());
        }

        @Test
        @DisplayName("before-interceptor failure notifies hooks with the error handler's outcome exactly once")
        void beforeInterceptorFailureNotifiesHooksWithErrorHandlerOutcome(Vertx vertx)
                throws ReflectiveOperationException {
            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry entry = entryFor(ErrorStrategy.SKIP, null);

            // beforeDispatch itself fails — this is distinct from the ctx.filtered() case above:
            // no interceptor ever returned a (possibly-filtered) context, so only the pre-interceptor
            // dispatchCtx built before runBeforeInterceptors ran is available.
            KafkaConsumerInterceptor failingInterceptor = new KafkaConsumerInterceptor() {
                @Override
                public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
                    return Future.failedFuture(new RuntimeException("before-dispatch boom"));
                }
            };

            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            ServiceRequestSender requestSender = mock(ServiceRequestSender.class);

            KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                    entry,
                    List.of(failingInterceptor),
                    Set.of(hook),
                    producerFactory,
                    requestSender,
                    KafkaTestSupport.noOpTargetResolver(),
                    KafkaTestSupport.eventBusClient(vertx),
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            @SuppressWarnings("unchecked")
            KafkaConsumer<String, byte[]> mockConsumer = mock(KafkaConsumer.class);
            when(mockConsumer.commit(anyMap())).thenReturn(Future.succeededFuture(Map.of()));
            setField(verticle, "consumer", mockConsumer);
            setField(verticle, "errorHandler", new KafkaErrorHandler(entry, producerFactory));
            setField(
                    verticle,
                    "dispatcher",
                    new KafkaRecordDispatcher(
                            entry,
                            Map.of(),
                            KafkaTestSupport.jsonSerdeRegistry(),
                            requestSender,
                            KafkaTestSupport.noOpTargetResolver(),
                            KafkaTestSupport.eventBusClient(vertx),
                            KafkaTestSupport.noOpInboundExecutionContextScope(),
                            KafkaTestSupport.noOpEnvelopeBuilder()));
            setField(
                    verticle,
                    "interceptorChain",
                    new KafkaConsumerInterceptorChain(entry.name(), List.of(failingInterceptor)));

            KafkaConsumerRecord<String, byte[]> record = fakeRecord();
            when(record.value()).thenReturn(null); // tombstone value — bypasses the (unset) deserializer
            when(record.key()).thenReturn("k");
            when(record.headers()).thenReturn(null);

            invokeProcessRecord(verticle, record);

            assertEquals(
                    1,
                    hook.observations.size(),
                    "hook must be notified exactly once when a before-dispatch interceptor fails");
            assertEquals(
                    KafkaTerminalOutcome.SKIP,
                    hook.observations.get(0).outcome(),
                    "SKIP error strategy must yield a SKIP outcome even on before-interceptor failure");
            verify(mockConsumer).commit(anyMap());
        }

        @Test
        @DisplayName(
                "error-handler failure on the pre-dispatch (deserialization) exit notifies ERROR_HANDLER_FAILED exactly once")
        void errorHandlerFailurePreDispatchNotifiesErrorHandlerFailed(Vertx vertx) throws ReflectiveOperationException {
            RecordingPreDispatchHook hook = new RecordingPreDispatchHook();
            // BINDING kind with no deserializer configured: a non-tombstone value forces
            // deserializeRecord() to throw DeserializationException, exercising the pre-dispatch
            // (deserialization-failure) exit rather than the outer processRecord() catch.
            ConsumerEntry entry = entryFor(ErrorStrategy.SKIP, null);

            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            ServiceRequestSender requestSender = mock(ServiceRequestSender.class);

            KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                    entry,
                    List.of(),
                    Set.of(hook),
                    producerFactory,
                    requestSender,
                    KafkaTestSupport.noOpTargetResolver(),
                    KafkaTestSupport.eventBusClient(vertx),
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            // Bare (unstubbed) mock consumer — this path never reaches commit/pause, so stubbing
            // commit() here would trip strict-stubs as unnecessary.
            @SuppressWarnings("unchecked")
            KafkaConsumer<String, byte[]> mockConsumer = mock(KafkaConsumer.class);
            setField(verticle, "consumer", mockConsumer);

            // The error handler's own future fails — the terminal notification must still fire.
            KafkaErrorHandler failingErrorHandler = mockFailingErrorHandler();
            setField(verticle, "errorHandler", failingErrorHandler);
            setField(
                    verticle,
                    "dispatcher",
                    new KafkaRecordDispatcher(
                            entry,
                            Map.of(),
                            KafkaTestSupport.jsonSerdeRegistry(),
                            requestSender,
                            KafkaTestSupport.noOpTargetResolver(),
                            KafkaTestSupport.eventBusClient(vertx),
                            KafkaTestSupport.noOpInboundExecutionContextScope(),
                            KafkaTestSupport.noOpEnvelopeBuilder()));
            setField(verticle, "interceptorChain", new KafkaConsumerInterceptorChain(entry.name(), List.of()));

            KafkaConsumerRecord<String, byte[]> record = fakeRecord();
            // Non-tombstone value with no deserializer configured (BINDING kind) forces the
            // deserialization-failure exit at dispatchRecord()'s inner catch block.
            when(record.value()).thenReturn("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            when(record.key()).thenReturn("k");
            when(record.headers()).thenReturn(null);

            invokeProcessRecord(verticle, record);

            assertEquals(
                    1,
                    hook.observations.size(),
                    "hook must be notified exactly once even when the error handler's own future fails");
            assertEquals(
                    KafkaTerminalOutcome.ERROR_HANDLER_FAILED,
                    hook.observations.get(0).outcome(),
                    "error-handler failure must surface as ERROR_HANDLER_FAILED, not silently dropped");
        }

        @Test
        @DisplayName(
                "error-handler failure on the post-dispatch (dispatch-error) exit notifies ERROR_HANDLER_FAILED exactly once")
        void errorHandlerFailurePostDispatchNotifiesErrorHandlerFailed(Vertx vertx)
                throws ReflectiveOperationException {
            RecordingCaptureHook hook = new RecordingCaptureHook(0);
            ConsumerEntry bindingEntry = entryFor(ErrorStrategy.SKIP, null);
            // HANDLER kind with no handler configured: dispatchToHandler() fails naturally, so this
            // exercises the real dispatch-error exit (post-interceptor, post-dispatch) rather than
            // the before-interceptor-failure exit covered above.
            ConsumerEntry entry = new ConsumerEntry(
                    bindingEntry.name(),
                    bindingEntry.config(),
                    ConsumerEntry.Kind.HANDLER,
                    Object.class,
                    null,
                    null,
                    false,
                    List.of(),
                    null,
                    null,
                    false,
                    null,
                    bindingEntry.valueFormat());

            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            ServiceRequestSender requestSender = mock(ServiceRequestSender.class);

            KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                    entry,
                    List.of(),
                    Set.of(hook),
                    producerFactory,
                    requestSender,
                    KafkaTestSupport.noOpTargetResolver(),
                    KafkaTestSupport.eventBusClient(vertx),
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            @SuppressWarnings("unchecked")
            KafkaConsumer<String, byte[]> mockConsumer = mock(KafkaConsumer.class);
            setField(verticle, "consumer", mockConsumer);

            KafkaErrorHandler failingErrorHandler = mockFailingErrorHandler();
            setField(verticle, "errorHandler", failingErrorHandler);
            setField(
                    verticle,
                    "dispatcher",
                    new KafkaRecordDispatcher(
                            entry,
                            Map.of(),
                            KafkaTestSupport.jsonSerdeRegistry(),
                            requestSender,
                            KafkaTestSupport.noOpTargetResolver(),
                            KafkaTestSupport.eventBusClient(vertx),
                            KafkaTestSupport.noOpInboundExecutionContextScope(),
                            KafkaTestSupport.noOpEnvelopeBuilder()));
            setField(verticle, "interceptorChain", new KafkaConsumerInterceptorChain(entry.name(), List.of()));

            KafkaConsumerRecord<String, byte[]> record = fakeRecord();
            when(record.value()).thenReturn(null); // tombstone value — bypasses the (unset) deserializer
            when(record.key()).thenReturn("k");
            when(record.headers()).thenReturn(null);

            invokeProcessRecord(verticle, record);

            assertEquals(
                    1,
                    hook.observations.size(),
                    "hook must be notified exactly once even when the error handler's own future fails");
            assertEquals(
                    KafkaTerminalOutcome.ERROR_HANDLER_FAILED,
                    hook.observations.get(0).outcome(),
                    "error-handler failure on the dispatch-error exit must surface as ERROR_HANDLER_FAILED");
        }
    }

    // --- Throwing hook safety ---

    @Nested
    @DisplayName("Throwing hook safety")
    class ThrowingHookSafety {

        @Test
        @DisplayName("a throwing hook does not propagate its exception; subsequent hooks still run")
        void throwingHookDoesNotBlockOtherHooks() {
            ThrowingCaptureHook throwingHook = new ThrowingCaptureHook();
            RecordingCaptureHook recordingHook = new RecordingCaptureHook(1);

            KafkaDispatchContext<?> ctx =
                    new KafkaDispatchContext<>("c", "t", 0, 0L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());

            // Sort: throwingHook is anonymous (priority=0 by default), recordingHook has priority=1
            KafkaConsumerCaptureHook first = new KafkaConsumerCaptureHook() {
                @Override
                public void onTerminalOutcome(KafkaDispatchContext<?> cx, KafkaTerminalOutcome outcome) {
                    throwingHook.onTerminalOutcome(cx, outcome);
                }
            };

            List<KafkaConsumerCaptureHook> sorted = new ArrayList<>(List.of(first, recordingHook));
            sorted.sort(OrderedExtension.comparator());

            // invokeHooks must not throw even though throwingHook throws
            invokeHooks(sorted, ctx, KafkaTerminalOutcome.SUCCESS);

            assertEquals(1, throwingHook.seen.size(), "throwing hook must be invoked");
            assertEquals(1, recordingHook.observations.size(), "recording hook must be invoked after throwing hook");
            assertEquals(
                    KafkaTerminalOutcome.SUCCESS,
                    recordingHook.observations.get(0).outcome());
        }
    }
}
