// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.avro;

import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificRecord;

/**
 * Apicurio Registry-backed {@link KafkaSerdeProvider} for the {@code "avro"} value format.
 *
 * <p>Serializes/deserializes {@code SpecificRecord} payloads via
 * {@link AvroKafkaSerializer}/{@link AvroKafkaDeserializer} in <strong>Confluent-wire-compatible</strong>
 * mode: the Apicurio 3.x default {@code Default4ByteIdHandler} writes a magic byte + 4-byte schema id
 * into the <em>payload</em>, and headers are disabled — so the framing matches Confluent's and survives
 * the consumer's UTF-8 header stringification and the DLQ raw-bytes republish (see ADR-0074).
 *
 * <p>Serdes are built once per producer-method / consumer (the registry calls
 * {@link #serializer}/{@link #deserializer} at endpoint-build time, not per record). The provider
 * declares {@link #mayBlock()}{@code =true}; the {@code KafkaSerdeRegistry} wrapper propagates it onto
 * every built serde, so the framework offloads producer serialization off the event loop and forces
 * the worker threading model for Avro consumers (NFR-AVRO-005).
 *
 * <p>Registry/serde configuration is read from the already-merged {@code endpointConfig}
 * ({@code serdeProperties} + {@code schemaRegistry}); the native Kafka {@code properties} bag is never
 * consulted (FR-AVRO-009).
 */
public final class ApicurioAvroSerdeProvider implements KafkaSerdeProvider {

    /** The lowercased format key this provider handles. */
    public static final String FORMAT = "avro";

    // Apicurio config keys (literal values pinned to apicurio-registry 3.x; see java-api research notes).
    private static final String CFG_REGISTRY_URL = "apicurio.registry.url";
    private static final String CFG_HEADERS_ENABLED = "apicurio.registry.headers.enabled";
    private static final String CFG_USE_SPECIFIC_AVRO_READER = "apicurio.registry.use-specific-avro-reader";
    private static final String CFG_AUTO_REGISTER = "apicurio.registry.auto-register";
    // Bounded registry-call defaults (NFR-AVRO-002). Apicurio 3.2.4 exposes no config-map socket
    // read-timeout key; the JDK client adapter applies a 30s connect timeout, and these bound the
    // retry storm. All are overridable via serdeProperties / kafka.schemaRegistry. The worker-threading
    // contract (NFR-AVRO-005) remains the primary event-loop protection.
    private static final String CFG_RETRY_COUNT = "apicurio.registry.retry-count";
    private static final String CFG_RETRY_BACKOFF_MS = "apicurio.registry.retry-backoff-ms";
    private static final long DEFAULT_RETRY_COUNT = 3L;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 300L;

    private final JsonObject globalSchemaRegistry;

    /**
     * Builds the provider from the typed Kafka config, reading the canonical
     * {@link KafkaConfig#schemaRegistry()} block used as the fallback for the router-routing
     * deserializer.
     *
     * @param kafkaConfig the typed Kafka config (its {@code schemaRegistry} block is read; a
     *     {@code null} block falls back to an empty object)
     */
    public ApicurioAvroSerdeProvider(KafkaConfig kafkaConfig) {
        this.globalSchemaRegistry =
                kafkaConfig.schemaRegistry() != null ? kafkaConfig.schemaRegistry() : new JsonObject();
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public boolean autoDetects(Class<?> type) {
        return SpecificRecord.class.isAssignableFrom(type);
    }

    @Override
    public boolean supports(Class<?> type) {
        return type != null && SpecificRecord.class.isAssignableFrom(type);
    }

    @Override
    public boolean mayBlock() {
        return true;
    }

    @Override
    public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
        requireSpecificRecord(type);
        AvroKafkaSerializer<V> serializer = new AvroKafkaSerializer<>();
        serializer.configure(serializerConfig(endpointConfig), false);
        return new KafkaSerializer<>() {
            @Override
            public byte[] serialize(V value, String topic, Map<String, String> headers) {
                return serializer.serialize(topic, value);
            }

            @Override
            public void close() {
                serializer.close();
            }
        };
    }

