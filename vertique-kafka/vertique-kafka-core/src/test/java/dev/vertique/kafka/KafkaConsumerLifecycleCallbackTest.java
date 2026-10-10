// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordIdentity;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.services.ServiceRequestSender;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link KafkaConsumerInterceptor#onRecordCompleted}, exercised through the real
 * {@link KafkaConsumerVerticle} record pipeline.
 *
 * <p>The verticle's collaborators ({@code consumer}, {@code errorHandler}, {@code dispatcher},
 * {@code interceptorChain}) are normally built in {@code start()}, which needs a broker. The fixture
 * wires them by reflection so every terminal path runs offline and synchronously.
 *
 * <p>Covered: one completion with the expected outcome on every terminal path; the framework-owned
 * identity, key and headers cannot be changed by an interceptor or a filter; the view keeps
 * duplicate, null-valued and binary headers, and the filter, deserializer, dispatch context, record
 * context and handler receive that same header collection; the framework's own text reads
 * (correlation id, header routing, durable context) keep their results; the value is the
 * broker's array, uncopied; error isolation of the completion callback and of the three older
 * synchronous observers; a completion held back until an asynchronous dead-letter publish settles;
 * the retry count of a first and of a redelivered record; a consumer with no interceptors; and the
 * null checks of the two event records.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class KafkaConsumerLifecycleCallbackTest {

    private static final String TOPIC = "t";
    private static final long TIMESTAMP = 1_700_000_000_000L;

    // --- Test doubles ---

    /** Interceptor that records every completion it observes. */
    static final class CompletionRecorder implements KafkaConsumerInterceptor {

        record Completion(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {}

        final List<Completion> completions = new CopyOnWriteArrayList<>();
        private final int interceptorPriority;
        private final List<String> order;

        CompletionRecorder() {
            this(0, new ArrayList<>());
        }

        CompletionRecorder(int priority, List<String> order) {
            this.interceptorPriority = priority;
            this.order = order;
        }

        @Override
        public int priority() {
            return interceptorPriority;
        }

        @Override
        public void onRecordCompleted(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {
            order.add("p" + interceptorPriority);
            completions.add(new Completion(event, record));
        }

        Completion only() {
            assertEquals(1, completions.size(), "the record must complete exactly once");
            return completions.get(0);
        }
    }

    /** Wired verticle plus the mocks a test needs to assert on. */
    record Fixture(KafkaConsumerVerticle verticle, KafkaConsumer<String, byte[]> consumer, KafkaErrorHandler errors) {

        void process(KafkaConsumerRecord<String, byte[]> record) throws ReflectiveOperationException {
            Method method = KafkaConsumerVerticle.class.getDeclaredMethod("processRecord", KafkaConsumerRecord.class);
            method.setAccessible(true);
            method.invoke(verticle, record);
        }

        int inFlight() throws ReflectiveOperationException {
            Field field = KafkaConsumerVerticle.class.getDeclaredField("inFlight");
            field.setAccessible(true);
            return ((AtomicInteger) field.get(verticle)).get();
        }
    }

    // --- Fixture helpers ---

    private static void setField(KafkaConsumerVerticle verticle, String name, Object value)
            throws ReflectiveOperationException {
        Field field = KafkaConsumerVerticle.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(verticle, value);
    }

    /** BINDING entry with no deserializer: a non-null value fails deserialization. */
    private static ConsumerEntry bindingEntry(ErrorStrategy strategy, @Nullable String dlqTopic) {
        return KafkaTerminalOutcomeTest.entryFor(strategy, dlqTopic);
    }

    /** HANDLER entry; a {@code null} handler makes every dispatch fail. */
    private static ConsumerEntry handlerEntry(
            ErrorStrategy strategy,
            @Nullable String dlqTopic,
            @Nullable KafkaRecordHandler<?> handler,
            @Nullable KafkaDeserializer<?> deserializer,
            @Nullable KafkaRecordFilter filter) {
        ConsumerEntry base = bindingEntry(strategy, dlqTopic);
        return new ConsumerEntry(
                base.name(),
                base.config(),
                ConsumerEntry.Kind.HANDLER,
                Object.class,
                null,
                null,
                false,
                List.of(),
                handler,
                deserializer,
                false,
                filter,
                base.valueFormat());
    }

    private static ConsumerEntry failingHandlerEntry(ErrorStrategy strategy, @Nullable String dlqTopic) {
        return handlerEntry(strategy, dlqTopic, null, null, null);
    }

    private static ConsumerEntry succeedingHandlerEntry() {
        return handlerEntry(ErrorStrategy.SKIP, null, message -> Future.succeededFuture(), null, null);
    }

    /** ROUTER entry with no routes: no record ever matches. */
    private static ConsumerEntry routerEntry() {
        ConsumerEntry base = bindingEntry(ErrorStrategy.SKIP, null);
        return new ConsumerEntry(
                base.name(),
                base.config(),
                ConsumerEntry.Kind.ROUTER,
                null,
                null,
                null,
                false,
                List.of(),
                null,
                null,
                false,
                null,
                base.valueFormat());
    }

    private static KafkaRecordDispatcher realDispatcher(Vertx vertx, ConsumerEntry entry) {
        return new KafkaRecordDispatcher(
                entry,
                Map.of(),
                KafkaTestSupport.jsonSerdeRegistry(),
                mock(ServiceRequestSender.class),
                KafkaTestSupport.noOpTargetResolver(),
                KafkaTestSupport.eventBusClient(vertx),
                KafkaTestSupport.noOpInboundExecutionContextScope(),
                KafkaTestSupport.noOpEnvelopeBuilder());
    }

    /** What a {@link #recordingDispatcher} sent to the event bus and bound as durable context. */
    record Dispatches(List<String> addresses, List<DispatchEnvelope<?>> envelopes, List<DurableMetadata> bound) {

        Dispatches() {
            this(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        }

        /** The record context carried by the only envelope sent. */
        KafkaRecordContext onlyRecordContext() {
            assertEquals(1, envelopes.size(), "exactly one envelope must have been sent");
            return (KafkaRecordContext)
                    envelopes.get(0).metadata().dispatchContext().get(KafkaRecordContext.class.getName());
        }
    }

    /**
     * A real dispatcher whose two outward seams are recorded: the event-bus send (address and
     * envelope) and the durable context decoded from the record and handed to the context scope.
     */
    private static KafkaRecordDispatcher recordingDispatcher(ConsumerEntry entry, Dispatches dispatches) {
        EventBusClient eventBus = mock(EventBusClient.class);
        lenient()
                .doAnswer(invocation -> {
                    dispatches.addresses().add(invocation.getArgument(0));
                    dispatches.envelopes().add(invocation.getArgument(1));
                    return null;
                })
                .when(eventBus)
                .send(anyString(), any());
        DurableContextPropagator propagator = mock(DurableContextPropagator.class);
        lenient().when(propagator.bindFrom(any(), anyString())).thenAnswer(invocation -> {
            dispatches.bound().add(invocation.getArgument(0));
            return (dev.vertique.core.context.ContextHolder.Scope) () -> {};
        });
        return new KafkaRecordDispatcher(
                entry,
                Map.of(),
                KafkaTestSupport.jsonSerdeRegistry(),
                mock(ServiceRequestSender.class),
                KafkaTestSupport.noOpTargetResolver(),
                eventBus,
                new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of()),
                KafkaTestSupport.noOpEnvelopeBuilder());
    }

    /**
     * Processes a record on a duplicated Vert.x context, as the Kafka client does, and returns once
     * everything queued on that context has run. Event-bus dispatch binds context values, which
     * needs a duplicated context.
     */
    private static void processOnDuplicatedContext(
            Vertx vertx, Fixture fixture, KafkaConsumerRecord<String, byte[]> record) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        CompletableFuture<Void> done = new CompletableFuture<>();
        duplicate.runOnContext(v -> {
            try {
                fixture.process(record);
                duplicate.runOnContext(drained -> done.complete(null));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    /** Records every dispatch context handed to the synchronous {@code onRecord} observer. */
    static final class DispatchContextRecorder implements KafkaConsumerInterceptor {

        final List<KafkaDispatchContext<?>> contexts = new CopyOnWriteArrayList<>();

        @Override
        public void onRecord(KafkaDispatchContext<?> ctx) {
            contexts.add(ctx);
        }
    }

    /** Mocked error handler whose own future always fails. */
    private static KafkaErrorHandler failingErrorHandler() throws ReflectiveOperationException {
        KafkaErrorHandler handler = mock(KafkaErrorHandler.class);
        Field retryCounts = KafkaErrorHandler.class.getDeclaredField("retryCounts");
        retryCounts.setAccessible(true);
        retryCounts.set(handler, new ConcurrentHashMap<String, Integer>());
        when(handler.handleError(any(), any(), any(), any(), any()))
                .thenReturn(Future.failedFuture(new RuntimeException("error handler boom")));
        return handler;
    }

    private static Fixture wire(Vertx vertx, ConsumerEntry entry, List<KafkaConsumerInterceptor> interceptors)
            throws ReflectiveOperationException {
        return wire(vertx, entry, interceptors, mock(KafkaProducerFactory.class), null, null);
    }

    /**
     * Builds a verticle and wires the collaborators {@code start()} would build. The same interceptor
     * list goes to the constructor and to the chain, as in production.
     */
    @SuppressWarnings("unchecked")
    private static Fixture wire(
            Vertx vertx,
            ConsumerEntry entry,
            List<KafkaConsumerInterceptor> interceptors,
            KafkaProducerFactory producerFactory,
            @Nullable KafkaErrorHandler errorHandler,
            @Nullable KafkaRecordDispatcher dispatcher)
            throws ReflectiveOperationException {
        KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                entry,
                interceptors,
                producerFactory,
                mock(ServiceRequestSender.class),
                KafkaTestSupport.noOpTargetResolver(),
                KafkaTestSupport.eventBusClient(vertx),
                KafkaTestSupport.noOpInboundExecutionContextScope(),
                KafkaTestSupport.noOpEnvelopeBuilder(),
                KafkaTestSupport.jsonSerdeRegistry());
        // The retry path sets a timer, so the verticle needs its Vert.x instance.
        verticle.init(vertx, vertx.getOrCreateContext());

        KafkaConsumer<String, byte[]> consumer = mock(KafkaConsumer.class);
        lenient().when(consumer.commit(anyMap())).thenReturn(Future.succeededFuture(Map.of()));
        lenient().when(consumer.seek(any(), anyLong())).thenReturn(Future.succeededFuture());
        KafkaErrorHandler errors = errorHandler != null ? errorHandler : new KafkaErrorHandler(entry, producerFactory);
        setField(verticle, "consumer", consumer);
        setField(verticle, "errorHandler", errors);
        setField(verticle, "dispatcher", dispatcher != null ? dispatcher : realDispatcher(vertx, entry));
        setField(verticle, "interceptorChain", new KafkaConsumerInterceptorChain(entry.name(), interceptors));
        return new Fixture(verticle, consumer, errors);
    }

    /** A record with key {@code "k"}, no headers and the given value ({@code null} = tombstone). */
    private static KafkaConsumerRecord<String, byte[]> record(long offset, @Nullable byte[] value) {
        return record(offset, "k", value, null);
    }

    @SuppressWarnings("unchecked")
    private static KafkaConsumerRecord<String, byte[]> record(
            long offset, @Nullable String key, @Nullable byte[] value, @Nullable List<KafkaHeader> headers) {
        KafkaConsumerRecord<String, byte[]> rec = mock(KafkaConsumerRecord.class);
        lenient().when(rec.topic()).thenReturn(TOPIC);
        lenient().when(rec.partition()).thenReturn(3);
        lenient().when(rec.offset()).thenReturn(offset);
        lenient().when(rec.timestamp()).thenReturn(TIMESTAMP);
        lenient().when(rec.key()).thenReturn(key);
        lenient().when(rec.value()).thenReturn(value);
        lenient().when(rec.headers()).thenReturn(headers);
        return rec;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(KafkaConsumerRecordView view) {
        try (InputStream in = view.value().bufferedStream().orElseThrow()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Asserts one completion with the outcome, one shared identity instance, and a released slot. */
    private static CompletionRecorder.Completion assertCompletedOnce(
            Fixture fixture, CompletionRecorder recorder, KafkaTerminalOutcome expected)
            throws ReflectiveOperationException {
        CompletionRecorder.Completion completion = recorder.only();
        assertEquals(expected, completion.event().outcome());
        assertSame(
                completion.event().identity(),
                completion.record().identity(),
                "the event and the record view must share one identity instance");
        assertEquals(0, fixture.inFlight(), "the in-flight slot must be released");
        return completion;
    }

    // --- Terminal paths ---

    @Nested
    @DisplayName("every terminal path completes the record exactly once")
    class TerminalPaths {

        @Test
        @DisplayName("pre-deserialization filter rejection completes with SKIP and commits")
        void filterRejection(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, null, null, (key, headers) -> false);
            Fixture fixture = wire(vertx, entry, List.of(recorder));

            fixture.process(record(7L, bytes("v")));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            assertEquals(
                    new KafkaConsumerRecordIdentity(entry.name(), TOPIC, 3, 7L, TIMESTAMP, 0),
                    completion.event().identity());
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName(
                "a filter that throws completes the record once, releases the slot and leaves the consumer working")
        void filterThrows(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicInteger handled = new AtomicInteger();
            KafkaRecordFilter throwingOnBadKey = (key, headers) -> {
                if ("bad".equals(key)) {
                    throw new IllegalStateException("filter boom");
                }
                return true;
            };
            ConsumerEntry entry = handlerEntry(
                    ErrorStrategy.SKIP,
                    null,
                    message -> {
                        handled.incrementAndGet();
                        return Future.succeededFuture();
                    },
                    null,
                    throwingOnBadKey);
            Fixture fixture = wire(vertx, entry, List.of(recorder));

            fixture.process(record(1L, "bad", bytes("v"), null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            assertEquals(0, handled.get(), "a record whose filter threw must not be dispatched");

            fixture.process(record(2L, "good", bytes("v"), null));

            assertEquals(
                    List.of(KafkaTerminalOutcome.SKIP, KafkaTerminalOutcome.SUCCESS),
                    recorder.completions.stream().map(c -> c.event().outcome()).toList(),
                    "each record must complete exactly once");
            assertEquals(1, handled.get(), "the next record must still be processed");
            assertEquals(0, fixture.inFlight(), "the in-flight slot must be released");
            verify(fixture.consumer(), times(2)).commit(anyMap());
        }

        @Test
        @DisplayName("a filter that throws and whose error handling fails completes with ERROR_HANDLER_FAILED")
        void filterThrowsAndErrorHandlerFails(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, null, null, (key, headers) -> {
                throw new IllegalStateException("filter boom");
            });
            Fixture fixture = wire(
                    vertx, entry, List.of(recorder), mock(KafkaProducerFactory.class), failingErrorHandler(), null);

            fixture.process(record(1L, bytes("v")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
        }

        @Test
        @DisplayName("a router with no matching route completes with SKIP and commits")
        void noMatchingRoute(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, routerEntry(), List.of(recorder));

            fixture.process(record(1L, bytes("v")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a failure thrown while resolving the route completes with the error handler's outcome")
        void routeResolutionThrows(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            ConsumerEntry entry = routerEntry();
            KafkaRecordDispatcher dispatcher = mock(KafkaRecordDispatcher.class);
            when(dispatcher.resolveRoute(any(), anyMap(), any(), anyString()))
                    .thenThrow(new IllegalStateException("route"));
            Fixture fixture = wire(vertx, entry, List.of(recorder), mock(KafkaProducerFactory.class), null, dispatcher);

            fixture.process(record(1L, bytes("v")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
        }

        @Test
        @DisplayName("a route-resolution failure whose error handling fails completes with ERROR_HANDLER_FAILED")
        void routeResolutionThrowsAndErrorHandlerFails(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            KafkaRecordDispatcher dispatcher = mock(KafkaRecordDispatcher.class);
            when(dispatcher.resolveRoute(any(), anyMap(), any(), anyString()))
                    .thenThrow(new IllegalStateException("route"));
            Fixture fixture = wire(
                    vertx,
                    routerEntry(),
                    List.of(recorder),
                    mock(KafkaProducerFactory.class),
                    failingErrorHandler(),
                    dispatcher);

            fixture.process(record(1L, bytes("v")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
        }

        @Test
        @DisplayName("a deserialization failure completes with the error handler's outcome")
        void deserializationFailure(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, bindingEntry(ErrorStrategy.SKIP, null), List.of(recorder));

            fixture.process(record(1L, bytes("payload")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a deserialization failure whose error handling fails completes with ERROR_HANDLER_FAILED")
        void deserializationFailureAndErrorHandlerFails(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            ConsumerEntry entry = bindingEntry(ErrorStrategy.SKIP, null);
            Fixture fixture = wire(
                    vertx, entry, List.of(recorder), mock(KafkaProducerFactory.class), failingErrorHandler(), null);

            fixture.process(record(1L, bytes("payload")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
        }

        @Test
        @DisplayName("a failed beforeDispatch completes with the error handler's outcome")
        void beforeDispatchFailure(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(new FailingBeforeDispatch(), recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a failed beforeDispatch whose error handling fails completes with ERROR_HANDLER_FAILED")
        void beforeDispatchFailureAndErrorHandlerFails(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(
                    vertx,
                    succeedingHandlerEntry(),
                    List.of(new FailingBeforeDispatch(), recorder),
                    mock(KafkaProducerFactory.class),
                    failingErrorHandler(),
                    null);

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
        }

        @Test
        @DisplayName("a record filtered by an interceptor completes with SKIP and commits")
        void interceptorFiltered(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            KafkaConsumerInterceptor filtering = new KafkaConsumerInterceptor() {
                @Override
                public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
                    return Future.succeededFuture(ctx.withFiltered(true));
                }
            };
            AtomicInteger handled = new AtomicInteger();
            ConsumerEntry entry = handlerEntry(
                    ErrorStrategy.SKIP,
                    null,
                    message -> {
                        handled.incrementAndGet();
                        return Future.succeededFuture();
                    },
                    null,
                    null);
            Fixture fixture = wire(vertx, entry, List.of(filtering, recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            assertEquals(0, handled.get(), "a filtered record must not be dispatched");
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a successful dispatch completes with SUCCESS and commits")
        void success(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicInteger handled = new AtomicInteger();
            ConsumerEntry entry = handlerEntry(
                    ErrorStrategy.SKIP,
                    null,
                    message -> {
                        handled.incrementAndGet();
                        return Future.succeededFuture();
                    },
                    null,
                    null);
            Fixture fixture = wire(vertx, entry, List.of(recorder));

            fixture.process(record(1L, bytes("v")));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            assertEquals(1, handled.get(), "the handler must have run");
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a dispatch failure recovered by an interceptor completes with RECOVERED and commits")
        void recovered(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            KafkaConsumerInterceptor recovering = new KafkaConsumerInterceptor() {
                @Override
                public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
                    return Future.succeededFuture();
                }
            };
            Fixture fixture = wire(vertx, failingHandlerEntry(ErrorStrategy.SKIP, null), List.of(recovering, recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.RECOVERED);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a dispatch failure under SKIP completes with SKIP and commits")
        void dispatchFailureSkipped(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, failingHandlerEntry(ErrorStrategy.SKIP, null), List.of(recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a dispatch failure under DEAD_LETTER completes with DLQ_PUBLISHED after the publish, and commits")
        void dispatchFailureDeadLettered(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            when(producerFactory.sendForDlq(anyString(), any(), any(), any())).thenReturn(Future.succeededFuture());
            Fixture fixture = wire(
                    vertx,
                    failingHandlerEntry(ErrorStrategy.DEAD_LETTER, "dlq"),
                    List.of(recorder),
                    producerFactory,
                    null,
                    null);

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.DLQ_PUBLISHED);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a dead-letter publish that settles later completes the record only when it settles")
        void deadLetterPublishSettlesLater(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Promise<RecordMetadata> publish = Promise.promise();
            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            when(producerFactory.sendForDlq(anyString(), any(), any(), any())).thenReturn(publish.future());
            Fixture fixture = wire(
                    vertx,
                    failingHandlerEntry(ErrorStrategy.DEAD_LETTER, "dlq"),
                    List.of(recorder),
                    producerFactory,
                    null,
                    null);

            fixture.process(record(1L, null));

            verify(producerFactory).sendForDlq(anyString(), any(), any(), any());
            assertEquals(List.of(), recorder.completions, "the record must not complete before the publish settles");
            verify(fixture.consumer(), never()).commit(anyMap());

            publish.complete(null);

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.DLQ_PUBLISHED);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("a failed dead-letter publish completes with DLQ_FAILED and does not commit")
        void deadLetterPublishFails(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
            when(producerFactory.sendForDlq(anyString(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("kafka-down")));
            Fixture fixture = wire(
                    vertx,
                    failingHandlerEntry(ErrorStrategy.DEAD_LETTER, "dlq"),
                    List.of(recorder),
                    producerFactory,
                    null,
                    null);

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.DLQ_FAILED);
            verify(fixture.consumer(), never()).commit(anyMap());
        }

        @Test
        @DisplayName("a dispatch failure under RETRY completes with RETRY_SCHEDULED, seeks back and does not commit")
        void dispatchFailureRetried(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, failingHandlerEntry(ErrorStrategy.RETRY, null), List.of(recorder));

            fixture.process(record(5L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.RETRY_SCHEDULED);
            verify(fixture.consumer()).pause();
            verify(fixture.consumer()).seek(any(), anyLong());
            verify(fixture.consumer(), never()).commit(anyMap());
        }

        @Test
        @DisplayName("a dispatch failure whose error handling fails completes with ERROR_HANDLER_FAILED")
        void dispatchFailureAndErrorHandlerFails(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(
                    vertx,
                    failingHandlerEntry(ErrorStrategy.SKIP, null),
                    List.of(recorder),
                    mock(KafkaProducerFactory.class),
                    failingErrorHandler(),
                    null);

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.ERROR_HANDLER_FAILED);
        }

        @Test
        @DisplayName("interceptors are notified in their list order")
        void notifiedInOrder(Vertx vertx) throws ReflectiveOperationException {
            List<String> order = new ArrayList<>();
            CompletionRecorder first = new CompletionRecorder(1, order);
            CompletionRecorder second = new CompletionRecorder(2, order);
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(first, second));

            fixture.process(record(1L, null));

            assertEquals(List.of("p1", "p2"), order);
            assertSame(first.only().record(), second.only().record(), "every interceptor must receive the same view");
        }
    }

    /** Interceptor whose {@code beforeDispatch} fails. */
    static final class FailingBeforeDispatch implements KafkaConsumerInterceptor {

        @Override
        public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
            return Future.failedFuture(new RuntimeException("before-dispatch boom"));
        }
    }

    // --- Framework-owned identity, key and headers ---

    @Nested
    @DisplayName("identity, key and headers are framework-owned")
    class FrameworkOwnedRecord {

        /** A wholesale-replaced context: different coordinates, key, bytes, headers and retry count. */
        private KafkaDispatchContext<Object> forgedContext() {
            return new KafkaDispatchContext<>(
                    "forged-consumer",
                    "forged.topic",
                    9,
                    999L,
                    "forged-key",
                    null,
                    PayloadSources.buffered(bytes("forged"), null),
                    KafkaRecordHeaders.of(Map.of("forged", "1")),
                    0L,
                    5,
                    false,
                    Map.of());
        }

        private KafkaConsumerInterceptor replacing(KafkaDispatchContext<Object> forged, AtomicInteger calls) {
            return new KafkaConsumerInterceptor() {
                @Override
                public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
                    calls.incrementAndGet();
                    return Future.succeededFuture(forged);
                }
            };
        }

        private void assertRealRecord(ConsumerEntry entry, CompletionRecorder.Completion completion) {
            assertEquals(
                    new KafkaConsumerRecordIdentity(entry.name(), TOPIC, 3, 42L, TIMESTAMP, 0),
                    completion.event().identity(),
                    "the identity must carry the record's own coordinates");
            assertEquals("real-key", completion.record().key());
            assertEquals(
                    new KafkaRecordHeaders(List.of(KafkaRecordHeader.ofUtf8("x-real", "1"))),
                    completion.record().headers());
        }

        @Test
        @DisplayName("a replaced dispatch context does not change the completed record on the success path")
        void replacedContextOnSuccess(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicInteger calls = new AtomicInteger();
            ConsumerEntry entry = succeedingHandlerEntry();
            Fixture fixture = wire(vertx, entry, List.of(replacing(forgedContext(), calls), recorder));

            fixture.process(record(42L, "real-key", null, List.of(KafkaHeader.header("x-real", "1"))));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            assertEquals(1, calls.get(), "the replacing interceptor must really have run");
            assertRealRecord(entry, completion);
        }

        @Test
        @DisplayName("a replaced dispatch context does not change the completed record when dispatch fails")
        void replacedContextOnDispatchFailure(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicInteger calls = new AtomicInteger();
            ConsumerEntry entry = failingHandlerEntry(ErrorStrategy.SKIP, null);
            Fixture fixture = wire(vertx, entry, List.of(replacing(forgedContext(), calls), recorder));

            fixture.process(record(42L, "real-key", null, List.of(KafkaHeader.header("x-real", "1"))));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            assertEquals(1, calls.get(), "the replacing interceptor must really have run");
            assertRealRecord(entry, completion);
        }

        @Test
        @DisplayName("a filter cannot change the headers: every attempt fails or changes only its own copy, "
                + "and the handler and the view read the headers as received")
        void filterCannotChangeHeaders(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            List<String> rejected = new CopyOnWriteArrayList<>();
            AtomicReference<KafkaRecordHeaders> seenByHandler = new AtomicReference<>();
            KafkaRecordFilter tampering = (key, headers) -> {
                try {
                    headers.entries().add(KafkaRecordHeader.ofUtf8("injected", "1"));
                } catch (UnsupportedOperationException e) {
                    rejected.add("entries.add");
                }
                try {
                    headers.entries().remove(0);
                } catch (UnsupportedOperationException e) {
                    rejected.add("entries.remove");
                }
                try {
                    headers.asMap().put("injected", "1");
                } catch (UnsupportedOperationException e) {
                    rejected.add("asMap.put");
                }
                try {
                    headers.asMap().remove("x-real");
                } catch (UnsupportedOperationException e) {
                    rejected.add("asMap.remove");
                }
                try {
                    headers.iterator().next();
                    java.util.Iterator<KafkaRecordHeader> iterator = headers.iterator();
                    iterator.next();
                    iterator.remove();
                } catch (UnsupportedOperationException e) {
                    rejected.add("iterator.remove");
                }
                // A value buffer is a copy: writing to it changes nothing but the copy.
                headers.entries().get(0).value().setByte(0, (byte) '9');
                return true;
            };
            KafkaRecordHandler<Object> handler = message -> {
                seenByHandler.set(message.headers());
                return Future.succeededFuture();
            };
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, handler, null, tampering);
            Fixture fixture = wire(vertx, entry, List.of(recorder));

            fixture.process(record(1L, "k", null, List.of(KafkaHeader.header("x-real", "1"))));

            KafkaRecordHeaders headers = assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                    .record()
                    .headers();
            assertEquals(
                    List.of("entries.add", "entries.remove", "asMap.put", "asMap.remove", "iterator.remove"),
                    rejected,
                    "every structural change must be rejected");
            assertEquals(new KafkaRecordHeaders(List.of(KafkaRecordHeader.ofUtf8("x-real", "1"))), headers);
            assertEquals(Map.of("x-real", "1"), headers.asMap());
            assertSame(headers, seenByHandler.get(), "the handler reads the headers the view holds");
            assertEquals(
                    Buffer.buffer(bytes("1")),
                    seenByHandler.get().entries().get(0).value());
        }

        @Test
        @DisplayName("a replaced dispatch context does not change duplicate and binary headers on the view")
        void replacedContextKeepsFaithfulHeaders(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicInteger calls = new AtomicInteger();
            ConsumerEntry entry = succeedingHandlerEntry();
            Fixture fixture = wire(vertx, entry, List.of(replacing(forgedContext(), calls), recorder));

            fixture.process(record(
                    42L,
                    "real-key",
                    null,
                    List.of(
                            KafkaHeader.header("x-real", "1"),
                            KafkaHeader.header("x-bin", Buffer.buffer(new byte[] {(byte) 0xFF})),
                            KafkaHeader.header("x-real", "2"))));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            assertEquals(1, calls.get(), "the replacing interceptor must really have run");
            assertEquals(
                    new KafkaRecordHeaders(List.of(
                            KafkaRecordHeader.ofUtf8("x-real", "1"),
                            new KafkaRecordHeader("x-bin", Buffer.buffer(new byte[] {(byte) 0xFF})),
                            KafkaRecordHeader.ofUtf8("x-real", "2"))),
                    completion.record().headers());
        }

        @Test
        @DisplayName("a null record key is exposed as null")
        void nullKey(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(recorder));

            fixture.process(record(1L, null, null, null));

            assertNull(assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                    .record()
                    .key());
        }
    }

    // --- One faithful header collection for the whole pipeline ---

    @Nested
    @DisplayName("the view keeps every header, and the whole pipeline is given that same header collection")
    class RecordHeaders {

        /** Two bytes that are never valid in UTF-8. */
        private final byte[] notUtf8 = {(byte) 0xFF, (byte) 0xFE};

        /**
         * Interleaved duplicates, a null value, a null that follows a value for the same key, an empty
         * value and a binary value.
         */
        private List<KafkaHeader> wireHeaders() {
            return List.of(
                    KafkaHeader.header("a", "1"),
                    KafkaHeader.header("b", "x"),
                    KafkaHeader.header("a", "2"),
                    KafkaHeader.header("nulled", (Buffer) null),
                    KafkaHeader.header("empty", Buffer.buffer()),
                    KafkaHeader.header("bin", Buffer.buffer(notUtf8)),
                    KafkaHeader.header("b", (Buffer) null));
        }

        /** The text projection of {@link #wireHeaders()}, written out by hand. */
        private Map<String, String> textMap() {
            String replacement = String.valueOf((char) 0xFFFD);
            Map<String, String> expected = new HashMap<>();
            expected.put("a", "2");
            expected.put("b", "x");
            expected.put("empty", "");
            expected.put("bin", replacement + replacement);
            return expected;
        }

        @Test
        @DisplayName("duplicate, null, empty and binary headers reach the view in order, byte for byte")
        void viewKeepsEveryHeader(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(recorder));

            fixture.process(record(1L, "k", null, wireHeaders()));

            KafkaRecordHeaders headers = assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                    .record()
                    .headers();
            List<KafkaRecordHeader> entries = headers.entries();
            assertEquals(
                    List.of("a", "b", "a", "nulled", "empty", "bin", "b"),
                    entries.stream().map(KafkaRecordHeader::key).toList());
            assertEquals(Buffer.buffer(bytes("1")), entries.get(0).value());
            assertEquals(Buffer.buffer(bytes("x")), entries.get(1).value());
            assertEquals(Buffer.buffer(bytes("2")), entries.get(2).value());
            assertNull(entries.get(3).value());
            assertEquals(Buffer.buffer(), entries.get(4).value());
            assertEquals(
                    Buffer.buffer(new byte[] {(byte) 0xFF, (byte) 0xFE}),
                    entries.get(5).value());
            assertNull(entries.get(6).value());
            assertEquals(textMap(), headers.asMap());
        }

        @Test
        @DisplayName("changing a wire header buffer after the record was received does not change the view")
        void wireBufferMutatedAfterReceipt(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(recorder));
            Buffer wireValue = Buffer.buffer(new byte[] {1, 2, 3});

            fixture.process(record(1L, "k", null, List.of(KafkaHeader.header("x-bin", wireValue))));

            KafkaRecordHeaders headers = assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                    .record()
                    .headers();
            assertEquals(
                    Buffer.buffer(new byte[] {1, 2, 3}),
                    headers.entries().get(0).value());

            // The consumer record caches this buffer and handler-kind consumers also receive it.
            wireValue.setByte(0, (byte) 9);
            wireValue.appendByte((byte) 4);

            assertEquals(
                    Buffer.buffer(new byte[] {1, 2, 3}),
                    headers.entries().get(0).value());
            assertEquals(
                    new KafkaRecordHeaders(
                            List.of(new KafkaRecordHeader("x-bin", Buffer.buffer(new byte[] {1, 2, 3})))),
                    headers);
        }

        /** Asserts that {@code headers} is {@link #wireHeaders()} entry for entry, byte for byte. */
        private void assertWireHeadersIntact(KafkaRecordHeaders headers) {
            assertEquals(
                    new KafkaRecordHeaders(List.of(
                            KafkaRecordHeader.ofUtf8("a", "1"),
                            KafkaRecordHeader.ofUtf8("b", "x"),
                            KafkaRecordHeader.ofUtf8("a", "2"),
                            new KafkaRecordHeader("nulled", null),
                            new KafkaRecordHeader("empty", Buffer.buffer()),
                            new KafkaRecordHeader("bin", Buffer.buffer(new byte[] {(byte) 0xFF, (byte) 0xFE})),
                            new KafkaRecordHeader("b", null))),
                    headers);
        }

        @Test
        @DisplayName("the filter, the deserializer, the dispatch context and the handler's message hold the "
                + "header collection of the view, with every header intact")
        void handlerPipelineSharesTheViewHeaders(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            DispatchContextRecorder contexts = new DispatchContextRecorder();
            AtomicReference<KafkaRecordHeaders> seenByFilter = new AtomicReference<>();
            AtomicReference<KafkaRecordHeaders> seenByDeserializer = new AtomicReference<>();
            AtomicReference<KafkaRecordHeaders> seenByHandler = new AtomicReference<>();
            KafkaRecordFilter filter = (key, headers) -> {
                seenByFilter.set(headers);
                return true;
            };
            KafkaDeserializer<Object> deserializer = (data, topic, headers) -> {
                seenByDeserializer.set(headers);
                return "decoded";
            };
            KafkaRecordHandler<Object> handler = message -> {
                seenByHandler.set(message.headers());
                return Future.succeededFuture();
            };
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, handler, deserializer, filter);
            Fixture fixture = wire(vertx, entry, List.of(contexts, recorder));

            fixture.process(record(1L, "k", bytes("payload"), wireHeaders()));

            KafkaRecordHeaders view = assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                    .record()
                    .headers();
            assertWireHeadersIntact(view);
            assertSame(view, seenByFilter.get(), "filter");
            assertSame(view, seenByDeserializer.get(), "deserializer");
            assertSame(view, seenByHandler.get(), "handler message");
            assertEquals(1, contexts.contexts.size(), "the dispatch context must have been observed once");
            assertSame(view, contexts.contexts.get(0).headers(), "dispatch context");
        }

        @Test
        @DisplayName("on event-bus dispatch the record context in the envelope, the dispatch context, the "
                + "filter and the deserializer hold the header collection of the view")
        void eventBusPipelineSharesTheViewHeaders(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            DispatchContextRecorder contexts = new DispatchContextRecorder();
            AtomicReference<KafkaRecordHeaders> seenByFilter = new AtomicReference<>();
            AtomicReference<KafkaRecordHeaders> seenByDeserializer = new AtomicReference<>();
            KafkaRecordFilter filter = (key, headers) -> {
                seenByFilter.set(headers);
                return true;
            };
            KafkaDeserializer<Object> deserializer = (data, topic, headers) -> {
                seenByDeserializer.set(headers);
                return "decoded";
            };
            ConsumerEntry base = bindingEntry(ErrorStrategy.SKIP, null);
            ConsumerEntry entry = new ConsumerEntry(
                    base.name(),
                    base.config(),
                    ConsumerEntry.Kind.BINDING,
                    Object.class,
                    "addr",
                    null,
                    true,
                    List.of(),
                    null,
                    deserializer,
                    false,
                    filter,
                    base.valueFormat());
            Dispatches dispatches = new Dispatches();
            Fixture fixture = wire(
                    vertx,
                    entry,
                    List.of(contexts, recorder),
                    mock(KafkaProducerFactory.class),
                    null,
                    recordingDispatcher(entry, dispatches));

            processOnDuplicatedContext(vertx, fixture, record(1L, "k", bytes("payload"), wireHeaders()));

            KafkaRecordHeaders view = assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                    .record()
                    .headers();
            assertWireHeadersIntact(view);
            assertEquals(List.of("addr"), dispatches.addresses());
            assertEquals("decoded", dispatches.envelopes().get(0).payload());
            assertSame(view, dispatches.onlyRecordContext().headers(), "record context in the envelope");
            assertSame(view, seenByFilter.get(), "filter");
            assertSame(view, seenByDeserializer.get(), "deserializer");
            assertEquals(1, contexts.contexts.size(), "the dispatch context must have been observed once");
            assertSame(view, contexts.contexts.get(0).headers(), "dispatch context");
        }

        @Test
        @DisplayName("without interceptors the filter and the handler still share one faithful header collection")
        void sharedHeadersWithoutInterceptors(Vertx vertx) throws ReflectiveOperationException {
            AtomicReference<KafkaRecordHeaders> seenByFilter = new AtomicReference<>();
            AtomicReference<KafkaRecordHeaders> seenByHandler = new AtomicReference<>();
            KafkaRecordFilter filter = (key, headers) -> {
                seenByFilter.set(headers);
                return true;
            };
            KafkaRecordHandler<Object> handler = message -> {
                seenByHandler.set(message.headers());
                return Future.succeededFuture();
            };
            Fixture fixture = wire(vertx, handlerEntry(ErrorStrategy.SKIP, null, handler, null, filter), List.of());

            fixture.process(record(1L, "k", null, wireHeaders()));

            assertWireHeadersIntact(seenByFilter.get());
            assertSame(seenByFilter.get(), seenByHandler.get());
            assertEquals(textMap(), seenByFilter.get().asMap());
            assertEquals(0, fixture.inFlight(), "the in-flight slot must be released");
        }

        /** A header value that is {@code null}. */
        private KafkaHeader nullValued(String key) {
            return KafkaHeader.header(key, (Buffer) null);
        }

        /** Sends one tombstone record with the given headers through an event-bus consumer. */
        private Dispatches dispatched(Vertx vertx, ConsumerEntry entry, List<KafkaHeader> headers)
                throws ReflectiveOperationException {
            Dispatches dispatches = new Dispatches();
            Fixture fixture = wire(
                    vertx,
                    entry,
                    List.of(),
                    mock(KafkaProducerFactory.class),
                    null,
                    recordingDispatcher(entry, dispatches));
            processOnDuplicatedContext(vertx, fixture, record(1L, "k", null, headers));
            assertEquals(0, fixture.inFlight(), "the in-flight slot must be released");
            return dispatches;
        }

        @Test
        @DisplayName("the correlation id is the last non-null x-correlation-id value, decoded leniently")
        void correlationIdKeepsItsTextRule(Vertx vertx) throws ReflectiveOperationException {
            ConsumerEntry entry = bindingEntry(ErrorStrategy.SKIP, null);
            String name = "x-correlation-id";

            assertEquals(
                    "second",
                    dispatched(
                                    vertx,
                                    entry,
                                    List.of(
                                            KafkaHeader.header(name, "first"),
                                            KafkaHeader.header("other", "x"),
                                            KafkaHeader.header(name, "second")))
                            .onlyRecordContext()
                            .correlationId(),
                    "duplicate");
            assertEquals(
                    "value",
                    dispatched(vertx, entry, List.of(nullValued(name), KafkaHeader.header(name, "value")))
                            .onlyRecordContext()
                            .correlationId(),
                    "null then value");
            assertEquals(
                    "value",
                    dispatched(vertx, entry, List.of(KafkaHeader.header(name, "value"), nullValued(name)))
                            .onlyRecordContext()
                            .correlationId(),
                    "value then null");
            String replacement = String.valueOf((char) 0xFFFD);
            assertEquals(
                    replacement + replacement,
                    dispatched(vertx, entry, List.of(KafkaHeader.header(name, Buffer.buffer(notUtf8))))
                            .onlyRecordContext()
                            .correlationId(),
                    "malformed UTF-8");
            // A header with only a null value is no correlation id: one is generated.
            String generated = dispatched(vertx, entry, List.of(nullValued(name)))
                    .onlyRecordContext()
                    .correlationId();
            assertEquals(36, generated.length(), "a generated id is a UUID: " + generated);
        }

        @Test
        @DisplayName(
                "an x-correlation-id header with an empty value gives an empty correlation id, not a generated one")
        void emptyCorrelationIdHeaderIsKept(Vertx vertx) throws ReflectiveOperationException {
            ConsumerEntry entry = bindingEntry(ErrorStrategy.SKIP, null);

            assertEquals(
                    "",
                    dispatched(vertx, entry, List.of(KafkaHeader.header("x-correlation-id", Buffer.buffer())))
                            .onlyRecordContext()
                            .correlationId());
        }

        @Test
        @DisplayName("header routing matches the last non-null value of the route header, decoded leniently")
        void headerRoutingKeepsItsTextRule(Vertx vertx) throws ReflectiveOperationException {
            String replacement = String.valueOf((char) 0xFFFD);
            ConsumerEntry base = bindingEntry(ErrorStrategy.SKIP, null);
            ConsumerEntry entry = new ConsumerEntry(
                    base.name(),
                    base.config(),
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(
                            new ConsumerEntry.RouteEntry(
                                    "event-type", "", "created", false, Void.class, "addr.created", null, true),
                            new ConsumerEntry.RouteEntry(
                                    "event-type", "", "updated", false, Void.class, "addr.updated", null, true),
                            new ConsumerEntry.RouteEntry(
                                    "event-type",
                                    "",
                                    replacement + replacement,
                                    false,
                                    Void.class,
                                    "addr.replaced",
                                    null,
                                    true)),
                    null,
                    null,
                    false,
                    null,
                    base.valueFormat());
            String name = "event-type";

            assertEquals(
                    List.of("addr.updated"),
                    dispatched(
                                    vertx,
                                    entry,
                                    List.of(
                                            KafkaHeader.header(name, "created"),
                                            KafkaHeader.header("other", "x"),
                                            KafkaHeader.header(name, "updated")))
                            .addresses(),
                    "duplicate");
            assertEquals(
                    List.of("addr.created"),
                    dispatched(vertx, entry, List.of(nullValued(name), KafkaHeader.header(name, "created")))
                            .addresses(),
                    "null then value");
            assertEquals(
                    List.of("addr.updated"),
                    dispatched(vertx, entry, List.of(KafkaHeader.header(name, "updated"), nullValued(name)))
                            .addresses(),
                    "value then null");
            assertEquals(
                    List.of("addr.replaced"),
                    dispatched(vertx, entry, List.of(KafkaHeader.header(name, Buffer.buffer(notUtf8))))
                            .addresses(),
                    "malformed UTF-8");
            assertEquals(
                    List.of(),
                    dispatched(vertx, entry, List.of(nullValued(name))).addresses(),
                    "a header with only a null value matches no route");
        }

        @Test
        @DisplayName("durable context is decoded from the last non-null value of each context header; a "
                + "malformed one is skipped")
        void durableContextKeepsItsTextRule(Vertx vertx) throws ReflectiveOperationException {
            ConsumerEntry entry = bindingEntry(ErrorStrategy.SKIP, null);

            Dispatches dispatches = dispatched(
                    vertx,
                    entry,
                    List.of(
                            KafkaHeader.header("vertique-correlation", "{\"id\":\"first\"}"),
                            nullValued("vertique-tenant"),
                            KafkaHeader.header("vertique-actor", "{\"id\":\"u1\"}"),
                            KafkaHeader.header("vertique-correlation", "{\"id\":\"last\"}"),
                            KafkaHeader.header("vertique-tenant", "{\"id\":\"acme\"}"),
                            nullValued("vertique-actor"),
                            KafkaHeader.header("vertique-binary", Buffer.buffer(notUtf8)),
                            nullValued("vertique-cleared"),
                            KafkaHeader.header("event-type", "{\"id\":\"not-context\"}")));

            assertEquals(
                    List.of(DurableMetadata.empty()
                            .with("correlation", new JsonObject().put("id", "last"))
                            .with("tenant", new JsonObject().put("id", "acme"))
                            .with("actor", new JsonObject().put("id", "u1"))),
                    dispatches.bound());
        }

        @Test
        @DisplayName("a header with a null key is skipped: the record is processed and its slot released")
        void nullKeyHeaderIsSkipped(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicReference<KafkaRecordHeaders> seenByFilter = new AtomicReference<>();
            AtomicInteger handled = new AtomicInteger();
            KafkaRecordFilter filter = (key, headers) -> {
                seenByFilter.set(headers);
                return true;
            };
            KafkaRecordHandler<Object> handler = message -> {
                handled.incrementAndGet();
                return Future.succeededFuture();
            };
            Fixture fixture =
                    wire(vertx, handlerEntry(ErrorStrategy.SKIP, null, handler, null, filter), List.of(recorder));

            fixture.process(record(
                    1L,
                    "k",
                    null,
                    List.of(
                            KafkaHeader.header("a", "1"),
                            KafkaHeader.header(null, "orphan"),
                            KafkaHeader.header("b", "2"))));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            assertEquals(1, handled.get(), "the record must be dispatched normally");
            assertEquals(
                    new KafkaRecordHeaders(
                            List.of(KafkaRecordHeader.ofUtf8("a", "1"), KafkaRecordHeader.ofUtf8("b", "2"))),
                    completion.record().headers());
            assertSame(completion.record().headers(), seenByFilter.get());
            assertEquals(Map.of("a", "1", "b", "2"), seenByFilter.get().asMap());
            assertEquals(0, fixture.inFlight(), "the in-flight slot must be released");
        }

        @Test
        @DisplayName("a record without headers has the shared empty header collection")
        void noHeaders(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(recorder));

            fixture.process(record(1L, null));

            assertSame(
                    KafkaRecordHeaders.empty(),
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                            .record()
                            .headers());
        }
    }

    // --- Record value ---

    @Nested
    @DisplayName("the view's value is the delivered array, uncopied")
    class RecordValue {

        @Test
        @DisplayName("the deserializer receives the record's own array and the view reads the same bytes")
        void valueIsTheDeliveredArray(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicReference<byte[]> seenByDeserializer = new AtomicReference<>();
            KafkaDeserializer<Object> deserializer = (data, topic, headers) -> {
                seenByDeserializer.set(data);
                return "decoded";
            };
            ConsumerEntry entry =
                    handlerEntry(ErrorStrategy.SKIP, null, message -> Future.succeededFuture(), deserializer, null);
            Fixture fixture = wire(vertx, entry, List.of(recorder));
            byte[] delivered = bytes("payload");

            fixture.process(record(1L, delivered));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            assertSame(delivered, seenByDeserializer.get(), "the framework must not copy the value");
            assertArrayEquals(bytes("payload"), read(completion.record()));
        }

        @Test
        @DisplayName("an in-place edit by the deserializer is visible through the view (not a snapshot)")
        void inPlaceEditIsVisible(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            KafkaDeserializer<Object> editing = (data, topic, headers) -> {
                data[0] = 'X';
                return "decoded";
            };
            ConsumerEntry entry =
                    handlerEntry(ErrorStrategy.SKIP, null, message -> Future.succeededFuture(), editing, null);
            Fixture fixture = wire(vertx, entry, List.of(recorder));

            fixture.process(record(1L, bytes("payload")));

            assertArrayEquals(
                    bytes("Xayload"),
                    read(assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                            .record()));
        }

        @Test
        @DisplayName("a skipped record still exposes its value")
        void skippedRecordExposesValue(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, null, null, (key, headers) -> false);
            Fixture fixture = wire(vertx, entry, List.of(recorder));

            fixture.process(record(1L, bytes("payload")));

            assertArrayEquals(
                    bytes("payload"),
                    read(assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP)
                            .record()));
        }

        @Test
        @DisplayName("a tombstone has an absent value")
        void tombstoneIsAbsent(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(recorder));

            fixture.process(record(1L, null));

            assertSame(
                    PayloadSources.absent(),
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS)
                            .record()
                            .value());
        }
    }

    // --- Error isolation ---

    @Nested
    @DisplayName("a throwing observer cannot change a record's fate")
    class ErrorIsolation {

        private void assertCompletionThrowIsIsolated(Vertx vertx, Throwable thrown)
                throws ReflectiveOperationException {
            AtomicInteger thrownCount = new AtomicInteger();
            KafkaConsumerInterceptor throwing = new KafkaConsumerInterceptor() {
                @Override
                public void onRecordCompleted(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {
                    thrownCount.incrementAndGet();
                    sneakyThrow(thrown);
                }
            };
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(throwing, recorder));

            fixture.process(record(1L, null));
            fixture.process(record(2L, null));

            assertEquals(2, thrownCount.get(), "the throwing interceptor must have been called for both records");
            assertEquals(2, recorder.completions.size(), "the later interceptor must see both records");
            assertEquals(
                    List.of(KafkaTerminalOutcome.SUCCESS, KafkaTerminalOutcome.SUCCESS),
                    recorder.completions.stream().map(c -> c.event().outcome()).toList());
            assertEquals(
                    List.of(1L, 2L),
                    recorder.completions.stream()
                            .map(c -> c.event().identity().offset())
                            .toList());
            verify(fixture.consumer(), times(2)).commit(anyMap());
            assertEquals(0, fixture.inFlight());
        }

        @Test
        @DisplayName("onRecordCompleted throwing RuntimeException stops neither later interceptors nor later records")
        void completionThrowsRuntimeException(Vertx vertx) throws ReflectiveOperationException {
            assertCompletionThrowIsIsolated(vertx, new RuntimeException("completion boom"));
        }

        @Test
        @DisplayName("onRecordCompleted throwing AssertionError stops neither later interceptors nor later records")
        void completionThrowsAssertionError(Vertx vertx) throws ReflectiveOperationException {
            assertCompletionThrowIsIsolated(vertx, new AssertionError("completion boom"));
        }

        @Test
        @DisplayName("onRecordCompleted throwing LinkageError stops neither later interceptors nor later records")
        void completionThrowsLinkageError(Vertx vertx) throws ReflectiveOperationException {
            assertCompletionThrowIsIsolated(vertx, new NoClassDefFoundError("completion boom"));
        }

        @Test
        @DisplayName("onRecord throwing AssertionError still ends in one completion and a released slot")
        void onRecordThrowsAssertionError(Vertx vertx) throws ReflectiveOperationException {
            KafkaConsumerInterceptor throwing = new KafkaConsumerInterceptor() {
                @Override
                public void onRecord(KafkaDispatchContext<?> ctx) {
                    throw new AssertionError("onRecord boom");
                }
            };
            CompletionRecorder recorder = new CompletionRecorder();
            AtomicInteger handled = new AtomicInteger();
            ConsumerEntry entry = handlerEntry(
                    ErrorStrategy.SKIP,
                    null,
                    message -> {
                        handled.incrementAndGet();
                        return Future.succeededFuture();
                    },
                    null,
                    null);
            Fixture fixture = wire(vertx, entry, List.of(throwing, recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            assertEquals(1, handled.get(), "the record must still be dispatched");
        }

        @Test
        @DisplayName("onSuccess throwing AssertionError still ends in one completion and a released slot")
        void onSuccessThrowsAssertionError(Vertx vertx) throws ReflectiveOperationException {
            KafkaConsumerInterceptor throwing = new KafkaConsumerInterceptor() {
                @Override
                public void onSuccess(KafkaDispatchContext<?> ctx) {
                    throw new AssertionError("onSuccess boom");
                }
            };
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, succeedingHandlerEntry(), List.of(throwing, recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SUCCESS);
            verify(fixture.consumer()).commit(anyMap());
        }

        @Test
        @DisplayName("onError throwing AssertionError still ends in one completion and a released slot")
        void onErrorThrowsAssertionError(Vertx vertx) throws ReflectiveOperationException {
            KafkaConsumerInterceptor throwing = new KafkaConsumerInterceptor() {
                @Override
                public void onError(KafkaDispatchContext<?> ctx, Throwable error) {
                    throw new AssertionError("onError boom");
                }
            };
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, failingHandlerEntry(ErrorStrategy.SKIP, null), List.of(throwing, recorder));

            fixture.process(record(1L, null));

            assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.SKIP);
            verify(fixture.consumer()).commit(anyMap());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }

    // --- Redelivery ---

    @Nested
    @DisplayName("retry count")
    class RetryCount {

        @Test
        @DisplayName("a first delivery that fails to deserialize under RETRY reports retryCount 0")
        void firstDeliveryFailingDeserializationReportsRetryCountZero(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, bindingEntry(ErrorStrategy.RETRY, null), List.of(recorder));

            fixture.process(record(5L, bytes("payload")));

            CompletionRecorder.Completion completion =
                    assertCompletedOnce(fixture, recorder, KafkaTerminalOutcome.RETRY_SCHEDULED);
            assertEquals(0, completion.event().identity().retryCount());
            assertEquals(1, fixture.errors().retryCounts.get(TOPIC + ":3:5"), "the retry must have been counted");
        }

        @Test
        @DisplayName("a redelivered record reports retryCount 1")
        void redeliveredRecordReportsRetryCountOne(Vertx vertx) throws ReflectiveOperationException {
            CompletionRecorder recorder = new CompletionRecorder();
            Fixture fixture = wire(vertx, failingHandlerEntry(ErrorStrategy.RETRY, null), List.of(recorder));

            fixture.process(record(5L, null));
            fixture.process(record(5L, null));

            assertEquals(2, recorder.completions.size(), "each delivery completes once");
            assertEquals(0, recorder.completions.get(0).event().identity().retryCount());
            assertEquals(1, recorder.completions.get(1).event().identity().retryCount());
            assertEquals(
                    List.of(KafkaTerminalOutcome.RETRY_SCHEDULED, KafkaTerminalOutcome.RETRY_SCHEDULED),
                    recorder.completions.stream().map(c -> c.event().outcome()).toList());
        }
    }

    // --- No interceptors ---

    @Nested
    @DisplayName("a consumer with no interceptors")
    class NoInterceptors {

        @Test
        @DisplayName("dispatches, commits and releases the slot")
        void successWithoutInterceptors(Vertx vertx) throws ReflectiveOperationException {
            AtomicReference<Object> handledValue = new AtomicReference<>();
            ConsumerEntry entry = handlerEntry(
                    ErrorStrategy.SKIP,
                    null,
                    message -> {
                        handledValue.set(message.value());
                        return Future.succeededFuture();
                    },
                    null,
                    null);
            Fixture fixture = wire(vertx, entry, List.of());
            byte[] delivered = bytes("payload");

            fixture.process(record(1L, delivered));

            assertSame(delivered, handledValue.get(), "a byte[] handler receives the delivered array");
            verify(fixture.consumer()).commit(anyMap());
            assertEquals(0, fixture.inFlight());
        }

        @Test
        @DisplayName("applies the error strategy to a failed dispatch and releases the slot")
        void failureWithoutInterceptors(Vertx vertx) throws ReflectiveOperationException {
            Fixture fixture = wire(vertx, failingHandlerEntry(ErrorStrategy.SKIP, null), List.of());

            fixture.process(record(1L, null));

            verify(fixture.consumer()).commit(anyMap());
            assertEquals(0, fixture.inFlight());
        }

        @Test
        @DisplayName("commits a filtered record and releases the slot")
        void filteredWithoutInterceptors(Vertx vertx) throws ReflectiveOperationException {
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, null, null, (key, headers) -> false);
            Fixture fixture = wire(vertx, entry, List.of());

            fixture.process(record(1L, bytes("v")));

            verify(fixture.consumer()).commit(anyMap());
            assertEquals(0, fixture.inFlight());
        }

        @Test
        @DisplayName("applies the error strategy when the filter throws and releases the slot")
        void throwingFilterWithoutInterceptors(Vertx vertx) throws ReflectiveOperationException {
            ConsumerEntry entry = handlerEntry(ErrorStrategy.SKIP, null, null, null, (key, headers) -> {
                throw new IllegalStateException("filter boom");
            });
            Fixture fixture = wire(vertx, entry, List.of());

            fixture.process(record(1L, bytes("v")));

            verify(fixture.consumer()).commit(anyMap());
            assertEquals(0, fixture.inFlight());
        }
    }

    // --- Event records ---

    @Nested
    @DisplayName("event records reject null required components")
    class EventRecords {

        private final KafkaConsumerRecordIdentity identity = new KafkaConsumerRecordIdentity("c", "t", 0, 1L, 2L, 0);

        @Test
        @DisplayName("KafkaConsumerRecordIdentity rejects a null consumer name and a null topic")
        void identityRejectsNulls() {
            assertThrows(NullPointerException.class, () -> new KafkaConsumerRecordIdentity(null, "t", 0, 1L, 2L, 0));
            assertThrows(NullPointerException.class, () -> new KafkaConsumerRecordIdentity("c", null, 0, 1L, 2L, 0));
        }

        @Test
        @DisplayName("KafkaConsumerCompletedEvent rejects a null identity and a null outcome")
        void eventRejectsNulls() {
            assertThrows(
                    NullPointerException.class,
                    () -> new KafkaConsumerCompletedEvent(null, KafkaTerminalOutcome.SUCCESS));
            assertThrows(NullPointerException.class, () -> new KafkaConsumerCompletedEvent(identity, null));
        }
    }
}
