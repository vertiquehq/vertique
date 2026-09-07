// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonSerdeProvider}: format identity, auto-detection, mayBlock,
 * serializer/deserializer round-trips, property-routing (routingDeserializer → matchValue →
 * convertRouted), and error paths.
 */
class JsonSerdeProviderTest {

    // --- Fixtures ---

    record Plain(String name) {}

    record OrderEvent(String type, String id) {}

    // Registry with no application profiles — only the built-in system/vertique/vertique-strict
    // profiles. Every bag in this test omits jsonProfile, so resolution always lands on the
    // registry's vertique mapper (the pre-resolved global default; json.jsonProfile is unset).
    private final JsonSerdeProvider provider =
            new JsonSerdeProvider(new DefaultJsonMapperProfileRegistry(Set.of()), JsonConfig.defaults());

    // --- Identity ---

    @Nested
    @DisplayName("format identity")
    class Identity {

        @Test
        @DisplayName("format() returns 'json'")
        void formatIsJson() {
            assertEquals("json", provider.format());
        }

        @Test
        @DisplayName("autoDetects() always returns false")
        void neverAutoDetects() {
            assertFalse(provider.autoDetects(Plain.class));
            assertFalse(provider.autoDetects(Object.class));
            assertFalse(provider.autoDetects(String.class));
        }

        @Test
        @DisplayName("mayBlock() returns false — no network calls")
        void doesNotBlock() {
            assertFalse(provider.mayBlock());
        }
    }

    // --- Serializer ---

    @Nested
    @DisplayName("serializer round-trip")
    class Serializer {

        @Test
        @DisplayName("serializes a Plain record to JSON bytes")
        void roundTrip() {
            KafkaSerializer<Plain> ser = provider.serializer(Plain.class, new JsonObject());
            byte[] bytes = ser.serialize(new Plain("hello"), "t", Map.of());
            assertEquals("{\"name\":\"hello\"}", new String(bytes, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("serializer.mayBlock() is false")
        void serializerDoesNotBlock() {
            assertFalse(provider.serializer(Plain.class, new JsonObject()).mayBlock());
        }
    }

    // --- Deserializer ---

    @Nested
    @DisplayName("deserializer round-trip")
    class Deserializer {

        @Test
        @DisplayName("deserializes JSON bytes into a Plain record")
        void roundTrip() throws Exception {
            byte[] bytes = "{\"name\":\"world\"}".getBytes(StandardCharsets.UTF_8);
            KafkaDeserializer<Plain> deser = provider.deserializer(Plain.class, new JsonObject());
            assertEquals(new Plain("world"), deser.deserialize(bytes, "t", Map.of()));
        }

        @Test
        @DisplayName("deserializer.mayBlock() is false")
        void deserializerDoesNotBlock() {
            assertFalse(provider.deserializer(Plain.class, new JsonObject()).mayBlock());
        }

        @Test
        @DisplayName("deserializer throws DeserializationException on invalid JSON")
        void invalidJsonThrows() {
            byte[] bad = "not-json".getBytes(StandardCharsets.UTF_8);
            KafkaDeserializer<Plain> deser = provider.deserializer(Plain.class, new JsonObject());
            assertThrows(DeserializationException.class, () -> deser.deserialize(bad, "t", Map.of()));
        }
    }

    // --- routingDeserializer ---

    @Nested
    @DisplayName("routingDeserializer — yields a JsonNode tree")
    class RoutingDeserializer {

        @Test
        @DisplayName("parses JSON bytes into a JsonNode")
        void yieldsJsonNode() throws Exception {
            byte[] bytes = "{\"type\":\"order\",\"id\":\"42\"}".getBytes(StandardCharsets.UTF_8);
            KafkaDeserializer<Object> routing = provider.routingDeserializer(new JsonObject());
            Object result = routing.deserialize(bytes, "t", Map.of());
            assertNotNull(result);
            assertNotNull(result instanceof JsonNode, "result must be a JsonNode");
        }

        @Test
        @DisplayName("returns null when input data is null")
        void nullInputReturnsNull() throws Exception {
            KafkaDeserializer<Object> routing = provider.routingDeserializer(new JsonObject());
            assertNull(routing.deserialize(null, "t", Map.of()));
        }

        @Test
        @DisplayName("throws DeserializationException on unparseable bytes")
        void invalidJsonThrows() {
            byte[] bad = "not-json!!".getBytes(StandardCharsets.UTF_8);
            KafkaDeserializer<Object> routing = provider.routingDeserializer(new JsonObject());
            assertThrows(DeserializationException.class, () -> routing.deserialize(bad, "t", Map.of()));
        }
    }

    // --- matchValue ---

    @Nested
    @DisplayName("matchValue — reads a discriminator field from a JsonNode")
    class MatchValue {

        @Test
        @DisplayName("reads the property value from a JsonNode routing value")
        void readsProperty() throws Exception {
            byte[] bytes = "{\"type\":\"created\",\"id\":\"7\"}".getBytes(StandardCharsets.UTF_8);
            Object tree = provider.routingDeserializer(new JsonObject()).deserialize(bytes, "t", Map.of());
            assertEquals("created", provider.matchValue(tree, "type"));
            assertEquals("7", provider.matchValue(tree, "id"));
        }

        @Test
        @DisplayName("returns empty string (asText() default) for an absent field")
        void absentFieldReturnsEmptyString() throws Exception {
            byte[] bytes = "{\"type\":\"created\"}".getBytes(StandardCharsets.UTF_8);
            Object tree = provider.routingDeserializer(new JsonObject()).deserialize(bytes, "t", Map.of());
            assertEquals("", provider.matchValue(tree, "missing"));
        }

        @Test
        @DisplayName("returns null when the value is not a JsonNode")
        void nonJsonNodeReturnsNull() {
            assertNull(provider.matchValue("not-a-json-node", "type"));
            assertNull(provider.matchValue(42, "type"));
            assertNull(provider.matchValue(null, "type"));
        }
    }

    // --- convertRouted ---

    @Nested
    @DisplayName("convertRouted — maps the pre-parsed JsonNode to the route type")
    class ConvertRouted {

        @Test
        @DisplayName("converts a JsonNode to the route target type without re-parsing bytes")
        void convertsTreeToType() throws Exception {
            byte[] bytes = "{\"type\":\"created\",\"id\":\"99\"}".getBytes(StandardCharsets.UTF_8);
            Object tree = provider.routingDeserializer(new JsonObject()).deserialize(bytes, "t", Map.of());
            OrderEvent event = provider.convertRouted(tree, OrderEvent.class, new JsonObject());
            assertEquals(new OrderEvent("created", "99"), event);
        }

        @Test
        @DisplayName("throws DeserializationException when routingValue is not a JsonNode")
        void nonJsonNodeRoutingValueThrows() {
            DeserializationException ex = assertThrows(
                    DeserializationException.class,
                    () -> provider.convertRouted("not-a-node", Plain.class, new JsonObject()));
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("throws DeserializationException when tree cannot be mapped to the target type")
        void incompatibleTreeThrows() throws Exception {
            // A JSON object that Jackson cannot map to String (e.g. missing constructor)
            byte[] bytes = "{\"nested\":{\"deep\":true}}".getBytes(StandardCharsets.UTF_8);
            Object tree = provider.routingDeserializer(new JsonObject()).deserialize(bytes, "t", Map.of());
            // String is not Jackson-deserializable from a JSON object
            assertThrows(
                    DeserializationException.class, () -> provider.convertRouted(tree, int.class, new JsonObject()));
        }
    }
}
