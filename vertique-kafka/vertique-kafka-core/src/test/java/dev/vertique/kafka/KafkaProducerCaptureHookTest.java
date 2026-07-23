// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Recording capture hook that collects all invocations. */
    static final class RecordingHook implements KafkaProducerCaptureHook {

        record Capture(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                Map<String, String> headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {}

        final List<Capture> captures = new CopyOnWriteArrayList<>();

        @Override
        public void onSend(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                Map<String, String> headers,
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
                Map<String, String> headers,
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
                Map<String, String> headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {
            callCount++;
            throw new RuntimeException("hook exploded");
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
                vertx,
                KafkaConfig.fromConfig(
                        new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", "localhost:9092")),
                        new DefaultConfigParser(DefaultConfigMapper.lenient())),
                KafkaTestSupport.noOpPropagator(),
                new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider())),
                Set.of(hooks));
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
            hook.onSend(KafkaSendOrigin.DIRECT_PRODUCER, "t", "k", null, Map.of(), null, null);
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
            factory.sendForDlq("dlq.topic", "k", new byte[] {1, 2}, Map.of());

            assertEquals(1, factory.capturedOrigins.size());
            assertEquals(KafkaSendOrigin.DLQ, factory.capturedOrigins.get(0));
            assertNull(factory.capturedMethods.get(0), "DLQ send must have null producerMethod");
        }

        @Test
        @DisplayName("sendForOutbox threads OUTBOX origin with null producerMethod")
        void outboxOriginAndNullMethod() {
            OriginCapturingFactory factory = capturingFactory();
            dev.vertique.core.context.DurableMetadata ctx = dev.vertique.core.context.DurableMetadata.empty();
            factory.sendForOutbox("outbox.topic", "k2", new byte[] {3, 4}, Map.of(), ctx);

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
            factory.sendForDlq("dlq", "k", new byte[] {1}, Map.of());
            assertFalse(
                    factory.capturedOrigins.contains(KafkaSendOrigin.DIRECT_PRODUCER),
                    "DLQ send must NOT be classified as DIRECT_PRODUCER");
        }

        @Test
        @DisplayName("OUTBOX origin is never DIRECT_PRODUCER")
        void outboxOriginIsNotDirectProducer() {
            OriginCapturingFactory factory = capturingFactory();
            dev.vertique.core.context.DurableMetadata ctx = dev.vertique.core.context.DurableMetadata.empty();
            factory.sendForOutbox("outbox", "k2", new byte[] {1}, Map.of(), ctx);
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
            factory.sendForDlq("dlq", "k", new byte[] {7, 8, 9}, Map.of());

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
            Future<RecordMetadata> result = factory.sendForDlq("dlq", "k", new byte[] {1}, Map.of());
            assertTrue(result.succeeded(), "throwing hook must not fail the send Future");
            assertEquals(1, throwing.callCount, "throwing hook must have been called");
        }

        @Test
        @DisplayName("a throwing hook does not prevent subsequent hooks from running")
        void throwingHookDoesNotBlockSubsequentHooks() {
            ThrowingHook throwing = new ThrowingHook();
            SecondaryRecordingHook secondary = new SecondaryRecordingHook();
            OriginCapturingFactory factory = capturingFactory(throwing, secondary);

            factory.sendForDlq("dlq", "k", new byte[] {1}, Map.of());
            assertEquals(1, throwing.callCount, "throwing hook must be called");
            assertEquals(1, secondary.seenOrigins.size(), "secondary hook must run even after throwing hook");
            assertEquals(KafkaSendOrigin.DLQ, secondary.seenOrigins.get(0));
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
                Map<String, String> wire,
                KafkaSendOrigin origin,
                Method producerMethod) {
            capturedOrigins.add(origin);
            capturedMethods.add(producerMethod);
            // Fire hooks synchronously with a succeeded result so hook tests can assert without async
            fireHooks(origin, topic, key, value, wire, producerMethod, Future.succeededFuture(null));
            return Future.succeededFuture(null);
        }
    }
}
