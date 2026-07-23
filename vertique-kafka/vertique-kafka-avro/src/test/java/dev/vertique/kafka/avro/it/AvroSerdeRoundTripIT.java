// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.avro.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.avro.ApicurioAvroSerdeProvider;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import dev.vertique.kafka.test.KafkaTestContainers;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link ApicurioAvroSerdeProvider} against a real Kafka broker and a real
 * Apicurio Schema Registry (Testcontainers). Validates the genuine Apicurio integration that unit
 * tests cannot: Confluent-wire framing on the actual wire, schema registration + resolution, the
 * {@code SpecificRecord} round-trip used by Models 1/2/4, the type-agnostic router routing
 * ({@code routingDeserializer} + {@code matchValue}) used by Model 3, DLQ raw-bytes survival, and
 * the registry-unreachable failure mapping to {@link DeserializationException}.
 *
 * <p>The framework's consumer-verticle dispatch stack is format-agnostic (proven by the JSON ITs and
 * the format-selection/dispatcher unit tests), so these ITs exercise the serde boundary directly via
 * raw Kafka clients rather than re-deploying the full dispatch machinery.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class AvroSerdeRoundTripIT {

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    static final GenericContainer<?> registry = new GenericContainer<>(
                    DockerImageName.parse("quay.io/apicurio/apicurio-registry:3.2.4"))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/apis/registry/v3/system/info")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    static KafkaSerdeRegistry serdeRegistry;
    static JsonObject endpointConfig;

    @BeforeAll
    static void setUp() {
        registry.start();

        String registryUrl = "http://" + registry.getHost() + ":" + registry.getMappedPort(8080) + "/apis/registry/v3";
        serdeRegistry = new KafkaSerdeRegistry(Set.of(new ApicurioAvroSerdeProvider(KafkaConfig.fromConfig(
                new JsonObject().put("kafka", new JsonObject().put("schemaRegistry", schemaRegistry(registryUrl))),
                new DefaultConfigParser(DefaultConfigMapper.lenient())))));
        endpointConfig = new JsonObject()
                .put("schemaRegistry", schemaRegistry(registryUrl))
                .put("serdeProperties", new JsonObject());
    }

    @AfterAll
    static void tearDown() {
        registry.stop();
    }

    private static JsonObject schemaRegistry(String url) {
        return new JsonObject().put("url", url);
    }

    // --- Round-trip (Models 1/2/4 payload path) ---

    @Test
    @DisplayName("SpecificRecord round-trips through Kafka + Schema Registry as Avro")
    void specificRecordRoundTrip() throws Exception {
        String topic = "it.avro.orders";
        OrderCreated event = OrderCreated.newBuilder()
                .setEventType("created")
                .setOrderId("A-1")
                .setAmount(4200L)
                .build();

        KafkaSerializer<OrderCreated> serializer = serdeRegistry.serializer("avro", OrderCreated.class, endpointConfig);
        byte[] wire = serializer.serialize(event, topic, Map.of());

        // Confluent-wire framing: magic byte 0x00 + 4-byte schema id in the payload.
        assertTrue(wire.length >= 5, "Avro wire bytes must carry a 5-byte header");
        assertEquals(0, wire[0], "Confluent-wire magic byte must be 0x00");

        produce(topic, "A-1", wire);
        byte[] consumed = consumeOne(topic, "it-avro-roundtrip");
        assertNotNull(consumed, "Expected to consume the produced Avro record");

        KafkaDeserializer<OrderCreated> deserializer =
                serdeRegistry.deserializer("avro", OrderCreated.class, endpointConfig);
        OrderCreated decoded = deserializer.deserialize(consumed, topic, Map.of());
        assertEquals(event, decoded, "Avro round-trip must preserve the record");
    }

    // --- Auto-detect ---

    @Test
    @DisplayName("a SpecificRecord payload with no format config resolves to avro and round-trips")
    void autoDetectRoundTrip() throws Exception {
        assertEquals("avro", serdeRegistry.resolveFormat(OrderShipped.class, new JsonObject(), null));

        String topic = "it.avro.autodetect";
        OrderShipped event = OrderShipped.newBuilder()
                .setEventType("shipped")
                .setOrderId("B-2")
                .setCarrier("ACME")
                .build();
        String format = serdeRegistry.resolveFormat(OrderShipped.class, new JsonObject(), null);
        byte[] wire = serdeRegistry
                .serializer(format, OrderShipped.class, endpointConfig)
                .serialize(event, topic, Map.of());
        produce(topic, "B-2", wire);

        byte[] consumed = consumeOne(topic, "it-avro-autodetect");
        OrderShipped decoded = serdeRegistry
                .deserializer(format, OrderShipped.class, endpointConfig)
                .deserialize(consumed, topic, Map.of());
        assertEquals(event, decoded);
    }

    // --- Model-3 router routing via the SPI ---

    @Test
    @DisplayName("router routingDeserializer resolves the concrete record and matchValue reads the discriminator")
    void routerRoutingViaSpi() throws Exception {
        String topic = "it.avro.router";
        OrderShipped shipped = OrderShipped.newBuilder()
                .setEventType("shipped")
                .setOrderId("C-3")
                .setCarrier("FAST")
                .build();
        byte[] wire = serdeRegistry
                .serializer("avro", OrderShipped.class, endpointConfig)
                .serialize(shipped, topic, Map.of());
        produce(topic, "C-3", wire);
        byte[] consumed = consumeOne(topic, "it-avro-router");

        // Type-agnostic: no Class<V> supplied — the wire schema id resolves the concrete record.
        Object record =
                serdeRegistry.routingDeserializer("avro", endpointConfig).deserialize(consumed, topic, Map.of());
        assertNotNull(record);
        assertEquals("shipped", serdeRegistry.matchValue("avro", record, "eventType"));
        assertEquals("C-3", serdeRegistry.matchValue("avro", record, "orderId"));
    }

    // --- DLQ raw-bytes survival ---

    @Test
    @DisplayName("Confluent-framed Avro bytes survive a raw DLQ-style republish and remain decodable")
    void dlqBytesSurvive() throws Exception {
        String sourceTopic = "it.avro.dlq.source";
        String dlqTopic = "it.avro.dlq";
        OrderCreated event = OrderCreated.newBuilder()
                .setEventType("created")
                .setOrderId("D-4")
                .setAmount(99L)
                .build();
        byte[] wire = serdeRegistry
                .serializer("avro", OrderCreated.class, endpointConfig)
                .serialize(event, sourceTopic, Map.of());

        // The error handler republishes the original raw bytes unchanged to the DLQ topic.
        produce(dlqTopic, "D-4", wire);
        byte[] fromDlq = consumeOne(dlqTopic, "it-avro-dlq");
        assertEquals(0, fromDlq[0], "DLQ bytes must retain the Confluent magic byte");

        OrderCreated decoded = serdeRegistry
                .deserializer("avro", OrderCreated.class, endpointConfig)
                .deserialize(fromDlq, dlqTopic, Map.of());
        assertEquals(event, decoded);
    }

    // --- Durable/correlation context (headers) survive an Avro round-trip ---

    @Test
    @DisplayName("application/durable-context headers survive alongside an Avro value (header pipeline intact)")
    void contextHeadersSurviveAvroRoundTrip() throws Exception {
        String topic = "it.avro.context";
        OrderCreated event = OrderCreated.newBuilder()
                .setEventType("created")
                .setOrderId("CTX-1")
                .setAmount(7L)
                .build();
        byte[] wire = serdeRegistry
                .serializer("avro", OrderCreated.class, endpointConfig)
                .serialize(event, topic, Map.of());

        // The framework carries durable/correlation context in record headers (Avro changes only the
        // value bytes — ADR-0074). Produce with a reserved-style header and assert it survives.
        org.apache.kafka.common.header.Header header = new org.apache.kafka.common.header.internals.RecordHeader(
                "vertique-correlation", "corr-123".getBytes(StandardCharsets.UTF_8));
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, null, "CTX-1", wire, List.of(header)))
                    .get(15, TimeUnit.SECONDS);
        }

        ConsumerRecord<String, byte[]> record = consumeOneRecord(topic, "it-avro-context");
        assertNotNull(record, "Expected to consume the produced record");
        org.apache.kafka.common.header.Header survived = record.headers().lastHeader("vertique-correlation");
        assertNotNull(survived, "context header must survive the Avro round-trip");
        assertEquals("corr-123", new String(survived.value(), StandardCharsets.UTF_8));

        OrderCreated decoded = serdeRegistry
                .deserializer("avro", OrderCreated.class, endpointConfig)
                .deserialize(record.value(), topic, Map.of());
        assertEquals(event, decoded, "value still decodes after carrying context headers");
    }

    // --- Registry unreachable ---

    @Test
    @DisplayName("an unreachable registry surfaces as DeserializationException, not an uncaught throw")
    void registryUnreachableFails() throws Exception {
        String topic = "it.avro.registry-down";
        OrderCreated event = OrderCreated.newBuilder()
                .setEventType("created")
                .setOrderId("E-5")
                .setAmount(1L)
                .build();
        byte[] wire = serdeRegistry
                .serializer("avro", OrderCreated.class, endpointConfig)
                .serialize(event, topic, Map.of());

        JsonObject badConfig = new JsonObject()
                .put("schemaRegistry", schemaRegistry("http://localhost:9/apis/registry/v3"))
                .put("serdeProperties", new JsonObject());
        KafkaDeserializer<OrderCreated> badDeserializer =
                serdeRegistry.deserializer("avro", OrderCreated.class, badConfig);

        assertThrows(DeserializationException.class, () -> badDeserializer.deserialize(wire, topic, Map.of()));
    }

    // --- Raw Kafka helpers ---

    private static Properties producerProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        return props;
    }

    private static void produce(String topic, String key, byte[] value) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps())) {
            try {
                producer.send(new ProducerRecord<>(topic, key, value)).get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Failed to produce to " + topic, e.getCause());
            }
        }
    }

    private static byte[] consumeOne(String topic, String group) {
        ConsumerRecord<String, byte[]> record = consumeOneRecord(topic, group);
        return record == null ? null : record.value();
    }

    private static ConsumerRecord<String, byte[]> consumeOneRecord(String topic, String group) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", group);
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    return record;
                }
            }
            return null;
        }
    }
}
