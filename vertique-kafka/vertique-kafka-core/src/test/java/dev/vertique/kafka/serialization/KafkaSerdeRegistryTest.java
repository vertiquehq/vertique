// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.serialization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaSerdeRegistry} format selection, provider delegation, wrapping, and
 * fail-fast validation. Uses a {@link FakeProvider} stand-in so no Avro/Apicurio dependency is
 * needed. JSON is now an ordinary provider ({@link TestJsonSerdeProvider}), not a built-in; tests
 * confirm both provider-contributed JSON behavior and the fail-fast path when no provider covers
 * a format.
 */
class KafkaSerdeRegistryTest {

    // --- Fixtures ---

    /** Marker for an auto-detectable payload type (stands in for {@code SpecificRecord}). */
    interface FakeRecordType {}

    record FakeAvroPayload(String field) implements FakeRecordType {}

    record Plain(String name) {}

    /** A record returned by the {@link FakeProvider} routing deserializer. */
    record FakeRecord(String field) {}

    /** Test double for a non-JSON format provider. */
    static final class FakeProvider implements KafkaSerdeProvider {
        private final String format;
        private final boolean mayBlock;

        FakeProvider(String format, boolean mayBlock) {
            this.format = format;
            this.mayBlock = mayBlock;
        }

        @Override
        public String format() {
            return format;
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeRecordType.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return mayBlock;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            // Lambda — mayBlock() defaults to false; only the registry wrapper exposes mayBlock=true.
            return (value, topic, headers) -> ("FAKE:" + value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            return (data, topic, headers) -> (V) new FakeRecord(new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) {
            return (data, topic, headers) -> new FakeRecord(new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public String matchValue(Object deserializedValue, String property) {
            return ((FakeRecord) deserializedValue).field();
        }
    }

    private static KafkaSerdeRegistry registry(KafkaSerdeProvider... providers) {
        return new KafkaSerdeRegistry(Set.of(providers));
    }

    private static KafkaSerdeRegistry jsonRegistry() {
        return new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()));
    }

    // --- JSON via provider ---

    @Nested
    @DisplayName("JSON via contributed TestJsonSerdeProvider")
    class JsonViaProvider {

        @Test
        @DisplayName("a provider declaring format 'json' is accepted and looked up via provider()")
        void jsonProviderAccepted() {
            KafkaSerdeRegistry reg = jsonRegistry();
            KafkaSerdeProvider p = reg.provider("json");
            assertNotNull(p, "json provider must be registered and returned by provider()");
            assertEquals("json", p.format());
        }

        @Test
        @DisplayName("json serializer/deserializer round-trips and never blocks")
        void jsonRoundTrips() {
            KafkaSerdeRegistry reg = jsonRegistry();
            KafkaSerializer<Plain> ser = reg.serializer("json", Plain.class, new JsonObject());
            KafkaDeserializer<Plain> deser = reg.deserializer("json", Plain.class, new JsonObject());

            byte[] bytes = ser.serialize(new Plain("hi"), "t", Map.of());
            assertArrayEquals("{\"name\":\"hi\"}".getBytes(StandardCharsets.UTF_8), bytes);
            assertEquals(new Plain("hi"), deser.deserialize(bytes, "t", Map.of()));
            assertFalse(ser.mayBlock());
            assertFalse(deser.mayBlock());
            assertFalse(reg.mayBlock("json"));
        }

        @Test
        @DisplayName("null/blank format normalizes to DEFAULT_FORMAT which resolves to the json provider")
        void nullBlankNormalizesToDefault() {
            KafkaSerdeRegistry reg = jsonRegistry();
            // Default format is "json", and json provider is registered, so resolveFormat returns "json"
            assertEquals("json", reg.resolveFormat(Plain.class, new JsonObject(), null));
        }

        @Test
        @DisplayName("serializer/deserializer for json delegate to the TestJsonSerdeProvider")
        void serdesDelegateToProvider() {
            KafkaSerdeRegistry reg = jsonRegistry();
            assertEquals(
                    "{\"name\":\"x\"}",
                    new String(
                            reg.serializer("json", Plain.class, new JsonObject())
                                    .serialize(new Plain("x"), "t", Map.of()),
                            StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("convertRouted delegates to the provider")
        void convertRoutedDelegatesToProvider() throws Exception {
            KafkaSerdeRegistry reg = jsonRegistry();
            // Build a routing value (JsonNode) via the routingDeserializer
            byte[] bytes = "{\"name\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
            Object routingValue =
                    reg.routingDeserializer("json", new JsonObject()).deserialize(bytes, "t", Map.of());
            Plain result = reg.convertRouted("json", routingValue, Plain.class, new JsonObject());
            assertEquals(new Plain("hello"), result);
        }
    }

    // --- Format precedence ---

    @Nested
    @DisplayName("resolveFormat precedence: endpoint > global > auto-detect > DEFAULT_FORMAT")
    class Precedence {

        private final KafkaSerdeRegistry reg = registry(new FakeProvider("avro", true), new TestJsonSerdeProvider());

        @Test
        @DisplayName("explicit endpoint format wins over everything, normalized to lowercase")
        void endpointWins() {
            assertEquals("avro", reg.resolveFormat(Plain.class, new JsonObject().put("format", "AVRO"), "json"));
        }

        @Test
        @DisplayName("global default wins over auto-detect")
        void globalWinsOverAutoDetect() {
            assertEquals("json", reg.resolveFormat(FakeAvroPayload.class, new JsonObject(), "json"));
        }

        @Test
        @DisplayName("auto-detect fires when no explicit/global format is set")
        void autoDetectWhenUnset() {
            assertEquals("avro", reg.resolveFormat(FakeAvroPayload.class, new JsonObject(), null));
        }

        @Test
        @DisplayName("falls through to DEFAULT_FORMAT for non-detected type")
        void defaultFallback() {
            assertEquals("json", reg.resolveFormat(Plain.class, new JsonObject(), null));
        }

        @Test
        @DisplayName("auto-detect needs a provider — empty registry never detects (returns DEFAULT_FORMAT)")
        void autoDetectNeedsProvider() {
            assertEquals("json", registry().resolveFormat(FakeAvroPayload.class, new JsonObject(), null));
        }

        @Test
        @DisplayName("ambiguous auto-detect (two providers claim the type) fails fast naming both")
        void ambiguousAutoDetectFailsFast() {
            KafkaSerdeRegistry ambiguous = registry(new FakeProvider("avro", false), new FakeProvider("proto", false));
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> ambiguous.resolveFormat(FakeAvroPayload.class, new JsonObject(), null));
            assertTrue(ex.getMessage().contains("Ambiguous auto-detect"), ex.getMessage());
            assertTrue(ex.getMessage().contains("avro"), ex.getMessage());
            assertTrue(ex.getMessage().contains("proto"), ex.getMessage());
        }
    }

    // --- Provider registration validation ---

    @Nested
    @DisplayName("provider registration")
    class Registration {

        @Test
        @DisplayName("duplicate format across providers is rejected")
        void duplicateRejected() {
            assertThrows(
                    IllegalStateException.class,
                    () -> registry(new FakeProvider("avro", false), new FakeProvider("avro", true)));
        }

        @Test
        @DisplayName("a provider claiming the 'json' format is accepted (json is no longer reserved)")
        void jsonProviderAccepted() {
            // json is a normal format key — no longer reserved/rejected
            KafkaSerdeRegistry reg = registry(new FakeProvider("json", false));
            assertNotNull(reg.provider("json"));
        }

        @Test
        @DisplayName("format keys are matched case-insensitively")
        void caseInsensitiveDuplicate() {
            assertThrows(
                    IllegalStateException.class,
                    () -> registry(new FakeProvider("Avro", false), new FakeProvider("avro", true)));
        }
    }

    // --- Fail-fast on missing provider ---

    @Nested
    @DisplayName("fail-fast for an unregistered format")
    class FailFast {

        @Test
        @DisplayName("requesting a serializer for an unknown format fails with actionable message")
        void unknownSerializer() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, () -> registry().serializer("avro", Plain.class, new JsonObject()));
            assertTrue(ex.getMessage().contains("avro"), "message must name the missing format");
            assertTrue(
                    ex.getMessage().contains("vertique-kafka")
                            || ex.getMessage().contains("module"),
                    "message must mention the module to add: " + ex.getMessage());
        }

        @Test
        @DisplayName("requesting a deserializer for an unknown format fails with actionable message")
        void unknownDeserializer() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> registry()
                    .deserializer("avro", Plain.class, new JsonObject()));
            assertTrue(ex.getMessage().contains("avro"), "message must name the missing format: " + ex.getMessage());
        }