    @Override
    public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
        requireSpecificRecord(type);
        AvroKafkaDeserializer<V> deserializer = new AvroKafkaDeserializer<>();
        deserializer.configure(deserializerConfig(endpointConfig), false);
        return new KafkaDeserializer<>() {
            @Override
            public V deserialize(byte[] data, String topic, Map<String, String> headers) {
                V decoded = decode(deserializer, topic, data);
                // Guard against a wire schema-id resolving to a different generated record than declared.
                if (decoded != null && !type.isInstance(decoded)) {
                    throw new DeserializationException(
                            "Avro record of type " + decoded.getClass().getName() + " on topic " + topic
                                    + " is not assignable to the declared value type " + type.getName(),
                            null);
                }
                return decoded;
            }

            @Override
            public void close() {
                deserializer.close();
            }
        };
    }

    @Override
    public KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) {
        AvroKafkaDeserializer<Object> deserializer = new AvroKafkaDeserializer<>();
        deserializer.configure(deserializerConfig(endpointConfig), false);
        return new KafkaDeserializer<>() {
            @Override
            public Object deserialize(byte[] data, String topic, Map<String, String> headers) {
                return decode(deserializer, topic, data);
            }

            @Override
            public void close() {
                deserializer.close();
            }
        };
    }

    /**
     * Runs an Apicurio deserialize, mapping any runtime failure (registry unreachable, missing
     * schema/class) to {@link DeserializationException} so it flows through the framework's
     * {@code ErrorStrategy} (NFR-AVRO-003).
     */
    private static <T> T decode(AvroKafkaDeserializer<T> deserializer, String topic, byte[] data) {
        try {
            return deserializer.deserialize(topic, data);
        } catch (RuntimeException e) {
            throw new DeserializationException(
                    "Avro deserialization failed on topic " + topic + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String matchValue(Object deserializedValue, String property) {
        if (deserializedValue instanceof GenericRecord record) {
            Object fieldValue = record.get(property);
            return fieldValue == null ? null : fieldValue.toString();
        }
        throw new IllegalArgumentException("Cannot read discriminator '" + property + "' from non-Avro record: "
                + (deserializedValue == null
                        ? "null"
                        : deserializedValue.getClass().getName()));
    }

    // --- Config builders ---

    private Map<String, Object> serializerConfig(JsonObject endpointConfig) {
        Map<String, Object> config = baseConfig(endpointConfig);
        // Auto-register the writer schema on first publish (dev convenience; override via serdeProperties).
        config.putIfAbsent(CFG_AUTO_REGISTER, true);
        return config;
    }

    private Map<String, Object> deserializerConfig(JsonObject endpointConfig) {
        Map<String, Object> config = baseConfig(endpointConfig);
        config.putIfAbsent(CFG_USE_SPECIFIC_AVRO_READER, true);
        return config;
    }

    /**
     * Builds the base Apicurio config from the merged endpoint view: registry URL + bounded retry
     * defaults (overridable by {@code serdeProperties}), the user {@code serdeProperties} overlay,
     * then the Confluent-wire framing flag re-applied last as a non-overridable framework invariant
     * (the whole design depends on the schema id living in the payload, not headers — ADR-0074).
     */
    private Map<String, Object> baseConfig(JsonObject endpointConfig) {
        JsonObject schemaRegistry = endpointConfig.getJsonObject("schemaRegistry", globalSchemaRegistry);
        JsonObject serdeProperties = endpointConfig.getJsonObject("serdeProperties", new JsonObject());

        Map<String, Object> config = new HashMap<>();
        String url = schemaRegistry.getString("url");
        if (url != null) {
            config.put(CFG_REGISTRY_URL, url);
        }
        // Bounded registry-call defaults (NFR-AVRO-002); overridable by serdeProperties below.
        config.put(CFG_RETRY_COUNT, DEFAULT_RETRY_COUNT);
        config.put(CFG_RETRY_BACKOFF_MS, DEFAULT_RETRY_BACKOFF_MS);
        // User-supplied apicurio.registry.* properties override the defaults above.
        serdeProperties.forEach(entry -> config.put(entry.getKey(), entry.getValue()));
        // Confluent-wire compatibility is a framework invariant: schema id in the payload, never in
        // Kafka headers. Re-applied after the overlay so it cannot be silently broken.
        config.put(CFG_HEADERS_ENABLED, false);
        return config;
    }

    /**
     * Fails fast when an explicit/auto-detected {@code avro} format is applied to a non-Avro payload:
     * the Apicurio Avro serde requires generated {@code SpecificRecord} classes.
     *
     * @param type the declared payload value type
     */
    private void requireSpecificRecord(Class<?> type) {
        if (!supports(type)) {
            throw new IllegalArgumentException("Avro format requires a generated SpecificRecord payload type, but got "
                    + (type == null ? "null" : type.getName())
                    + "; use json for plain POJOs or generate an Avro SpecificRecord");
        }
    }
}
