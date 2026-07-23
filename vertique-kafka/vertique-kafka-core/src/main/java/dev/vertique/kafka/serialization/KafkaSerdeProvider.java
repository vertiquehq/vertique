// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.serialization;

import dev.vertique.kafka.DeserializationException;
import io.vertx.core.json.JsonObject;

/**
 * SPI for a pluggable Kafka value serialization format (e.g. {@code "avro"}, {@code "json"}).
 *
 * <p>Providers are contributed via Dagger {@code @IntoSet Set<KafkaSerdeProvider>} and selected by
 * the {@link KafkaSerdeRegistry}. JSON is an ordinary provider (contributed by
 * {@code vertique-kafka-json}); the registry has no built-in format. An empty provider set means no
 * format is available until a provider is contributed.
 *
 * <p>To keep Avro (and other format) types off the core classpath, all format-specific type access
 * — including the Model-3 router discriminator field read — lives behind this SPI. The core
 * dispatcher never touches {@code org.apache.avro} or Jackson types directly.
 *
 * <p><strong>Thread-safety:</strong> the serializers/deserializers a provider returns MUST be safe
 * for concurrent use by multiple threads. The framework builds them once per producer-method /
 * consumer and reuses a single instance across records, across consumer deployment instances, and
 * across concurrent producer sends (the same contract Kafka itself relies on for a producer's shared
 * serializer). Providers that wrap a non-thread-safe library must guard it.
 *
 * <p>The registry <em>wraps</em> every serializer/deserializer this provider builds so the wrapper's
 * {@code mayBlock()} delegates to {@link #mayBlock()} — a provider need not stamp {@code mayBlock()}
 * on each serde itself.
 */
public interface KafkaSerdeProvider {

    /**
     * The lowercased format key this provider handles, matched against the configured/resolved
     * format (e.g. {@code "avro"}, {@code "json"}).
     *
     * @return the format key
     */
    String format();

    /**
     * Whether this provider's format should be auto-detected for the given payload type when no
     * explicit or global format is configured. The Avro provider returns {@code true} for
     * {@code SpecificRecord} types. The {@code SpecificRecord} check lives entirely here so core
     * gains no {@code org.apache.avro} dependency.
     *
     * @param type the declared payload value type
     * @return {@code true} if this format should be auto-selected for {@code type}; {@code false} by default
     */
    default boolean autoDetects(Class<?> type) {
        return false;
    }

    /**
     * Whether this provider can serialize/deserialize the given payload type when the format is
     * selected <em>explicitly</em> (via {@code format=avro}), as opposed to auto-detection. Used to
     * fail fast at registration — e.g. an Avro router route whose type is not a {@code SpecificRecord}.
     * Defaults to {@code true} (accept any type); the Avro provider restricts it to {@code SpecificRecord}.
     *
     * @param type the declared payload value type
     * @return {@code true} if this provider can handle {@code type}
     */
    default boolean supports(Class<?> type) {
        return true;
    }

    /**
     * Whether serdes built by this provider may block the calling thread (e.g. a Schema Registry
     * HTTP call on a cache miss). The registry propagates this onto every built serde's
     * {@code mayBlock()} and uses it for the router-path threading decision.
     *
     * @return {@code true} if this provider's serdes may block; {@code false} by default
     */
    default boolean mayBlock() {
        return false;
    }

    /**
     * Builds a serializer for the given value type using the already-merged endpoint serde config.
     *
     * @param type the value type to serialize
     * @param endpointConfig the merged serde configuration view for this producer-method
     * @param <V> the value type
     * @return a serializer for {@code type}
     */
    <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig);

    /**
     * Builds a deserializer for the given value type using the already-merged endpoint serde config.
     *
     * @param type the value type to deserialize
     * @param endpointConfig the merged serde configuration view for this consumer
     * @param <V> the value type
     * @return a deserializer for {@code type}
     */
    <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig);

    /**
     * Builds a type-agnostic deserializer for Model-3 router property routing. Because a router has
     * multiple route value types and the route is not yet chosen, the returned deserializer resolves
     * the concrete record from the wire schema-id with no caller-supplied {@code Class<V>}. It is
     * built once per router consumer from the consumer's already-merged {@code endpointConfig}
     * ({@code serdeProperties} + {@code schemaRegistry}), so endpoint registry/auth/id-handler
     * settings apply during route selection exactly as they do for payload deserialization
     * (FR-AVRO-007/009). The default throws — only formats that support property-based routing
     * override this.
     *
     * @param endpointConfig the merged serde configuration view for this consumer
     * @return a type-agnostic deserializer yielding the format's concrete payload object
     */
    default KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) {
        throw new UnsupportedOperationException("property-based routing not supported for format " + format());
    }

    /**
     * Reads the discriminator field named {@code property} from a record returned by the
     * {@link #routingDeserializer}, as a string for route matching. The default throws.
     *
     * @param deserializedValue the record from the routing deserializer
     * @param property the discriminator field name
     * @return the field value as a string, or {@code null} if absent
     */
    default String matchValue(Object deserializedValue, String property) {
        throw new UnsupportedOperationException("property-based routing not supported for format " + format());
    }

    /**
     * Converts the value produced by the {@link #routingDeserializer} into the concrete route type,
     * reusing the already-decoded value when possible. The default implementation reuses
     * {@code routingValue} directly when it is already an instance of {@code type}, and throws
     * {@link DeserializationException} when it is not — preventing a bad discriminator/schema
     * combination from dispatching the wrong record type to a route.
     *
     * <p>Format providers that decode an intermediate representation during routing (e.g. a parsed
     * object tree) should override this to convert the intermediate to the target type rather than
     * performing a second wire-byte parse.
     *
     * @param routingValue the value returned by the routing deserializer (never {@code null} on the
     *     property-match path; the dispatcher does not call this for null routing values)
     * @param type the concrete route value type
     * @param endpointConfig the merged serde configuration view for this consumer
     * @param <V> the route value type
     * @return the routing value cast or converted to {@code type}
     * @throws DeserializationException if {@code routingValue} is not assignable to {@code type}
     */
    default <V> V convertRouted(Object routingValue, Class<V> type, JsonObject endpointConfig) {
        if (type.isInstance(routingValue)) {
            return type.cast(routingValue);
        }
        throw new DeserializationException(
                "routing value "
                        + (routingValue == null
                                ? "null"
                                : routingValue.getClass().getName())
                        + " is not assignable to route type " + type.getName(),
                null);
    }
}