        @Test
        @DisplayName(
                "requesting a json serde with no json provider fails with actionable message mentioning vertique-kafka-json")
        void missingJsonProvider() {
            // An empty registry has no json provider — resolving to DEFAULT_FORMAT still requires a provider
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, () -> registry().serializer("json", Plain.class, new JsonObject()));
            assertTrue(ex.getMessage().contains("json"), "message must name the missing format: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("vertique-kafka-json"),
                    "message must mention vertique-kafka-json module: " + ex.getMessage());
        }

        @Test
        @DisplayName(
                "requesting a json deserializer with no json provider fails with actionable message mentioning vertique-kafka-json")
        void missingJsonProviderDeserializer() {
            // Covers the deserializer build-path: same no-provider → IllegalArgumentException guarantee
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> registry()
                    .deserializer("json", Plain.class, new JsonObject()));
            assertTrue(ex.getMessage().contains("json"), "message must name the missing format: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("vertique-kafka-json")
                            || ex.getMessage().contains("module"),
                    "message must point to a provider module: " + ex.getMessage());
        }
    }

    // --- mayBlock propagation through the registry wrapper ---

    @Nested
    @DisplayName("mayBlock propagation")
    class MayBlock {

        private final KafkaSerdeRegistry reg = registry(new FakeProvider("avro", true), new TestJsonSerdeProvider());

        @Test
        @DisplayName("a mayBlock provider's built serializer reports mayBlock through the wrapper")
        void serializerMayBlockViaWrapper() {
            assertTrue(reg.serializer("avro", Plain.class, new JsonObject()).mayBlock());
        }

        @Test
        @DisplayName("a mayBlock provider's built deserializer reports mayBlock through the wrapper")
        void deserializerMayBlockViaWrapper() {
            assertTrue(reg.deserializer("avro", Plain.class, new JsonObject()).mayBlock());
        }

        @Test
        @DisplayName("registry.mayBlock reflects the provider-level flag")
        void providerLevelFlag() {
            assertTrue(reg.mayBlock("avro"));
            assertFalse(reg.mayBlock("json"));
        }

        @Test
        @DisplayName("a non-blocking provider propagates mayBlock=false")
        void nonBlockingProvider() {
            KafkaSerdeRegistry r = registry(new FakeProvider("proto", false));
            assertFalse(r.serializer("proto", Plain.class, new JsonObject()).mayBlock());
            assertFalse(r.mayBlock("proto"));
        }
    }

    // --- Router routing SPI delegation ---

    @Nested
    @DisplayName("router routing SPI delegation")
    class Routing {

        private final KafkaSerdeRegistry reg = registry(new FakeProvider("avro", true));

        @Test
        @DisplayName("routingDeserializer and matchValue delegate to the provider")
        void delegatesToProvider() throws Exception {
            Object record = reg.routingDeserializer("avro", new JsonObject())
                    .deserialize("ORDER".getBytes(StandardCharsets.UTF_8), "t", Map.of());
            assertEquals(new FakeRecord("ORDER"), record);
            assertEquals("ORDER", reg.matchValue("avro", record, "field"));
        }

        @Test
        @DisplayName("the routing deserializer reflects the provider's mayBlock through the wrapper")
        void routingDeserializerMayBlock() {
            assertTrue(reg.routingDeserializer("avro", new JsonObject()).mayBlock());
        }

        @Test
        @DisplayName("json provider supports the routing SPI via TestJsonSerdeProvider")
        void jsonRoutingSupported() throws Exception {
            KafkaSerdeRegistry jsonReg = jsonRegistry();
            byte[] bytes = "{\"type\":\"order\"}".getBytes(StandardCharsets.UTF_8);
            Object routingValue =
                    jsonReg.routingDeserializer("json", new JsonObject()).deserialize(bytes, "t", Map.of());
            assertNotNull(routingValue, "json routing deserializer must return a non-null JsonNode");
            assertEquals("order", jsonReg.matchValue("json", routingValue, "type"));
        }

        @Test
        @DisplayName("convertRouted delegates to the provider via the registry")
        void convertRoutedDelegatesViaRegistry() throws Exception {
            KafkaSerdeRegistry jsonReg = jsonRegistry();
            byte[] bytes = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            Object routingValue =
                    jsonReg.routingDeserializer("json", new JsonObject()).deserialize(bytes, "t", Map.of());
            Plain result = jsonReg.convertRouted("json", routingValue, Plain.class, new JsonObject());
            assertEquals(new Plain("test"), result);
        }
    }

    // --- close() propagation through the wrapper ---

    @Nested
    @DisplayName("close() propagation")
    class CloseDelegation {

        /** Provider whose built serdes flip a flag when closed, to prove the wrapper delegates close. */
        static final class CloseTrackingProvider implements KafkaSerdeProvider {
            final java.util.concurrent.atomic.AtomicBoolean serializerClosed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.AtomicBoolean deserializerClosed =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public String format() {
                return "avro";
            }

            @Override
            public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                return new KafkaSerializer<>() {
                    @Override
                    public byte[] serialize(V value, String topic, Map<String, String> headers) {
                        return new byte[0];
                    }

                    @Override
                    public void close() {
                        serializerClosed.set(true);
                    }
                };
            }

            @Override
            public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                return new KafkaDeserializer<>() {
                    @Override
                    public V deserialize(byte[] data, String topic, Map<String, String> headers) {
                        return null;
                    }

                    @Override
                    public void close() {
                        deserializerClosed.set(true);
                    }
                };
            }
        }

        @Test
        @DisplayName("closing a registry-built serde closes the underlying provider serde")
        void closeDelegatesToProviderSerde() {
            CloseTrackingProvider provider = new CloseTrackingProvider();
            KafkaSerdeRegistry reg = registry(provider);

            reg.serializer("avro", Plain.class, new JsonObject()).close();
            reg.deserializer("avro", Plain.class, new JsonObject()).close();

            assertTrue(provider.serializerClosed.get(), "wrapper must delegate serializer close");
            assertTrue(provider.deserializerClosed.get(), "wrapper must delegate deserializer close");
        }

        @Test
        @DisplayName("the json serde close (via TestJsonSerdeProvider) is a safe no-op")
        void jsonCloseIsNoOp() {
            KafkaSerdeRegistry reg = jsonRegistry();
            reg.serializer("json", Plain.class, new JsonObject()).close();
            reg.deserializer("json", Plain.class, new JsonObject()).close();
        }
    }
}
