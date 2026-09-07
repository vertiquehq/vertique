// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.kafka.DeserializationException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;

/**
 * Self-contained Jackson-backed {@link KafkaSerdeProvider} for the {@code "json"} format.
 *
 * <p>This is a test fixture that mirrors the behaviour of the {@code JsonSerdeProvider} in
 * {@code vertique-kafka-json} module. It exists here so that core's runtime integration tests
 * ({@code KafkaConsumerIT}, {@code KafkaProducerIT}, etc.) can use a working JSON provider without
 * depending on {@code vertique-kafka-json} at test scope — which would create a Maven reactor cycle
 * ({@code json → core} main, {@code core → json} test).
 *
 * <p>Behavioural parity with the real provider:
 * <ul>
 *   <li>{@link #format()} returns {@code "json"}</li>
 *   <li>{@link #autoDetects} returns {@code false}</li>
 *   <li>{@link #mayBlock()} returns {@code false}</li>
 *   <li>{@link #routingDeserializer} parses the raw bytes into a {@link JsonNode} tree
 *       (one parse per record regardless of property route count)</li>
 *   <li>{@link #matchValue} reads the discriminator field from the {@link JsonNode}</li>
 *   <li>{@link #convertRouted} converts the pre-parsed tree to the route type via
 *       {@link ObjectMapper#treeToValue}, avoiding a second wire-byte parse</li>
 * </ul>
 *
 * <p>Not behaviourally identical: {@link #serializer} and {@link #deserializer} delegate to a
 * Jackson {@link ObjectMapper} via {@link DatabindCodec#mapper()} directly — the real
 * {@code JsonSerdeProvider} instead resolves the {@code vertique} floor (or a configured
 * {@code json.jsonProfile}) through a {@code JsonMapperProfileRegistry}. This fixture keeps the
 * simpler raw-mapper wiring; its bag-folding behaviour is otherwise unchanged.
 *
 * <p>This fixture requires {@code jackson-databind} on the test class-path (declared at
 * {@code test} scope in {@code vertique-kafka-core/pom.xml}).
 */
public final class TestJsonSerdeProvider implements KafkaSerdeProvider {

    /**
     * Returns the format key handled by this provider.
     *
     * @return {@code "json"}
     */
    @Override
    public String format() {
        return "json";
    }

    /**
     * Returns {@code false} — JSON is the default fallback format and is never auto-detected for a
     * specific type.
     *
     * @param type the declared payload value type
     * @return {@code false}
     */
    @Override
    public boolean autoDetects(Class<?> type) {
        return false;
    }

    /**
     * Returns {@code false} — JSON deserialization does not block (no network calls).
     *
     * @return {@code false}
     */
    @Override
    public boolean mayBlock() {
        return false;
    }

    /**
     * Builds a Jackson JSON serializer for the given value type using the framework's shared
     * {@link DatabindCodec#mapper()}.
     *
     * @param type the value type to serialize
     * @param endpointConfig the merged serde configuration view (unused)
     * @param <V> the value type
     * @return a serializer that writes the value as JSON bytes
     */
    @Override
    public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
        ObjectMapper mapper = DatabindCodec.mapper();
        return (value, topic, headers) -> {
            try {
                return mapper.writeValueAsBytes(value);
            } catch (Exception e) {
                throw new DeserializationException("Failed to serialize value to JSON: " + e.getMessage(), e);
            }
        };
    }

    /**
     * Builds a Jackson JSON deserializer for the given value type using the framework's shared
     * {@link DatabindCodec#mapper()}.
     *
     * @param type the value type to deserialize into
     * @param endpointConfig the merged serde configuration view (unused)
     * @param <V> the value type
     * @return a deserializer that reads JSON bytes into {@code type}
     */
    @Override
    public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
        ObjectMapper mapper = DatabindCodec.mapper();
        return (data, topic, headers) -> {
            try {
                return mapper.readValue(data, type);
            } catch (Exception e) {
                throw new DeserializationException("Failed to deserialize JSON to " + type.getName(), e);
            }
        };
    }

    /**
     * Builds a routing deserializer that parses wire bytes into a {@link JsonNode} tree for
     * property-based routing. The tree is reused by {@link #matchValue} and {@link #convertRouted}
     * to avoid double parsing.
     *
     * @param endpointConfig the merged serde configuration view (unused)
     * @return a deserializer yielding a {@link JsonNode} tree, or {@code null} when the input is
     *     {@code null}
     */
    @Override
    public KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) {
        return (data, topic, headers) -> {
            if (data == null) {
                return null;
            }
            try {
                return DatabindCodec.mapper().readTree(data);
            } catch (Exception e) {
                throw new DeserializationException("Failed to parse JSON for property-based routing", e);
            }
        };
    }

    /**
     * Reads the discriminator field named {@code property} from a {@link JsonNode} routing value.
     *
     * @param deserializedValue the routing value (expected to be a {@link JsonNode})
     * @param property the discriminator field name
     * @return the field value as a string, or {@code null} if the value is not a {@link JsonNode}
     */
    @Override
    public String matchValue(Object deserializedValue, String property) {
        if (!(deserializedValue instanceof JsonNode node)) {
            return null;
        }
        return node.path(property).asText();
    }

    /**
     * Converts the pre-parsed {@link JsonNode} routing value into the concrete route type using
     * {@link ObjectMapper#treeToValue}, reusing the already-parsed tree without a second
     * wire-byte parse.
     *
     * @param routingValue the value returned by the routing deserializer (expected to be a
     *     {@link JsonNode})
     * @param type the concrete route value type
     * @param endpointConfig the merged serde configuration view (unused)
     * @param <V> the route value type
     * @return the routing value converted to {@code type}
     * @throws DeserializationException if the conversion fails or the routing value is not a
     *     {@link JsonNode}
     */
    @Override
    public <V> V convertRouted(Object routingValue, Class<V> type, JsonObject endpointConfig) {
        if (!(routingValue instanceof JsonNode tree)) {
            throw new DeserializationException(
                    "TestJsonSerdeProvider.convertRouted expects a JsonNode but got: "
                            + (routingValue == null
                                    ? "null"
                                    : routingValue.getClass().getName()),
                    null);
        }
        try {
            return DatabindCodec.mapper().treeToValue(tree, type);
        } catch (Exception e) {
            throw new DeserializationException(
                    "Failed to convert JSON tree to " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns a human-readable string for diagnostics.
     *
     * @return a description of this provider
     */
    @Override
    public String toString() {
        return "TestJsonSerdeProvider[format=json]";
    }
}
