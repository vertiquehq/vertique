// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadKind;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducer;
import dev.vertique.kafka.producer.KafkaProducerCaptureHook;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.producer.KafkaProducerOperation;
import dev.vertique.kafka.producer.KafkaProducerSend;
import dev.vertique.kafka.producer.KafkaSendOrigin;
import dev.vertique.kafka.producer.Topic;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link KafkaProducerCaptureHook} and the origin-threading contract in
 * {@link KafkaProducerFactory}.
 *
 * <p>Covered:
 * <ul>
 *   <li>Default no-op {@link KafkaProducerCaptureHook#onSend} does not throw</li>
 *   <li>Hook implements {@link OrderedExtension} (default phase = APPLICATION)</li>
 *   <li>A proxy ({@link KafkaProducer @KafkaProducer}) send fires the hook exactly once with
 *       {@link KafkaSendOrigin#DIRECT_PRODUCER} and a non-null {@link Method}</li>
 *   <li>A DLQ send fires with {@link KafkaSendOrigin#DLQ}, NOT {@link KafkaSendOrigin#DIRECT_PRODUCER}</li>
 *   <li>An outbox-origin send fires with {@link KafkaSendOrigin#OUTBOX}, NOT
 *       {@link KafkaSendOrigin#DIRECT_PRODUCER}</li>
 *   <li>The hook receives a non-null {@link PayloadSource} wrapping the serialized bytes</li>
 *   <li>A throwing hook does not change the send result</li>
 * </ul>
 *
 * <p>Origin-threading tests use {@link OriginCapturingFactory} — a subclass of
 * {@link KafkaProducerFactory} that overrides {@code sendWire} to capture arguments without
 * needing a real Kafka broker.
 */
class KafkaProducerCaptureHookTest {

    /** Headers a dead-letter send accepts: they carry the source-topic header the error handler writes. */
    private static final KafkaRecordHeaders DLQ_HEADERS =
            KafkaRecordHeaders.of(Map.of("x-dlq-source-topic", "source.topic"));

    // --- Fixtures ---

    /** Minimal producer interface for testing hook invocation via the proxy path. */
    @KafkaProducer(name = "test")
    interface TestMsgProducer {
        /**
         * @param msg the message value
         * @return a future of the record metadata
         */
        @Topic("hook.test.topic")
        Future<RecordMetadata> publish(String msg);
    }

    /** Base contract whose send method a producer interface inherits. */
    interface BaseMsgProducer {
        /**
         * @param msg the message value
         * @return a future of the record metadata
         */
        @Topic("hook.base.topic")
        Future<RecordMetadata> publishBase(String msg);
    }

    /** Producer interface whose only send method is inherited. */
    @KafkaProducer(name = "derived")
    interface DerivedMsgProducer extends BaseMsgProducer {}

    /** Hook overriding the send-event overload; records the operation it receives. */
    static final class OperationRecordingHook implements KafkaProducerCaptureHook {

        final List<KafkaProducerSend> sends = new CopyOnWriteArrayList<>();

        @Override
        public void onSend(KafkaProducerSend send) {
            sends.add(send);
        }
    }

    /** Recording capture hook that collects all invocations. */
    static final class RecordingHook implements KafkaProducerCaptureHook {

        record Capture(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                KafkaRecordHeaders headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {}

        final List<Capture> captures = new CopyOnWriteArrayList<>();

        @Override
        public void onSend(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                KafkaRecordHeaders headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {
            captures.add(new Capture(origin, topic, key, value, headers, producerMethod, result));
        }
    }

    /** Low-priority recording hook used when two hooks are registered together. */
    static final class SecondaryRecordingHook implements KafkaProducerCaptureHook {

        final List<KafkaSendOrigin> seenOrigins = new CopyOnWriteArrayList<>();

        @Override
        public int priority() {
            return 1; // runs after ThrowingHook (priority 0)
        }

        @Override
        public void onSend(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                KafkaRecordHeaders headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {
            seenOrigins.add(origin);
        }
    }

    /** Hook that always throws — verifies throwing does not change the send result. */
    static final class ThrowingHook implements KafkaProducerCaptureHook {

        int callCount;

        @Override
        public void onSend(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                KafkaRecordHeaders headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {
            callCount++;
            throw new RuntimeException("hook exploded");
        }
    }

    /** Hook that always throws the {@link Error} it was given; sorts ahead of {@link SecondaryRecordingHook}. */
    static final class ErrorThrowingHook implements KafkaProducerCaptureHook {

        private final Error thrown;

        int callCount;

        ErrorThrowingHook(Error thrown) {
            this.thrown = thrown;
        }

        @Override
        public void onSend(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                KafkaRecordHeaders headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {
            callCount++;
            throw thrown;
        }
    }

    // --- Vert.x lifecycle ---

    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    // --- Factory helpers ---

    private static OriginCapturingFactory capturingFactory(KafkaProducerCaptureHook... hooks) {
        return new OriginCapturingFactory(
                vertx, kafkaConfig(), KafkaTestSupport.noOpPropagator(), serdeRegistry(), Set.of(hooks));
    }

    private static KafkaConfig kafkaConfig() {
        return KafkaConfig.fromConfig(
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", "localhost:9092")),
                new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    private static KafkaSerdeRegistry serdeRegistry() {
        return new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()));
    }

    // --- KafkaProducerCaptureHook contract ---

    @Nested
    @DisplayName("KafkaProducerCaptureHook contract")
    class HookContract {

        @Test
        @DisplayName("default onSend is a no-op — does not throw")
        void defaultNoOp() {
            KafkaProducerCaptureHook hook = new KafkaProducerCaptureHook() {};
            // Should not throw regardless of null arguments
            hook.onSend(KafkaSendOrigin.DIRECT_PRODUCER, "t", "k", null, KafkaRecordHeaders.empty(), null, null);
        }

        @Test
        @DisplayName("implements OrderedExtension — default phase is APPLICATION")
        void implementsOrderedExtension() {
            KafkaProducerCaptureHook hook = new KafkaProducerCaptureHook() {};
            assertEquals(ExtensionPhase.APPLICATION, hook.phase());
        }

        @Test
        @DisplayName("hooks are sortable by OrderedExtension comparator")
        void sortableByComparator() {
            KafkaProducerCaptureHook high = new KafkaProducerCaptureHook() {
                @Override
                public int priority() {
                    return 100;
                }
            };
            KafkaProducerCaptureHook low = new KafkaProducerCaptureHook() {
                @Override
                public int priority() {
                    return 0;
                }
            };

            List<KafkaProducerCaptureHook> hooks = new ArrayList<>(List.of(high, low));
            hooks.sort(OrderedExtension.comparator());

            assertSame(low, hooks.get(0), "lower priority must sort first");
            assertSame(high, hooks.get(1));
        }
    }

    // --- Producer validation ---

    @Nested
    @DisplayName("validateProducer contract")
    class ProducerValidation {

        /** Hook recording the producer interfaces it was asked to validate. */
        final class ValidatingHook implements KafkaProducerCaptureHook {

            final List<Class<?>> validated = new ArrayList<>();
            RuntimeException failure;

            @Override
            public void validateProducer(Class<?> producerInterface) {
                validated.add(producerInterface);
                if (failure != null) {
                    throw failure;
                }
            }
        }

        @Test
        @DisplayName("default validateProducer accepts every producer interface")
        void defaultAccepts() {
            new KafkaProducerCaptureHook() {}.validateProducer(TestMsgProducer.class);
        }

        @Test
        @DisplayName("create validates the producer interface once per hook")
        void createValidatesOncePerHook() {
            ValidatingHook first = new ValidatingHook();
            ValidatingHook second = new ValidatingHook();

            capturingFactory(first, second).create(DerivedMsgProducer.class);

            assertEquals(List.of(DerivedMsgProducer.class), first.validated);
            assertEquals(List.of(DerivedMsgProducer.class), second.validated);
        }

        @Test
        @DisplayName("a rejecting hook fails create instead of the first send")
        void rejectionFailsCreate() {
            ValidatingHook hook = new ValidatingHook();
            hook.failure = new IllegalStateException("policy 'nope' is not defined");
            OriginCapturingFactory factory = capturingFactory(hook);

            IllegalStateException thrown =
                    assertThrows(IllegalStateException.class, () -> factory.create(TestMsgProducer.class));

            assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage());
            assertTrue(factory.capturedOrigins.isEmpty(), "nothing is sent while creating");
        }

        @Test
        @DisplayName("an interface without @KafkaProducer is rejected before any hook validates it")
        void notAProducerIsNotValidated() {
            ValidatingHook hook = new ValidatingHook();
            OriginCapturingFactory factory = capturingFactory(hook);

            assertThrows(IllegalArgumentException.class, () -> factory.create(BaseMsgProducer.class));

            assertTrue(hook.validated.isEmpty());
        }
    }

    // --- KafkaSendOrigin enum ---

    @Nested
    @DisplayName("KafkaSendOrigin enum")
    class SendOriginEnum {

        @Test
        @DisplayName("all expected constants are present")
        void hasExpectedConstants() {
            KafkaSendOrigin[] values = KafkaSendOrigin.values();
            assertEquals(4, values.length, "expected DIRECT_PRODUCER, OUTBOX, DLQ, INTERNAL");
        }
    }

    // --- Origin threading ---

    @Nested
    @DisplayName("origin threading contract")
    class OriginThreading {

        @Test
        @DisplayName("sendForDlq threads DLQ origin with null producerMethod")
        void dlqOriginAndNullMethod() {
            OriginCapturingFactory factory = capturingFactory();
            factory.sendForDlq("dlq.topic", "k", new byte[] {1, 2}, DLQ_HEADERS);

            assertEquals(1, factory.capturedOrigins.size());
            assertEquals(KafkaSendOrigin.DLQ, factory.capturedOrigins.get(0));
            assertNull(factory.capturedMethods.get(0), "DLQ send must have null producerMethod");
        }

        @Test
        @DisplayName("sendForOutbox threads OUTBOX origin with null producerMethod")
        void outboxOriginAndNullMethod() {
            OriginCapturingFactory factory = capturingFactory();
            dev.vertique.core.context.DurableMetadata ctx = dev.vertique.core.context.DurableMetadata.empty();
            factory.sendForOutbox("outbox.topic", "k2", new byte[] {3, 4}, KafkaRecordHeaders.empty(), ctx);

            assertEquals(1, factory.capturedOrigins.size());
            assertEquals(KafkaSendOrigin.OUTBOX, factory.capturedOrigins.get(0));
            assertNull(factory.capturedMethods.get(0), "OUTBOX send must have null producerMethod");
        }

        @Test
        @DisplayName("proxy send threads DIRECT_PRODUCER origin with non-null producerMethod")
        void proxySendPassesDirectProducerOriginAndMethod() {
            OriginCapturingFactory factory = capturingFactory();
            TestMsgProducer producer = factory.create(TestMsgProducer.class);
            // publish() uses a non-blocking JSON serializer; sendWire is called synchronously
            producer.publish("hello");

            assertEquals(1, factory.capturedOrigins.size(), "proxy must call sendWire exactly once");
            assertEquals(KafkaSendOrigin.DIRECT_PRODUCER, factory.capturedOrigins.get(0));
            assertNotNull(factory.capturedMethods.get(0), "proxy send must supply a non-null producerMethod");
            assertEquals("publish", factory.capturedMethods.get(0).getName());
        }

        @Test
        @DisplayName("DLQ origin is never DIRECT_PRODUCER")
        void dlqOriginIsNotDirectProducer() {
            OriginCapturingFactory factory = capturingFactory();
            factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);
            assertFalse(
                    factory.capturedOrigins.contains(KafkaSendOrigin.DIRECT_PRODUCER),
                    "DLQ send must NOT be classified as DIRECT_PRODUCER");
        }

        @Test
        @DisplayName("OUTBOX origin is never DIRECT_PRODUCER")
        void outboxOriginIsNotDirectProducer() {
            OriginCapturingFactory factory = capturingFactory();
            dev.vertique.core.context.DurableMetadata ctx = dev.vertique.core.context.DurableMetadata.empty();
            factory.sendForOutbox("outbox", "k2", new byte[] {1}, KafkaRecordHeaders.empty(), ctx);
            assertFalse(
                    factory.capturedOrigins.contains(KafkaSendOrigin.DIRECT_PRODUCER),
                    "OUTBOX send must NOT be classified as DIRECT_PRODUCER");
        }
    }

    // --- PayloadSource in hook invocation ---

    @Nested
    @DisplayName("hook receives PayloadSource with serialized bytes")
    class HookPayloadSource {

        @Test
        @DisplayName("PayloadSources.buffered(bytes, null) produces BUFFERED kind with correct length")
        void payloadSourceIsBufferedWithBytes() {
            byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
            dev.vertique.core.payload.PayloadSource source =
                    dev.vertique.core.payload.PayloadSources.buffered(bytes, null);
            assertEquals(PayloadKind.BUFFERED, source.kind());
            assertTrue(source.bufferedView().isPresent());
            assertEquals(bytes.length, source.bufferedView().get().length());
        }

        @Test
        @DisplayName("hook receives non-null BUFFERED PayloadSource containing the wire bytes")
        void hookReceivesPayloadSourceWithBytes() {
            RecordingHook hook = new RecordingHook();
            OriginCapturingFactory factory = capturingFactory(hook);
            factory.sendForDlq("dlq", "k", new byte[] {7, 8, 9}, DLQ_HEADERS);

            assertEquals(1, hook.captures.size());
            PayloadSource ps = hook.captures.get(0).value();
            assertNotNull(ps, "hook must receive a non-null PayloadSource");
            assertEquals(PayloadKind.BUFFERED, ps.kind());
            assertEquals(3, ps.bufferedView().get().length());
        }
    }

    // --- Throwing hook safety ---

    @Nested
    @DisplayName("throwing hook does not affect send result")
    class ThrowingHookSafety {

        @Test
        @DisplayName("a throwing hook does not change the Future result")
        void throwingHookDoesNotChangeSendResult() {
            ThrowingHook throwing = new ThrowingHook();
            OriginCapturingFactory factory = capturingFactory(throwing);

            // OriginCapturingFactory.sendWire fires hooks then returns succeededFuture.
            // The throwing hook must not convert it to failed.
            Future<RecordMetadata> result = factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);
            assertTrue(result.succeeded(), "throwing hook must not fail the send Future");
            assertEquals(1, throwing.callCount, "throwing hook must have been called");
        }

        @Test
        @DisplayName("a throwing hook does not prevent subsequent hooks from running")
        void throwingHookDoesNotBlockSubsequentHooks() {
            ThrowingHook throwing = new ThrowingHook();
            SecondaryRecordingHook secondary = new SecondaryRecordingHook();
            OriginCapturingFactory factory = capturingFactory(throwing, secondary);

            factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);
            assertEquals(1, throwing.callCount, "throwing hook must be called");
            assertEquals(1, secondary.seenOrigins.size(), "secondary hook must run even after throwing hook");
            assertEquals(KafkaSendOrigin.DLQ, secondary.seenOrigins.get(0));
        }

        @Test
        @DisplayName("a hook throwing an AssertionError changes neither the send result nor the later hooks")
        void assertionErrorHookIsIsolated() {
            assertErrorHookIsolated(new AssertionError("hook assertion"));
        }

        @Test
        @DisplayName("a hook throwing a LinkageError changes neither the send result nor the later hooks")
        void linkageErrorHookIsIsolated() {
            assertErrorHookIsolated(new NoSuchMethodError("hook linkage"));
        }

        @Test
        @DisplayName("a LinkageError is logged at ERROR once per hook, an AssertionError at WARN for every send")
        void linkageErrorIsReportedOnceAndAssertionErrorEveryTime() {
            Logger factoryLogger = (Logger) LoggerFactory.getLogger(KafkaProducerFactory.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.setContext(factoryLogger.getLoggerContext());
            appender.start();
            factoryLogger.addAppender(appender);
            try {
                ErrorThrowingHook unusable = new ErrorThrowingHook(new NoSuchMethodError("hook linkage"));
                KafkaProducerCaptureHook asserting = new KafkaProducerCaptureHook() {
                    @Override
                    public void onSend(KafkaProducerSend send) {
                        throw new AssertionError("hook assertion");
                    }
                };
                SecondaryRecordingHook secondary = new SecondaryRecordingHook();
                OriginCapturingFactory factory = capturingFactory(unusable, asserting, secondary);

                factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);
                factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);

                assertEquals(2, unusable.callCount, "the unusable hook is still called for every send");
                assertEquals(2, secondary.seenOrigins.size(), "the later hook must see both sends");
                List<ILoggingEvent> errors = appender.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .toList();
                assertEquals(1, errors.size(), "the unusable hook must be reported once, not per send");
                String report = errors.get(0).getFormattedMessage();
                assertTrue(report.contains("is unusable and its notifications are being lost"), report);
                assertTrue(report.contains(ErrorThrowingHook.class.getName()), report);
                assertEquals(
                        2,
                        appender.list.stream()
                                .filter(e -> e.getLevel() == Level.WARN)
                                .count(),
                        "an AssertionError must be logged for every send");
            } finally {
                factoryLogger.detachAppender(appender);
                appender.stop();
            }
        }

        /**
         * Sends once with no hook and once with a hook throwing {@code thrown} ahead of a recording hook, and
         * asserts that the throwing hook was reached, that the send's future has the result it has without the
         * hook, and that the recording hook still ran.
         *
         * @param thrown the {@link Error} the first hook throws
         */
        private void assertErrorHookIsolated(Error thrown) {
            Future<RecordMetadata> baseline = capturingFactory().sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);
            ErrorThrowingHook throwing = new ErrorThrowingHook(thrown);
            SecondaryRecordingHook secondary = new SecondaryRecordingHook();
            OriginCapturingFactory factory = capturingFactory(throwing, secondary);

            Future<RecordMetadata> result = assertDoesNotThrow(
                    () -> factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS),
                    "the hook's " + thrown.getClass().getSimpleName() + " must not escape the send");

            assertEquals(1, throwing.callCount, "the throwing hook must have been reached");
            assertEquals(baseline.succeeded(), result.succeeded(), "the send result must be the one without the hook");
            assertEquals(baseline.result(), result.result(), "the send result must be the one without the hook");
            assertEquals(
                    List.of(KafkaSendOrigin.DLQ),
                    secondary.seenOrigins,
                    "the hook after the throwing one must still run");
        }
    }

    // --- Producer operation (issue #638) ---

    @Nested
    @DisplayName("Producer operation: hooks receive the @KafkaProducer interface and name")
    class ProducerOperation {

        @Test
        @DisplayName("a proxy send carries the producer interface, name, and method")
        void proxySendCarriesOperation() throws NoSuchMethodException {
            OperationRecordingHook hook = new OperationRecordingHook();
            capturingFactory(hook).create(TestMsgProducer.class).publish("hello");

            assertEquals(1, hook.sends.size());
            KafkaProducerSend send = hook.sends.get(0);
            assertEquals(KafkaSendOrigin.DIRECT_PRODUCER, send.origin());
            assertEquals(
                    new KafkaProducerOperation(
                            TestMsgProducer.class, "test", TestMsgProducer.class.getMethod("publish", String.class)),
                    send.operation());
        }

        @Test
        @DisplayName("an inherited send method carries the producer interface, not the declaring super-interface")
        void inheritedSendCarriesProducerInterface() {
            OperationRecordingHook hook = new OperationRecordingHook();
            capturingFactory(hook).create(DerivedMsgProducer.class).publishBase("hello");

            KafkaProducerOperation operation = hook.sends.get(0).operation();
            assertEquals(DerivedMsgProducer.class, operation.producerType());
            assertEquals("derived", operation.producerName());
            assertEquals(BaseMsgProducer.class, operation.method().getDeclaringClass());
        }

        @Test
        @DisplayName("non-proxy sends carry no operation")
        void nonProxySendCarriesNoOperation() {
            OperationRecordingHook hook = new OperationRecordingHook();
            capturingFactory(hook).sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);

            assertEquals(1, hook.sends.size());
            assertNull(hook.sends.get(0).operation());
        }

        @Test
        @DisplayName("a hook overriding only the positional signature still receives proxy sends")
        void positionalSignatureHookStillCalled() {
            RecordingHook hook = new RecordingHook();
            capturingFactory(hook).create(TestMsgProducer.class).publish("hello");

            assertEquals(1, hook.captures.size());
            assertEquals("publish", hook.captures.get(0).producerMethod().getName());
        }
    }

    // --- Origin reference ---

    @Nested
    @DisplayName("origin reference on the send")
    class OriginReference {

        private static final dev.vertique.core.context.DurableMetadata NO_CONTEXT =
                dev.vertique.core.context.DurableMetadata.empty();

        @Test
        @DisplayName("sendForOutbox with an entry id: the event form sees OUTBOX and the entry id as originRef")
        void outboxSendWithEntryIdCarriesOriginRef() {
            OperationRecordingHook hook = new OperationRecordingHook();
            OriginCapturingFactory factory = capturingFactory(hook);

            Future<RecordMetadata> sent = factory.sendForOutbox(
                    "outbox.topic", "k", new byte[] {1, 2}, KafkaRecordHeaders.empty(), NO_CONTEXT, "4711");

            assertTrue(sent.succeeded());
            assertEquals(1, hook.sends.size(), "the hook is called once");
            KafkaProducerSend send = hook.sends.get(0);
            assertEquals(KafkaSendOrigin.OUTBOX, send.origin());
            assertEquals("4711", send.originRef());
            assertEquals("outbox.topic", send.topic());
            assertEquals("k", send.key());
            assertNull(send.operation(), "an outbox send has no producer operation");
            assertEquals(List.of("4711"), factory.capturedOriginRefs, "the entry id reaches the wire funnel");
        }

        @Test
        @DisplayName("sendForOutbox with an entry id: a hook overriding only the positional form still fires")
        void positionalFormStillFiresForOutboxSendWithEntryId() {
            RecordingHook hook = new RecordingHook();

            capturingFactory(hook)
                    .sendForOutbox("outbox.topic", "k", new byte[] {1}, KafkaRecordHeaders.empty(), NO_CONTEXT, "4711");

            assertEquals(1, hook.captures.size());
            assertEquals(KafkaSendOrigin.OUTBOX, hook.captures.get(0).origin());
            assertEquals("outbox.topic", hook.captures.get(0).topic());
            assertNull(hook.captures.get(0).producerMethod());
        }

        @Test
        @DisplayName("sendForOutbox with an entry id rejects a reserved-prefix header like the form without one")
        void outboxSendWithEntryIdRejectsReservedHeader() {
            OperationRecordingHook hook = new OperationRecordingHook();
            KafkaRecordHeaders reserved = KafkaRecordHeaders.of(Map.of("vertique-correlation", "x"));

            Future<RecordMetadata> sent = capturingFactory(hook)
                    .sendForOutbox("outbox.topic", "k", new byte[] {1}, reserved, NO_CONTEXT, "4711");

            assertTrue(sent.failed(), "a reserved-prefix header fails the send");
            assertTrue(sent.cause() instanceof IllegalArgumentException);
            assertTrue(hook.sends.isEmpty(), "nothing was sent, so no hook is called");
        }

        @Test
        @DisplayName("sendForOutbox without an entry id carries a null originRef")
        void outboxSendWithoutEntryIdCarriesNoOriginRef() {
            OperationRecordingHook hook = new OperationRecordingHook();

            capturingFactory(hook)
                    .sendForOutbox("outbox.topic", "k", new byte[] {1}, KafkaRecordHeaders.empty(), NO_CONTEXT);

            assertEquals(1, hook.sends.size());
            assertEquals(KafkaSendOrigin.OUTBOX, hook.sends.get(0).origin());
            assertNull(hook.sends.get(0).originRef());
        }

        @Test
        @DisplayName("internal, dead-letter and proxy sends carry a null originRef")
        void otherOriginsCarryNoOriginRef() {
            OperationRecordingHook hook = new OperationRecordingHook();
            OriginCapturingFactory factory = capturingFactory(hook);

            factory.send("t", "k", new byte[] {1}, KafkaRecordHeaders.empty());
            factory.send("t", "k", new byte[] {1}, KafkaRecordHeaders.empty(), NO_CONTEXT);
            factory.sendForDlq("dlq", "k", new byte[] {1}, DLQ_HEADERS);
            factory.create(TestMsgProducer.class).publish("hello");

            assertEquals(
                    List.of(
                            KafkaSendOrigin.INTERNAL,
                            KafkaSendOrigin.INTERNAL,
                            KafkaSendOrigin.DLQ,
                            KafkaSendOrigin.DIRECT_PRODUCER),
                    hook.sends.stream().map(KafkaProducerSend::origin).toList());
            for (KafkaProducerSend send : hook.sends) {
                assertNull(send.originRef(), "originRef must be null for origin " + send.origin());
            }
            assertTrue(factory.capturedOriginRefs.isEmpty(), "no send without a reference takes the reference path");
        }

        @Test
        @DisplayName("the seven-argument KafkaProducerSend constructor gives a null originRef")
        void sevenArgumentConstructorGivesNullOriginRef() {
            KafkaProducerSend send = new KafkaProducerSend(
                    KafkaSendOrigin.OUTBOX,
                    "t",
                    "k",
                    dev.vertique.core.payload.PayloadSources.buffered(new byte[] {1}, null),
                    KafkaRecordHeaders.empty(),
                    null,
                    Future.succeededFuture(null));

            assertNull(send.originRef());
        }
    }

    // --- The real wire funnel ---

    @Nested
    @DisplayName("the real wire funnel")
    class RealWireFunnel {

        private static final dev.vertique.core.context.DurableMetadata NO_CONTEXT =
                dev.vertique.core.context.DurableMetadata.empty();

        @Test
        @DisplayName("a subclass overriding only the seven-argument fireHooks still sees a direct send")
        void subclassOverridingOnlyTheOlderFireHooksSeesADirectSend() throws Exception {
            List<String> intercepted = new CopyOnWriteArrayList<>();
            OperationRecordingHook hook = new OperationRecordingHook();
            KafkaProducerFactory factory =
                    new KafkaProducerFactory(
                            vertx, kafkaConfig(), KafkaTestSupport.noOpPropagator(), serdeRegistry(), Set.of(hook)) {
                        @Override
                        protected void fireHooks(
                                KafkaSendOrigin origin,
                                String topic,
                                String key,
                                byte[] value,
                                KafkaRecordHeaders wire,
                                KafkaProducerOperation operation,
                                AsyncResult<RecordMetadata> ar) {
                            intercepted.add(origin + ":" + topic);
                            super.fireHooks(origin, topic, key, value, wire, operation, ar);
                        }
                    };
            useMockProducer(factory);

            Future<RecordMetadata> sent = factory.create(TestMsgProducer.class).publish("hello");

            assertTrue(sent.succeeded(), "the send goes through the real wire funnel");
            assertEquals(
                    List.of("DIRECT_PRODUCER:hook.test.topic"),
                    intercepted,
                    "the overridden seven-argument fireHooks sees the send");
            assertEquals(1, hook.sends.size(), "the hook is still called once");
            assertNull(hook.sends.get(0).originRef());
        }

        @Test
        @DisplayName("sendForOutbox with an entry id delivers originRef to an event-form hook through the real funnel")
        void outboxSendDeliversOriginRefThroughTheRealFunnel() throws Exception {
            OperationRecordingHook hook = new OperationRecordingHook();
            KafkaProducerFactory factory = new KafkaProducerFactory(
                    vertx, kafkaConfig(), KafkaTestSupport.noOpPropagator(), serdeRegistry(), Set.of(hook));
            useMockProducer(factory);

            Future<RecordMetadata> sent = factory.sendForOutbox(
                    "outbox.topic", "k", new byte[] {1, 2}, KafkaRecordHeaders.empty(), NO_CONTEXT, "4711");

            assertTrue(sent.succeeded(), "the send goes through the real wire funnel");
            assertEquals(1, hook.sends.size(), "the hook is called once");
            assertEquals(KafkaSendOrigin.OUTBOX, hook.sends.get(0).origin());
            assertEquals("4711", hook.sends.get(0).originRef());
            assertEquals("outbox.topic", hook.sends.get(0).topic());
        }

        /**
         * Puts a mocked Vert.x producer in the factory's shared-producer slot, so the factory's own
         * {@code sendWire} runs without a broker: every send succeeds at once.
         */
        @SuppressWarnings("unchecked")
        private static void useMockProducer(KafkaProducerFactory factory) throws Exception {
            io.vertx.kafka.client.producer.KafkaProducer<String, byte[]> producer =
                    org.mockito.Mockito.mock(io.vertx.kafka.client.producer.KafkaProducer.class);
            org.mockito.Mockito.when(producer.send(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Future.succeededFuture(null));
            org.mockito.Mockito.when(producer.close()).thenReturn(Future.succeededFuture());
            java.lang.reflect.Field shared = KafkaProducerFactory.class.getDeclaredField("shared");
            shared.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<Object>) shared.get(factory)).set(producer);
        }
    }

    // --- Origin-capturing test double ---

    /**
     * A {@link KafkaProducerFactory} subclass that overrides {@code sendWire} to capture the
     * {@link KafkaSendOrigin} and {@link Method} arguments without needing a real Kafka broker.
     * Returns a pre-completed succeeded future and fires hooks synchronously so tests can assert
     * hook invocations without async mechanics.
     */
    static final class OriginCapturingFactory extends KafkaProducerFactory {

        final List<KafkaSendOrigin> capturedOrigins = new CopyOnWriteArrayList<>();
        final List<Method> capturedMethods = new CopyOnWriteArrayList<>();
        final List<String> capturedOriginRefs = new CopyOnWriteArrayList<>();

        OriginCapturingFactory(
                Vertx vertx,
                KafkaConfig kafkaConfig,
                DurableContextPropagator propagator,
                KafkaSerdeRegistry serdeRegistry,
                Set<KafkaProducerCaptureHook> hooks) {
            super(vertx, kafkaConfig, propagator, serdeRegistry, hooks);
        }

        @Override
        protected Future<RecordMetadata> sendWire(
                String topic,
                String key,
                byte[] value,
                KafkaRecordHeaders wire,
                KafkaSendOrigin origin,
                KafkaProducerOperation operation) {
            capturedOrigins.add(origin);
            capturedMethods.add(operation != null ? operation.method() : null);
            // Fire hooks synchronously with a succeeded result so hook tests can assert without async
            fireHooks(origin, topic, key, value, wire, operation, Future.succeededFuture(null));
            return Future.succeededFuture(null);
        }

        @Override
        protected Future<RecordMetadata> sendWire(
                String topic,
                String key,
                byte[] value,
                KafkaRecordHeaders wire,
                KafkaSendOrigin origin,
                KafkaProducerOperation operation,
                String originRef) {
            capturedOrigins.add(origin);
            capturedMethods.add(operation != null ? operation.method() : null);
            capturedOriginRefs.add(originRef);
            fireHooks(origin, topic, key, value, wire, operation, originRef, Future.succeededFuture(null));
            return Future.succeededFuture(null);
        }
    }
}
