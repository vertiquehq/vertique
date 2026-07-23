// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaProducerFactory}'s per-method serializer selection
 * ({@code resolveMethodSerializers}): format precedence (method &gt; producer &gt; global &gt;
 * auto-detect &gt; json), per-method serde-config merge, mayBlock propagation, and fail-fast on a
 * missing provider. Uses a {@link FakeProvider} so no Avro/Apicurio dependency is needed.
 */
class KafkaProducerSerializerSelectionTest {

    // --- Fixtures ---

    interface FakeRecordType {}

    record AvroPayload(String field) implements FakeRecordType {}

    record Plain(String name) {}

    /** Producer interface under test; only the parameter shapes matter for selection. */
    interface Producers {
        Future<RecordMetadata> avro(AvroPayload value);

        Future<RecordMetadata> json(Plain value);

        Future<RecordMetadata> keyed(String key, AvroPayload value);

        Future<RecordMetadata> legacy(AvroPayload value);
    }

    /**
     * Fake provider whose serializer embeds the resolved group id from {@code serdeProperties} so a
     * test can assert the merged serde-config view.
     */
    static final class FakeProvider implements KafkaSerdeProvider {
        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeRecordType.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return true;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            String group = endpointConfig
                    .getJsonObject("serdeProperties", new JsonObject())
                    .getString("group", "none");
            return (value, topic, headers) -> ("AVRO[" + group + "]:" + value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            throw new UnsupportedOperationException("not needed for producer tests");
        }
    }

    /** A second provider that also auto-detects {@link FakeRecordType}, to make auto-detect ambiguous. */
    static final class SecondProvider implements KafkaSerdeProvider {
        @Override
        public String format() {
            return "proto";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeRecordType.class.isAssignableFrom(type);
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            throw new UnsupportedOperationException("not needed for producer tests");
        }
    }

    private static KafkaSerdeRegistry registryWithAvro() {
        return new KafkaSerdeRegistry(Set.of(new FakeProvider(), new TestJsonSerdeProvider()));
    }

    /**
     * Resolves the per-method serializers from the legacy raw {@code producerConfig} fixture (which
     * sets producer-level {@code format}/{@code serdeProperties} plus flat per-method blocks keyed by
     * method name) and the raw {@code kafkaConfig} (which carries the global {@code format}). The flat
     * method blocks are reshaped to the typed external shape {@code producers.orderProducer.methods.
     * {method}} and parsed into the typed {@link KafkaConfig} at the boundary, so this exercises the
     * typed-config producer path while pinning every assertion's expected value unchanged.
     */
    private static Map<String, KafkaSerializer<Object>> resolve(
            JsonObject producerConfig, JsonObject kafkaConfig, KafkaSerdeRegistry registry) {
        KafkaConfig typed = typedKafkaConfig(producerConfig, kafkaConfig);
        return KafkaProducerFactory.resolveMethodSerializers(
                Producers.class, "orderProducer", typed.producerIndex().get("orderProducer"), typed, registry);
    }

    /**
     * Builds the typed {@link KafkaConfig} from the legacy fixtures: producer-level {@code format} and
     * {@code serdeProperties} stay at the producer level; every other key in {@code producerConfig} is a
     * method name and is moved under the keyed {@code methods} object (the {@code .methods.{method}}
     * external shape). The global {@code format} from {@code kafkaConfig} is carried through.
     */
    private static KafkaConfig typedKafkaConfig(JsonObject producerConfig, JsonObject kafkaConfig) {
        JsonObject producer = new JsonObject();
        JsonObject methods = new JsonObject();
        for (String key : producerConfig.fieldNames()) {
            if (key.equals("format") || key.equals("serdeProperties")) {
                producer.put(key, producerConfig.getValue(key));
            } else {
                methods.put(key, producerConfig.getValue(key));
            }
        }
        if (!methods.isEmpty()) {
            producer.put("methods", methods);
        }
        JsonObject kafka = kafkaConfig.copy().put("producers", new JsonObject().put("orderProducer", producer));
        return KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafka), new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    private static String bytes(KafkaSerializer<Object> serializer, Object value) {
        return new String(serializer.serialize(value, "topic", Map.of()), StandardCharsets.UTF_8);
    }

    // --- Auto-detect ---

    @Nested
    @DisplayName("auto-detect on the method value type")
    class AutoDetect {

        @Test
        @DisplayName("a SpecificRecord-like payload auto-selects avro; a plain payload stays json")
        void mixedAutoDetect() {
            var serializers = resolve(new JsonObject(), new JsonObject(), registryWithAvro());

            assertTrue(serializers.get("avro").mayBlock());
            assertEquals("AVRO[none]:" + new AvroPayload("x"), bytes(serializers.get("avro"), new AvroPayload("x")));

            assertFalse(serializers.get("json").mayBlock());
            assertEquals("{\"name\":\"y\"}", bytes(serializers.get("json"), new Plain("y")));
        }

        @Test
        @DisplayName("auto-detect resolves the value type for a keyed (key,value) method")
        void keyedMethodValueType() {
            var serializers = resolve(new JsonObject(), new JsonObject(), registryWithAvro());
            assertTrue(serializers.get("keyed").mayBlock());
        }

