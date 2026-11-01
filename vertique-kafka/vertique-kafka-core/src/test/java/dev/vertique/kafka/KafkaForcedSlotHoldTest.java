// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ServiceRequestSender;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * Proves that a consumer which forces its strategies keeps a failed record's slot until the record's
 * error handling has settled, so no later record is delivered, handled or committed ahead of it.
 *
 * <p>The verticle runs offline over a mocked Kafka consumer; {@code BrokerStub} stands in for the
 * broker by recording whether the consumer is paused, which is the only thing that keeps the next
 * record from being delivered. The one-at-a-time limit is on, as registration sets it for a forcing
 * binding.
 *
 * <p>Verified:
 * <ul>
 *   <li>At every point where a failed record is handed to the error strategy (a filter that throws,
 *       a throw out of dispatch, an undecodable value, a failing before-dispatch interceptor, a
 *       failed handler future), a dead-letter publish held open keeps the consumer paused and the
 *       next record undelivered until it settles; only then is the record committed and the
 *       consumer resumed.</li>
 *   <li>An asynchronous {@code recoverError} interceptor is inside the same hold, and a recovered
 *       record commits before its slot is released.</li>
 *   <li>A dead-letter publish that fails seeks back and keeps the consumer paused through the
 *       backoff, though the slot was released; the redelivered record then publishes, and the
 *       only offset ever committed is the failed record's own successor.</li>
 *   <li>A retry on a forcing binding pauses, seeks and resumes once, after the backoff; an entry
 *       that does not force its strategies still releases its slot before its error handling
 *       settles.</li>
 *   <li>A fault at each step of the failure handling (a send that throws or throws an
 *       {@link Error}, a pause that throws, a seek that throws, throws an {@link Error} or fails, a
 *       fallback that fails too) never commits and never resumes before a seek back is confirmed; when
 *       none can be confirmed the slot stays held and the consumer paused.</li>
 *   <li>A held slot is released when its partition is no longer assigned, so the consumer does not
 *       stay paused for good; a stop with a held slot, through a registered forced {@code MANUAL} binding, commits
 *       nothing.</li>
 *   <li>A binding that forces its strategies on the default {@code AUTO} commit is refused.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class KafkaForcedSlotHoldTest {

    private static final String TOPIC = "t";
    private static final int PARTITION = 3;
    private static final long FAILED_OFFSET = 5L;
    private static final long BACKOFF_MS = 50L;
    private static final TopicPartition POSITION = new TopicPartition(TOPIC, PARTITION);

    // --- Doubles ---

    /** The one thing the broker does that the proofs read: it delivers only while the consumer is not paused. */
    static final class BrokerStub {

        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicInteger resumes = new AtomicInteger();
    }

    /** The verticle under test and what it is wired to. */
    record Rig(
            KafkaConsumerVerticle verticle,
            KafkaConsumer<String, byte[]> consumer,
            KafkaErrorHandler errors,
            BrokerStub broker,
            KafkaProducerFactory producer) {

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

    /** Interceptor whose {@code recoverError} settles when the test says so. */
    static final class HeldRecovery implements KafkaConsumerInterceptor {

        final Promise<Void> recovery = Promise.promise();

        @Override
        public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
            return recovery.future();
        }
    }

    /** Interceptor whose {@code beforeDispatch} fails. */
    static final class FailingBeforeDispatch implements KafkaConsumerInterceptor {

        @Override
        public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
            return Future.failedFuture(new RuntimeException("before-dispatch boom"));
        }
    }

    // --- Builders ---

    /**
     * Builds a handler entry on the resolved configuration of a retrying consumer with a short
     * backoff and one retry, dead-lettering once the retry is used.
     */
    private static ConsumerEntry entry(
            boolean force,
            ErrorStrategy strategy,
            @Nullable KafkaRecordHandler<?> handler,
            @Nullable KafkaRecordFilter filter) {
        JsonObject consumer = new JsonObject()
                .put("topic", TOPIC)
                .put("groupId", "grp")
                .put("enabled", true)
                .put(
                        "retry",
                        new JsonObject()
                                .put("maxRetries", 1)
                                .put("backoffMs", BACKOFF_MS)
                                .put("maxBackoffMs", 1_000L)
                                .put("exhaustedStrategy", "DEAD_LETTER"));
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject()
                        .put("kafka", new JsonObject().put("consumers", new JsonObject().put("test", consumer))),
                new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                "test",
                TOPIC,
                "grp",
                true,
                CommitStrategy.MANUAL,
                strategy,
                "dlq",
                0L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get("test"));
        return new ConsumerEntry(
                "test",
                config.oneRecordAtATime(),
                ConsumerEntry.Kind.HANDLER,
                Object.class,
                null,
                null,
                false,
                List.of(),
                handler,
                null,
                false,
                filter,
                KafkaSerdeRegistry.DEFAULT_FORMAT,
                null,
                false,
                force);
    }

    private static Rig wire(
            Vertx vertx,
            ConsumerEntry entry,
            List<KafkaConsumerInterceptor> interceptors,
            KafkaProducerFactory producer)
            throws ReflectiveOperationException {
        return wire(vertx, entry, interceptors, producer, 10_000L);
    }

    @SuppressWarnings("unchecked")
    private static Rig wire(
            Vertx vertx,
            ConsumerEntry entry,
            List<KafkaConsumerInterceptor> interceptors,
            KafkaProducerFactory producer,
            long drainTimeoutMs)
            throws ReflectiveOperationException {
        KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                entry,
                interceptors,
                producer,
                mock(ServiceRequestSender.class),
                KafkaTestSupport.noOpTargetResolver(),
                KafkaTestSupport.eventBusClient(vertx),
                KafkaTestSupport.noOpInboundExecutionContextScope(),
                KafkaTestSupport.noOpEnvelopeBuilder(),
                KafkaTestSupport.jsonSerdeRegistry(),
                drainTimeoutMs,
                10_000L);
        verticle.init(vertx, vertx.getOrCreateContext());

        BrokerStub broker = new BrokerStub();
        KafkaConsumer<String, byte[]> consumer = mock(KafkaConsumer.class);
        when(consumer.commit(anyMap())).thenReturn(Future.succeededFuture(Map.of()));
        when(consumer.seek(any(), anyLong())).thenReturn(Future.succeededFuture());
        when(consumer.assignment()).thenReturn(Future.succeededFuture(Set.of(POSITION)));
        when(consumer.close()).thenReturn(Future.succeededFuture());
        doAnswer(invocation -> {
                    broker.paused.set(true);
                    return invocation.getMock();
                })
                .when(consumer)
                .pause();
        doAnswer(invocation -> {
                    broker.paused.set(false);
                    broker.resumes.incrementAndGet();
                    return invocation.getMock();
                })
                .when(consumer)
                .resume();

        KafkaErrorHandler errors = new KafkaErrorHandler(entry, producer);
        set(verticle, "consumer", consumer);
        set(verticle, "errorHandler", errors);
        set(
                verticle,
                "dispatcher",
                new KafkaRecordDispatcher(
                        entry,
                        Map.of(),
                        KafkaTestSupport.jsonSerdeRegistry(),
                        mock(ServiceRequestSender.class),
                        KafkaTestSupport.noOpTargetResolver(),
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder()));
        set(verticle, "interceptorChain", new KafkaConsumerInterceptorChain(entry.name(), interceptors));
        return new Rig(verticle, consumer, errors, broker, producer);
    }

    private static void set(KafkaConsumerVerticle verticle, String name, Object value)
            throws ReflectiveOperationException {
        Field field = KafkaConsumerVerticle.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(verticle, value);
    }

    /** A dead-letter producer whose every send is answered by the given future. */
    private static KafkaProducerFactory producerAnswering(Future<RecordMetadata> answer) {
        KafkaProducerFactory producer = mock(KafkaProducerFactory.class);
        when(producer.sendForDlq(anyString(), any(), any(), any())).thenReturn(answer);
        return producer;
    }

    @SuppressWarnings("unchecked")
    private static KafkaConsumerRecord<String, byte[]> record(long offset, @Nullable byte[] value) {
        KafkaConsumerRecord<String, byte[]> record = mock(KafkaConsumerRecord.class);
        when(record.topic()).thenReturn(TOPIC);
        when(record.partition()).thenReturn(PARTITION);
        when(record.offset()).thenReturn(offset);
        when(record.timestamp()).thenReturn(1_700_000_000_000L);
        when(record.key()).thenReturn("k");
        when(record.value()).thenReturn(value);
        when(record.headers()).thenReturn(null);
        return record;
    }

    /** A handler that records the offsets it is given and fails the failing offset. */
    private static KafkaRecordHandler<Object> handlerFailing(long failingOffset, List<Long> handled) {
        return message -> {
            handled.add(message.offset());
            return message.offset() == failingOffset
                    ? Future.failedFuture(new RuntimeException("handler boom"))
                    : Future.succeededFuture();
        };
    }

    /** A forced entry, RETRY-strategy, whose retry for the failed record is already used up. */
    private static void useUpRetries(Rig rig) {
        rig.errors().retryCounts.put(TOPIC + ":" + PARTITION + ":" + FAILED_OFFSET, 1);
    }

    private static String committed(long nextOffset) {
        return TOPIC + ":" + PARTITION + ":" + nextOffset;
    }

    /** Every offset committed so far, in order, as {@code topic:partition:offset}. */
    @SuppressWarnings("unchecked")
    private static List<String> committedOffsets(Rig rig) {
        List<String> offsets = new ArrayList<>();
        for (Invocation invocation : Mockito.mockingDetails(rig.consumer()).getInvocations()) {
            if (invocation.getMethod().getName().equals("commit")) {
                Map<TopicPartition, OffsetAndMetadata> commit = invocation.getArgument(0);
                commit.forEach((position, offset) ->
                        offsets.add(position.getTopic() + ":" + position.getPartition() + ":" + offset.getOffset()));
            }
        }
        return offsets;
    }

    // --- Proofs ---

    @Test
    @DisplayName("a held dead-letter send keeps the next record undelivered at every point a failure is handled")
    void heldDeadLetterSendKeepsTheNextRecordUndelivered(Vertx vertx) throws Exception {
        // The sites differ in how the failure reaches the error strategy; each row sets one up.
        record Site(String label) {}
        List<Site> sites = List.of(
                new Site("filter that throws"),
                new Site("throw out of dispatch"),
                new Site("undecodable value"),
                new Site("failing before-dispatch interceptor"),
                new Site("failed handler future"));

        for (Site site : sites) {
            // Given: a forcing entry whose dead-letter send is held open, and a record that fails at this site
            Promise<RecordMetadata> send = Promise.promise();
            KafkaProducerFactory producer = producerAnswering(send.future());
            List<Long> handled = new CopyOnWriteArrayList<>();
            KafkaConsumerRecord<String, byte[]> failing = record(FAILED_OFFSET, null);
            ConsumerEntry entry;
            List<KafkaConsumerInterceptor> interceptors = List.of();
            switch (site.label()) {
                case "filter that throws" ->
                    entry = entry(true, ErrorStrategy.RETRY, null, (key, headers) -> {
                        throw new IllegalStateException("filter boom");
                    });
                case "undecodable value" -> {
                    entry = entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, handled), null);
                    failing = record(FAILED_OFFSET, "not decodable".getBytes(StandardCharsets.UTF_8));
                }
                case "failing before-dispatch interceptor" -> {
                    entry = entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, handled), null);
                    interceptors = List.of(new FailingBeforeDispatch());
                }
                default -> entry = entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, handled), null);
            }
            Rig rig = wire(vertx, entry, interceptors, producer);
            if (site.label().equals("throw out of dispatch")) {
                KafkaConsumerInterceptorChain throwing = mock(KafkaConsumerInterceptorChain.class);
                when(throwing.runBeforeInterceptors(any())).thenThrow(new IllegalStateException("chain boom"));
                set(rig.verticle(), "interceptorChain", throwing);
            }
            useUpRetries(rig);

            // When: the record is handled and its failure goes to the dead-letter send, which stays open
            rig.process(failing);

            // Then: the send was started, and the slot is held, the consumer paused and nothing committed
            String where = " at the " + site.label();
            verify(producer, times(1)).sendForDlq(anyString(), any(), any(), any());
            assertEquals(1, rig.inFlight(), "the slot must be held while the send is open" + where);
            assertTrue(rig.broker().paused.get(), "the consumer must stay paused while the send is open" + where);
            assertEquals(0, rig.broker().resumes.get(), "nothing may resume the consumer" + where);
            assertEquals(List.of(), committedOffsets(rig), "nothing may be committed");

            // When: the send settles
            send.complete(null);

            // Then: the record is committed, then the slot is released and the consumer resumes, once
            assertEquals(List.of(committed(FAILED_OFFSET + 1)), committedOffsets(rig));
            assertEquals(0, rig.inFlight(), "the slot must be released once the send settled" + where);
            assertFalse(rig.broker().paused.get(), "the consumer must resume once the send settled" + where);
            assertEquals(1, rig.broker().resumes.get(), "the consumer must resume exactly once" + where);
        }
    }

    @Test
    @DisplayName("an asynchronous recoverError is inside the hold, and a recovery commits before the slot is released")
    void asynchronousRecoveryIsInsideTheHold(Vertx vertx) throws Exception {
        // Given: a forcing entry whose handler fails, an interceptor whose recoverError is held open and a
        // dead-letter send that is held open too
        HeldRecovery recovering = new HeldRecovery();
        Promise<RecordMetadata> send = Promise.promise();
        KafkaProducerFactory producer = producerAnswering(send.future());
        Rig rig = wire(
                vertx,
                entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, new ArrayList<>()), null),
                List.of(recovering),
                producer);
        useUpRetries(rig);

        // When: the record fails and the recovery has not answered
        rig.process(record(FAILED_OFFSET, null));

        // Then: the slot is held, the consumer paused, nothing sent and nothing committed
        assertEquals(1, rig.inFlight(), "the slot must be held while the recovery is open");
        assertTrue(rig.broker().paused.get(), "the consumer must stay paused while the recovery is open");
        assertEquals(0, rig.broker().resumes.get());
        verify(producer, never()).sendForDlq(anyString(), any(), any(), any());

        // When: the recovery declines, so the error strategy takes the record and its send is still open
        recovering.recovery.fail(new RuntimeException("not recovered"));

        // Then: the slot is still held and the consumer still paused
        verify(producer, times(1)).sendForDlq(anyString(), any(), any(), any());
        assertEquals(1, rig.inFlight(), "the slot must be held while the send is open");
        assertEquals(0, rig.broker().resumes.get());

        // When: the send settles
        send.complete(null);

        // Then: the record is committed and the consumer resumed
        assertEquals(List.of(committed(FAILED_OFFSET + 1)), committedOffsets(rig));
        assertEquals(0, rig.inFlight());
        assertEquals(1, rig.broker().resumes.get());

        // Given: a second record with a recovery that succeeds later
        HeldRecovery recovered = new HeldRecovery();
        Rig second = wire(
                vertx,
                entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, new ArrayList<>()), null),
                List.of(recovered),
                producerAnswering(Future.succeededFuture()));

        // When: it fails and is recovered
        second.process(record(FAILED_OFFSET, null));
        assertEquals(1, second.inFlight(), "the slot must be held while the recovery is open");
        assertEquals(0, second.broker().resumes.get());
        recovered.recovery.complete();

        // Then: it is committed and the slot released
        assertEquals(List.of(committed(FAILED_OFFSET + 1)), committedOffsets(second));
        assertEquals(0, second.inFlight());
        assertEquals(1, second.broker().resumes.get());
    }

    @Test
    @DisplayName("a failed dead-letter publish is redelivered after the backoff and nothing commits past the record")
    void failedPublishIsRedeliveredAndNothingCommitsPastIt(Vertx vertx) throws Exception {
        // Given: a forcing entry whose handler fails the record, and a dead-letter send that fails and then works
        KafkaProducerFactory producer = mock(KafkaProducerFactory.class);
        when(producer.sendForDlq(anyString(), any(), any(), any()))
                .thenReturn(Future.failedFuture(new RuntimeException("kafka-down")))
                .thenReturn(Future.succeededFuture());
        List<Long> handled = new CopyOnWriteArrayList<>();
        Rig rig = wire(
                vertx,
                entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, handled), null),
                List.of(),
                producer);
        useUpRetries(rig);

        // When: the record fails and its dead-letter send fails
        rig.process(record(FAILED_OFFSET, null));

        // Then: the consumer was sent back to the record, nothing was committed, and the slot is released
        // but the consumer stays paused through the backoff
        verify(rig.consumer(), times(1)).seek(POSITION, FAILED_OFFSET);
        assertEquals(List.of(), committedOffsets(rig), "nothing may be committed");
        assertEquals(0, rig.inFlight());
        assertTrue(rig.broker().paused.get(), "the released slot must not resume the consumer during the backoff");
        assertEquals(0, rig.broker().resumes.get());

        // When: the backoff is over
        verify(rig.consumer(), timeout(5_000).times(1)).resume();

        // Then: the consumer resumed once, and the record is delivered again
        assertFalse(rig.broker().paused.get());
        assertEquals(1, rig.broker().resumes.get());

        // When: the record is delivered again and its dead-letter send works
        rig.process(record(FAILED_OFFSET, null));

        // Then: it is dead-lettered and committed, and the only offset ever committed is the one after it
        assertEquals(List.of(committed(FAILED_OFFSET + 1)), committedOffsets(rig));
        verify(producer, times(2)).sendForDlq(anyString(), any(), any(), any());
        assertEquals(List.of(FAILED_OFFSET, FAILED_OFFSET), handled, "only the failed record may have been handled");
        assertEquals(0, rig.inFlight());
        assertEquals(2, rig.broker().resumes.get());
    }

    @Test
    @DisplayName("a retry on a forcing entry pauses, seeks and resumes once, after the backoff")
    void retryOnAForcingEntryResumesOnceAfterTheBackoff(Vertx vertx) throws Exception {
        // Given: a forcing entry whose handler fails the record, with its retry unused
        Rig rig = wire(
                vertx,
                entry(true, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, new ArrayList<>()), null),
                List.of(),
                producerAnswering(Future.succeededFuture()));

        // When: the record fails
        rig.process(record(FAILED_OFFSET, null));

        // Then: it was sought back to, nothing was committed or sent, the slot is released and the consumer is
        // paused for the backoff
        verify(rig.consumer(), times(1)).seek(POSITION, FAILED_OFFSET);
        assertEquals(List.of(), committedOffsets(rig), "nothing may be committed");
        verify(rig.producer(), never()).sendForDlq(anyString(), any(), any(), any());
        assertEquals(0, rig.inFlight());
        assertTrue(rig.broker().paused.get());
        assertEquals(0, rig.broker().resumes.get());

        // When: the backoff is over
        verify(rig.consumer(), timeout(5_000).times(1)).resume();

        // Then: the consumer resumed exactly once
        assertFalse(rig.broker().paused.get());
        assertEquals(1, rig.broker().resumes.get());
    }

    @Test
    @DisplayName(
            "an entry that does not force its strategies still releases its slot before its error handling settles")
    void unforcedEntryReleasesItsSlotAtOnce(Vertx vertx) throws Exception {
        // Given: an entry that does not force its strategies, one record at a time, and a held dead-letter send
        Promise<RecordMetadata> send = Promise.promise();
        Rig rig = wire(
                vertx,
                entry(false, ErrorStrategy.RETRY, handlerFailing(FAILED_OFFSET, new ArrayList<>()), null),
                List.of(),
                producerAnswering(send.future()));
        useUpRetries(rig);

        // When: the record fails and its dead-letter send stays open
        rig.process(record(FAILED_OFFSET, null));

        // Then: the slot was released and the consumer resumed before the send settled, as it always did
        verify(rig.producer(), times(1)).sendForDlq(anyString(), any(), any(), any());
        assertEquals(0, rig.inFlight());
        assertFalse(rig.broker().paused.get());
        assertEquals(1, rig.broker().resumes.get());

        // When: the send settles
        send.complete(null);

        // Then: the record is committed and nothing else resumes the consumer
        assertEquals(List.of(committed(FAILED_OFFSET + 1)), committedOffsets(rig));
        assertEquals(1, rig.broker().resumes.get());
    }

    // --- Faults at each step of the failure handling ---

    /** What one call of the consumer's seek does. */
    enum Seek {
        /** Answers with a future the test completes later. */
        HELD,
        /** Answers with a failed future, as a seek on an unassigned partition does. */
        FAIL,
        /** Throws an exception, as a seek on a closed consumer does. */
        THROW,
        /** Throws an {@link Error}. */
        ERROR
    }

    /** How the dead-letter send behaves. */
    enum Send {
        FAILS,
        THROWS,
        THROWS_ERROR
    }

    /**
     * One fault.
     *
     * @param label the row
     * @param send how the send behaves
     * @param pauseFault thrown by the consumer's pause when the failure is handled, or {@code null}
     * @param seeks what each call of the seek does; the last entry repeats
     * @param seekAttempts how many seeks are made before the test completes a held one
     * @param confirmed whether a seek is confirmed in the end
     */
    record Fault(
            String label,
            Send send,
            @Nullable Throwable pauseFault,
            List<Seek> seeks,
            int seekAttempts,
            boolean confirmed) {}

    private static List<Fault> faults() {
        return List.of(
                new Fault("a send that fails", Send.FAILS, null, List.of(Seek.HELD), 1, true),
                new Fault("a send that throws", Send.THROWS, null, List.of(Seek.HELD), 1, true),
                new Fault("a send that throws an Error", Send.THROWS_ERROR, null, List.of(Seek.HELD), 1, true),
                new Fault(
                        "a pause that throws",
                        Send.FAILS,
                        new IllegalStateException("pause"),
                        List.of(Seek.HELD),
                        1,
                        true),
                new Fault(
                        "a pause that throws an Error",
                        Send.FAILS,
                        new LinkageError("pause"),
                        List.of(Seek.HELD),
                        1,
                        true),
                new Fault(
                        "a first seek that fails, then a held one",
                        Send.FAILS,
                        null,
                        List.of(Seek.FAIL, Seek.HELD),
                        2,
                        true),
                new Fault("a seek that throws", Send.FAILS, null, List.of(Seek.THROW), 2, false),
                new Fault("a seek that throws an Error", Send.FAILS, null, List.of(Seek.ERROR), 2, false),
                new Fault("a seek that fails", Send.FAILS, null, List.of(Seek.FAIL), 2, false),
                new Fault("a fallback that fails too", Send.THROWS, null, List.of(Seek.FAIL, Seek.THROW), 2, false),
                new Fault(
                        "a fallback that throws an Error", Send.FAILS, null, List.of(Seek.FAIL, Seek.ERROR), 2, false));
    }

    /** Scripts the seek of a rig and returns the futures it handed out for {@link Seek#HELD}. */
    private static List<Promise<Void>> scriptSeeks(Rig rig, List<Seek> script, AtomicInteger attempts) {
        List<Promise<Void>> held = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    Seek next = script.get(Math.min(attempts.getAndIncrement(), script.size() - 1));
                    switch (next) {
                        case FAIL -> {
                            return Future.failedFuture(new IllegalStateException("No current assignment"));
                        }
                        case THROW -> throw new IllegalStateException("This consumer has already been closed");
                        case ERROR -> throw new LinkageError("seek");
                        default -> {
                            Promise<Void> promise = Promise.promise();
                            held.add(promise);
                            return promise.future();
                        }
                    }
                })
                .when(rig.consumer())
                .seek(any(TopicPartition.class), anyLong());
        return held;
    }

    private static void setPaused(Rig rig, boolean value) throws ReflectiveOperationException {
        Field field = KafkaConsumerVerticle.class.getDeclaredField("paused");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(rig.verticle())).set(value);
    }

    /**
     * Builds a forcing rig whose retries are used up, whose handler fails the record and which sends the
     * failed record to a dead-letter send that behaves as the fault says.
     */
    private static Rig faulty(Vertx vertx, Fault fault) throws ReflectiveOperationException {
        KafkaProducerFactory producer = mock(KafkaProducerFactory.class);
        switch (fault.send()) {
            case FAILS ->
                when(producer.sendForDlq(anyString(), any(), any(), any()))
                        .thenReturn(Future.failedFuture(new RuntimeException("kafka-down")));
            case THROWS ->
                when(producer.sendForDlq(anyString(), any(), any(), any()))
                        .thenThrow(new IllegalStateException("producer could not be built"));
            case THROWS_ERROR ->
                when(producer.sendForDlq(anyString(), any(), any(), any()))
                        .thenThrow(new LinkageError("producer could not be built"));
        }
        AtomicReference<Runnable> beforeFailing = new AtomicReference<>(() -> {});
        KafkaRecordHandler<Object> handler = message -> {
            beforeFailing.get().run();
            return Future.failedFuture(new RuntimeException("handler boom"));
        };
        Rig rig = wire(vertx, entry(true, ErrorStrategy.RETRY, handler, null), List.of(), producer);
        useUpRetries(rig);
        if (fault.pauseFault() != null) {
            // The consumer is already paused when the record is taken in; this row needs the pause to be
            // called again, and to throw, while the failure is handled.
            beforeFailing.set(() -> {
                try {
                    setPaused(rig, false);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                doAnswer(invocation -> {
                            throw fault.pauseFault();
                        })
                        .when(rig.consumer())
                        .pause();
            });
        }
        return rig;
    }

    @Test
    @DisplayName(
            "a fault at any step never commits or resumes before a seek back is confirmed, and else holds the slot")
    void faultAtAnyStepNeverCommitsOrResumesBeforeASeekIsConfirmed(Vertx vertx) throws Exception {
        for (Fault fault : faults()) {
            String where = " with " + fault.label();
            // Given: a forcing entry whose failed record goes to a dead-letter send and a seek, both scripted
            Rig rig = faulty(vertx, fault);
            AtomicInteger attempts = new AtomicInteger();
            List<Promise<Void>> held = scriptSeeks(rig, fault.seeks(), attempts);

            // When: the record fails
            rig.process(record(FAILED_OFFSET, null));

            // Then: before any seek is confirmed nothing is committed, nothing resumes, and the slot is held
            assertEquals(fault.seekAttempts(), attempts.get(), "seek attempts" + where);
            assertEquals(List.of(), committedOffsets(rig), "nothing may be committed" + where);
            assertEquals(0, rig.broker().resumes.get(), "nothing may resume before a seek is confirmed" + where);
            assertEquals(1, rig.inFlight(), "the slot must stay held until a seek is confirmed" + where);
            assertTrue(
                    rig.broker().paused.get() || fault.pauseFault() != null, "the consumer must stay paused" + where);

            if (fault.confirmed()) {
                // When: the seek is confirmed
                held.get(held.size() - 1).complete();

                // Then: the slot is released, the consumer still waits out the backoff and resumes once after it
                assertEquals(0, rig.inFlight(), "the confirmed seek releases the slot" + where);
                assertEquals(0, rig.broker().resumes.get(), "the backoff holds the consumer" + where);
                verify(rig.consumer(), timeout(5_000).times(1)).resume();
            } else {
                // Then: no seek was confirmed, so the slot stays held and the consumer paused for good
                verify(rig.consumer(), after(3 * BACKOFF_MS).never()).resume();
                assertEquals(1, rig.inFlight(), "the slot must stay held" + where);
                assertTrue(rig.broker().paused.get(), "the consumer must stay paused" + where);
            }
            assertEquals(List.of(), committedOffsets(rig), "nothing may ever be committed" + where);
            verify(rig.producer(), times(1)).sendForDlq(anyString(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("a held slot is released when its partition is no longer assigned")
    void heldSlotIsReleasedWhenItsPartitionIsNoLongerAssigned(Vertx vertx) throws Exception {
        // Given: a forcing entry whose seek back fails, on a partition the consumer no longer owns
        Fault fault = new Fault("a seek that fails", Send.FAILS, null, List.of(Seek.FAIL), 2, false);
        Rig lost = faulty(vertx, fault);
        scriptSeeks(lost, fault.seeks(), new AtomicInteger());
        when(lost.consumer().assignment()).thenReturn(Future.succeededFuture(Set.of()));

        // When: the record fails
        lost.process(record(FAILED_OFFSET, null));

        // Then: the record goes to the new owner, so the slot is released, the consumer resumes, its counts
        // are dropped and nothing is committed
        assertEquals(0, lost.inFlight());
        assertFalse(lost.broker().paused.get());
        assertEquals(1, lost.broker().resumes.get());
        assertEquals(List.of(), committedOffsets(lost));
        assertTrue(
                lost.errors().retryCounts.isEmpty() && lost.errors().dlqFailures.isEmpty());
    }

    @Test
    @DisplayName("a stop with a held slot, through a registered forced MANUAL binding, commits nothing")
    void stopWithAHeldSlotCommitsNothing(Vertx vertx) throws Exception {
        // Given: a forced MANUAL source binding, registered as an application would, whose handler fails
        KafkaConsumerBinding<KafkaForcedStrategiesTest.Payload> binding = KafkaConsumerBinding.builder(
                        "forced", KafkaForcedStrategiesTest.Payload.class)
                .topic(TOPIC)
                .groupId("grp")
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.RETRY)
                .handler(message -> Future.failedFuture(new RuntimeException("handler boom")))
                .forceStrategies()
                .build();
        JsonObject retry = new JsonObject()
                .put("retry", new JsonObject().put("maxRetries", 1).put("backoffMs", BACKOFF_MS));
        ConsumerEntry registered = List.copyOf(KafkaForcedStrategiesTest.StrategyRig.register(binding, retry)
                        .entries())
                .get(0);
        KafkaProducerFactory producer = producerAnswering(Future.failedFuture(new RuntimeException("kafka-down")));
        Rig rig = wire(vertx, registered, List.of(), producer, 200L);
        useUpRetries(rig);
        scriptSeeks(rig, List.of(Seek.FAIL), new AtomicInteger());

        // And: the record failed, its dead-letter send failed and no seek back could be confirmed
        rig.process(record(FAILED_OFFSET, null));
        assertEquals(1, rig.inFlight(), "the slot must be held");

        // When: the verticle is stopped
        Promise<Void> stopped = Promise.promise();
        rig.verticle().stop(stopped);
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> stopped.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));

        // Then: the stop reports the record still held, the consumer is closed with nothing committed, and the
        // binding never auto-commits
        assertTrue(failure.getCause() instanceof TimeoutException, "was: " + failure.getCause());
        verify(rig.consumer(), times(1)).close();
        assertEquals(List.of(), committedOffsets(rig), "a close must not commit past the held record");
        assertEquals(CommitStrategy.MANUAL, registered.config().commitStrategy());
        Field props = KafkaConsumerVerticle.class.getDeclaredField("kafkaProps");
        props.setAccessible(true);
        assertEquals("false", ((Map<?, ?>) props.get(rig.verticle())).get("enable.auto.commit"));
    }

    @Test
    @DisplayName("a binding that forces its strategies on the default AUTO commit is refused")
    void forcedBindingOnAutoCommitIsRefused() {
        // Given: builders that force the strategies, with the default commit strategy, AUTO and MANUAL
        record Row(String label, @Nullable CommitStrategy strategy, boolean refused) {}
        List<Row> rows = List.of(
                new Row("the default commit strategy", null, true),
                new Row("AUTO", CommitStrategy.AUTO, true),
                new Row("MANUAL", CommitStrategy.MANUAL, false));

        for (Row row : rows) {
            KafkaConsumerBinding.Builder<String> builder = KafkaConsumerBinding.builder("forced", String.class)
                    .topic("forced.events")
                    .groupId("forced-group")
                    .handler(message -> Future.succeededFuture())
                    .forceStrategies();
            if (row.strategy() != null) {
                builder.commitStrategy(row.strategy());
            }

            // When / Then: AUTO is refused, naming the binding and the option; MANUAL builds
            if (row.refused()) {
                IllegalStateException refusal = assertThrows(
                        IllegalStateException.class, builder::build, "forced strategies on " + row.label());
                assertTrue(
                        refusal.getMessage().contains("forced")
                                && refusal.getMessage().contains("forceStrategies")
                                && refusal.getMessage().contains("MANUAL"),
                        "the refusal must name the binding and the option, was: " + refusal.getMessage());
            } else {
                assertTrue(builder.build().forceStrategies(), "forced strategies on " + row.label());
            }
        }
    }
}
