// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducer;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.producer.Topic;
import dev.vertique.kafka.test.KafkaTestContainers;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for {@link KafkaProducerFactory}.
 *
 * <p>Verifies that typed producer proxies created by {@link KafkaProducerFactory#create(Class)}
 * correctly serialize payloads and publish them to the Kafka topic declared by
 * {@link Topic}. Each test produces a message through the proxy and then consumes
 * it back directly using a raw Vert.x {@link KafkaConsumer} to verify the bytes on wire.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
public class KafkaProducerIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record OrderEvent(String orderId, String status) {}

    /**
     * Typed producer interface used by the proxy-creation tests.
     *
     * <p>Methods cover:
     * <ol>
     *   <li>Single-parameter (value only)</li>
     *   <li>Key + value (two parameters, first is {@link String})</li>
     *   <li>Value + headers (two parameters, second is {@link Map})</li>
     * </ol>
     */
    @KafkaProducer
    interface OrderProducer {

        /**
         * Publishes an order event without a key.
         *
         * @param event the event to publish
         * @return a future of the record metadata
         */
        @Topic("it.orders")
        Future<RecordMetadata> publishOrder(OrderEvent event);

        /**
         * Publishes an order event with an explicit key.
         *
         * @param key   the message key
         * @param event the event to publish
         * @return a future of the record metadata
         */
        @Topic("it.orders.keyed")
        Future<RecordMetadata> publishOrderWithKey(String key, OrderEvent event);

        /**
         * Publishes an order event with custom headers.
         *
         * @param event   the event to publish
         * @param headers the message headers
         * @return a future of the record metadata
         */
        @Topic("it.orders.headers")
        Future<RecordMetadata> publishOrderWithHeaders(OrderEvent event, Map<String, String> headers);
    }

    // --- Lifecycle ---

    /**
     * Completes setup immediately; the shared Kafka container is already started via
     * {@link #kafka}'s field initializer.
     *
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUp(VertxTestContext ctx) {
        ctx.completeNow();
    }

    // --- Tests ---

    @Test
    @DisplayName("should produce message to correct topic via typed proxy (value only)")
    void shouldProduceMessageToCorrectTopic(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory factory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient())),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        OrderProducer producer = factory.create(OrderProducer.class);
        OrderEvent event = new OrderEvent("ORD-001", "CREATED");

        RecordMetadata meta = producer.publishOrder(event)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        assertNotNull(meta);
        assertEquals("it.orders", meta.getTopic());

        // Consume back and verify payload
        KafkaConsumerRecord<String, byte[]> record = consumeOneRecord(vertx, "it.orders", "verify-group-1");
        assertNotNull(record);

        ObjectMapper mapper = DatabindCodec.mapper();
        OrderEvent deserialized = mapper.readValue(record.value(), OrderEvent.class);
        assertEquals("ORD-001", deserialized.orderId());
        assertEquals("CREATED", deserialized.status());
    }

    @Test
    @DisplayName("should produce message with explicit key via typed proxy")
    void shouldProduceMessageWithKey(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory factory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient())),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        OrderProducer producer = factory.create(OrderProducer.class);
        OrderEvent event = new OrderEvent("ORD-002", "SHIPPED");

        RecordMetadata meta = producer.publishOrderWithKey("order-key-002", event)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        assertNotNull(meta);
        assertEquals("it.orders.keyed", meta.getTopic());

        KafkaConsumerRecord<String, byte[]> record = consumeOneRecord(vertx, "it.orders.keyed", "verify-group-2");
        assertNotNull(record);
        assertEquals("order-key-002", record.key());

        ObjectMapper mapper = DatabindCodec.mapper();
        OrderEvent deserialized = mapper.readValue(record.value(), OrderEvent.class);
        assertEquals("ORD-002", deserialized.orderId());
        assertEquals("SHIPPED", deserialized.status());
    }

    @Test
    @DisplayName("should produce message with custom headers via typed proxy")
    void shouldProduceMessageWithHeaders(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory factory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient())),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        OrderProducer producer = factory.create(OrderProducer.class);
        OrderEvent event = new OrderEvent("ORD-003", "DELIVERED");

        RecordMetadata meta = producer.publishOrderWithHeaders(event, Map.of("x-source", "it-test", "x-version", "1"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        assertNotNull(meta);
        assertEquals("it.orders.headers", meta.getTopic());

        KafkaConsumerRecord<String, byte[]> record = consumeOneRecord(vertx, "it.orders.headers", "verify-group-3");
        assertNotNull(record);

        // Verify at least one expected header is present
        boolean hasSourceHeader = record.headers().stream()
                .anyMatch(h -> "x-source".equals(h.key())
                        && "it-test".equals(new String(h.value().getBytes())));
        assertTrue(hasSourceHeader, "Produced record should carry x-source header");

        ObjectMapper mapper = DatabindCodec.mapper();
        OrderEvent deserialized = mapper.readValue(record.value(), OrderEvent.class);
        assertEquals("ORD-003", deserialized.orderId());
        assertEquals("DELIVERED", deserialized.status());
    }

    @Test
    @DisplayName("should produce raw bytes via KafkaProducerFactory.send()")
    void shouldProduceRawBytes(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory factory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient())),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        byte[] payload = "{\"test\":true}".getBytes();
        RecordMetadata meta = factory.send("it.raw", "raw-key", payload, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        assertNotNull(meta);
        assertEquals("it.raw", meta.getTopic());

        KafkaConsumerRecord<String, byte[]> record = consumeOneRecord(vertx, "it.raw", "verify-group-raw");
        assertNotNull(record);
        assertArrayEquals(payload, record.value());
    }

    // --- Helpers ---

    /**
     * Creates a disposable Kafka consumer, subscribes to {@code topic}, polls a single record
     * and returns it. The consumer is closed after the record is received.
     *
     * @param vertx       the Vert.x instance
     * @param topic       the topic to subscribe to
     * @param consumerGroup a unique consumer group ID for this test
     * @return the first received record, or {@code null} if the timeout expires
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private KafkaConsumerRecord<String, byte[]> consumeOneRecord(Vertx vertx, String topic, String consumerGroup)
            throws InterruptedException {
        AtomicReference<KafkaConsumerRecord<String, byte[]>> ref = new AtomicReference<>();

        Map<String, String> props = Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", consumerGroup,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "true");

        KafkaConsumer<String, byte[]> consumer = KafkaConsumer.create(vertx, props, String.class, byte[].class);
        consumer.handler(record -> {
            ref.compareAndSet(null, record);
            consumer.close();
        });
        consumer.subscribe(topic);

        long deadline = System.currentTimeMillis() + 10_000;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        return ref.get();
    }

    /**
     * Asserts {@code condition} is {@code true} with the given message. Extracted as a method
     * to keep test assertions concise.
     *
     * @param condition the boolean condition
     * @param message   the failure message
     */
    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