        @Test
        @DisplayName("without an avro provider, a SpecificRecord-like payload stays json")
        void noProviderNoDetect() {
            var serializers = resolve(
                    new JsonObject(), new JsonObject(), new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider())));
            assertFalse(serializers.get("avro").mayBlock());
            assertEquals("{\"field\":\"z\"}", bytes(serializers.get("avro"), new AvroPayload("z")));
        }
    }

    // --- Precedence ---

    @Nested
    @DisplayName("format precedence")
    class Precedence {

        @Test
        @DisplayName("global kafka.format wins over auto-detect")
        void globalOverAutoDetect() {
            var serializers = resolve(new JsonObject(), new JsonObject().put("format", "json"), registryWithAvro());
            assertFalse(serializers.get("avro").mayBlock());
            assertEquals("{\"field\":\"a\"}", bytes(serializers.get("avro"), new AvroPayload("a")));
        }

        @Test
        @DisplayName("producer-level format wins over global")
        void producerOverGlobal() {
            var serializers = resolve(
                    new JsonObject().put("format", "avro"), new JsonObject().put("format", "json"), registryWithAvro());
            assertTrue(serializers.get("json").mayBlock()); // even the Plain method becomes avro
        }

        @Test
        @DisplayName("per-method format override wins over producer-level")
        void methodOverProducer() {
            var producerConfig =
                    new JsonObject().put("format", "avro").put("legacy", new JsonObject().put("format", "json"));
            var serializers = resolve(producerConfig, new JsonObject(), registryWithAvro());

            assertFalse(serializers.get("legacy").mayBlock()); // overridden to json
            assertTrue(serializers.get("avro").mayBlock()); // still avro
        }
    }

    // --- Serde config merge ---

    @Nested
    @DisplayName("serde config merge (method > producer)")
    class SerdeMerge {

        @Test
        @DisplayName("method-level serdeProperties override producer-level ones")
        void methodOverridesProducer() {
            var producerConfig = new JsonObject()
                    .put("format", "avro")
                    .put("serdeProperties", new JsonObject().put("group", "producerGroup"))
                    .put(
                            "legacy",
                            new JsonObject().put("serdeProperties", new JsonObject().put("group", "methodGroup")));
            var serializers = resolve(producerConfig, new JsonObject(), registryWithAvro());

            assertEquals(
                    "AVRO[producerGroup]:" + new AvroPayload("x"),
                    bytes(serializers.get("avro"), new AvroPayload("x")));
            assertEquals(
                    "AVRO[methodGroup]:" + new AvroPayload("x"),
                    bytes(serializers.get("legacy"), new AvroPayload("x")));
        }
    }

    // --- Fail-fast ---

    @Nested
    @DisplayName("fail-fast on a missing provider")
    class FailFast {

        @Test
        @DisplayName("explicit format=avro with no provider fails fast naming the endpoint + missing module")
        void explicitMissingProvider() {
            // A method-level override pins the failing method deterministically (getMethods() order varies).
            // The json provider is present so only the avro-format method triggers the fail-fast.
            JsonObject producerConfig = new JsonObject().put("legacy", new JsonObject().put("format", "avro"));
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> resolve(
                            producerConfig,
                            new JsonObject(),
                            new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()))));
            assertTrue(ex.getMessage().contains("orderProducer#legacy"), ex.getMessage());
            assertTrue(ex.getMessage().contains("vertique-kafka-avro"), ex.getMessage());
        }

        @Test
        @DisplayName("ambiguous auto-detect surfaces with Producer name#method context, not a raw throw")
        void ambiguousAutoDetectWrappedWithContext() {
            // Two providers auto-detect FakeRecordType, so a method whose value type is auto-detected
            // (no explicit format) resolves ambiguously. The failure must carry the producer/method
            // context rather than escaping as the raw registry IllegalArgumentException.
            KafkaSerdeRegistry ambiguous = new KafkaSerdeRegistry(
                    Set.of(new FakeProvider(), new SecondProvider(), new TestJsonSerdeProvider()));
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, () -> resolve(new JsonObject(), new JsonObject(), ambiguous));
            assertTrue(ex.getMessage().contains("orderProducer#"), ex.getMessage());
            assertTrue(ex.getMessage().contains("cannot resolve value format"), ex.getMessage());
        }
    }

    // --- Synchronous serialization failure becomes a failed Future (NFR-AVRO-003) ---

    @Nested
    @DisplayName("serialization failure surfaces as a failed Future, not a synchronous throw")
    class SerializeFailure {

        @dev.vertique.kafka.producer.KafkaProducer(name = "throwing")
        interface ThrowingProducer {
            @Topic("throwing.topic")
            Future<io.vertx.kafka.client.producer.RecordMetadata> publish(AvroPayload value);
        }

        /** A non-blocking (mayBlock=false) avro-format provider whose serializer always throws. */
        static final class ThrowingProvider implements KafkaSerdeProvider {
            @Override
            public String format() {
                return "avro";
            }

            @Override
            public boolean autoDetects(Class<?> type) {
                return FakeRecordType.class.isAssignableFrom(type);
            }

            @Override
            public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                return (value, topic, headers) -> {
                    throw new IllegalStateException("boom");
                };
            }

            @Override
            public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                throw new UnsupportedOperationException("not needed");
            }
        }

        @Test
        @DisplayName("an on-loop serializer that throws yields a failed Future from the proxy")
        void onLoopSerializeThrowFailsFuture() {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(new ThrowingProvider()));
                JsonObject config =
                        new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", "localhost:9092"));
                // propagator is null-safe here: the serializer throws before sendRaw captures context.
                KafkaProducerFactory factory = new KafkaProducerFactory(
                        vertx,
                        KafkaConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient())),
                        null,
                        registry);
                ThrowingProducer producer = factory.create(ThrowingProducer.class);

                Future<io.vertx.kafka.client.producer.RecordMetadata> result = producer.publish(new AvroPayload("x"));
                assertTrue(result.failed(), "serializer throw must surface as a failed Future");
                assertTrue(result.cause() instanceof IllegalStateException, String.valueOf(result.cause()));
            } finally {
                vertx.close();
            }
        }
    }
}
