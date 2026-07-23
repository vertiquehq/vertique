// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.serialization;

import dev.vertique.kafka.DeserializationException;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selects and builds Kafka value serdes by format, delegating entirely to registered
 * {@link KafkaSerdeProvider} instances.
 *
 * <p>Every format — including {@code "json"} — is provided by a contributor via
 * {@code @IntoSet Set<KafkaSerdeProvider>}; there is no built-in format. The registry resolves a
 * format key to its provider and fails fast with an actionable error when no provider is registered.
 *
 * <p>Format selection follows the precedence <strong>explicit endpoint format &gt; global default
 * &gt; auto-detect &gt; {@value #DEFAULT_FORMAT}</strong> (see {@link #resolveFormat}). The
 * registry <em>wraps</em> every provider-built serializer/deserializer so the wrapper's
 * {@code mayBlock()} delegates to the resolving provider's {@link KafkaSerdeProvider#mayBlock()} —
 * providers need not stamp {@code mayBlock()} on each serde themselves, and the framework's
 * event-loop-safety decision can key off the effective serde uniformly.
 */
@Singleton
public final class KafkaSerdeRegistry {

    /**
     * The terminal fallback format key (used only when no explicit/global/auto-detected format
     * applies). Resolving to it still requires a registered provider (e.g. from
     * {@code vertique-kafka-json}).
     */
    public static final String DEFAULT_FORMAT = "json";

    private final Map<String, KafkaSerdeProvider> providers;

    /**
     * Builds a registry over the contributed providers, rejecting blank and duplicate formats.
     *
     * @param providers the format providers contributed via multibinding (may be empty)
     * @throws IllegalStateException if a provider declares a blank format or two providers share a
     *     format (case-insensitive)
     */
    @Inject
    public KafkaSerdeRegistry(Set<KafkaSerdeProvider> providers) {
        Map<String, KafkaSerdeProvider> byFormat = new HashMap<>();
        for (KafkaSerdeProvider p : providers) {
            String format = normalize(p.format());
            if (format == null) {
                throw new IllegalStateException(
                        "KafkaSerdeProvider " + p.getClass().getName() + " declares a blank format");
            }
            KafkaSerdeProvider previous = byFormat.put(format, p);
            if (previous != null) {
                throw new IllegalStateException("Duplicate KafkaSerdeProvider for format '" + format + "': "
                        + previous.getClass().getName() + " and " + p.getClass().getName());
            }
        }
        this.providers = Map.copyOf(byFormat);
    }

    /**
     * Returns the provider for the given format.
     *
     * <p>Callers pass an already-resolved format; resolve via {@link #resolveFormat} first, which
     * never returns {@code null}/blank (it falls through to {@link #DEFAULT_FORMAT}). A
     * {@code null}/blank format reaching this method is a caller error and fails fast.
     *
     * @param format the resolved format key (case-insensitive); must not be {@code null} or blank
     * @return the registered provider for the format
     * @throws IllegalArgumentException if {@code format} is {@code null}/blank, or if no provider is
     *     registered for it — with an actionable message naming the missing module (e.g.
     *     {@code vertique-kafka-json} for {@code "json"}, {@code vertique-kafka-avro} for {@code "avro"})
     */
    public KafkaSerdeProvider provider(String format) {
        String normalized = normalize(format);
        if (normalized == null) {
            throw new IllegalArgumentException("no value format resolved");
        }
        KafkaSerdeProvider provider = providers.get(normalized);
        if (provider == null) {
            throw new IllegalArgumentException("No KafkaSerdeProvider registered for format '" + normalized
                    + "'. Add the module that provides it (e.g. vertique-kafka-json for 'json',"
                    + " vertique-kafka-avro for 'avro').");
        }
        return provider;
    }

    /**
     * Resolves the effective value format for a payload type, applying the precedence
     * <strong>explicit endpoint format &gt; global default &gt; auto-detect &gt;
     * {@value #DEFAULT_FORMAT}</strong>. Config always wins over auto-detect; auto-detect only fires
     * when a registered provider's {@link KafkaSerdeProvider#autoDetects(Class)} returns
     * {@code true} for {@code type}. The returned string is normalized (trimmed, lowercased); it is
     * not validated against the provider set here — an explicit/global format with no provider is
     * caught at serde build time so the caller can attach endpoint context to the failure.
     *
     * @param type the declared payload value type
     * @param endpointConfig the endpoint config, inspected for an explicit {@code "format"} key
     * @param globalDefault the global default format (e.g. {@code kafka.format}), or {@code null}
     * @return the resolved format key
     * @throws IllegalArgumentException if two registered providers both auto-detect {@code type}
     *     (ambiguous selection); set an explicit format to disambiguate
     */
    public String resolveFormat(Class<?> type, JsonObject endpointConfig, String globalDefault) {
        String endpoint = endpointConfig == null ? null : normalize(endpointConfig.getString("format"));
        if (endpoint != null) {
            return endpoint;
        }
        String global = normalize(globalDefault);
        if (global != null) {
            return global;
        }
        // Auto-detect: collect every provider whose format claims this type so an ambiguous match
        // fails fast (naming the providers) rather than letting the provider-map iteration order pick
        // a silent winner. Provider formats are unique (enforced at construction), so a second match
        // is always a genuine conflict. The normalized format key is returned for the single match.
        String detected = null;
        String detectedBy = null;
        for (KafkaSerdeProvider provider : providers.values()) {
            if (provider.autoDetects(type)) {
                if (detected != null) {
                    throw new IllegalArgumentException("Ambiguous auto-detect for value type " + type.getName()
                            + ": both " + detectedBy + " (format '" + detected + "') and "
                            + provider.getClass().getName() + " (format '" + normalize(provider.format())
                            + "') claim it. Set an explicit 'format' on the endpoint or globally.");
                }
                detected = normalize(provider.format());
                detectedBy = provider.getClass().getName();
            }
        }
        if (detected != null) {
            return detected;
        }
        return DEFAULT_FORMAT;
    }

    /**
     * Builds a serializer for the format by delegating to the registered provider, wrapped so
     * {@code mayBlock()} reflects the provider.
     *
     * @param format the resolved format key
     * @param type the value type
     * @param endpointConfig the merged serde config for this endpoint
     * @param <V> the value type
     * @return a serializer for {@code type}
     * @throws IllegalArgumentException if no provider is registered for the format
     */
    public <V> KafkaSerializer<V> serializer(String format, Class<V> type, JsonObject endpointConfig) {
        KafkaSerdeProvider provider = provider(format);
        return wrap(provider, provider.serializer(type, endpointConfig));
    }

    /**
     * Builds a deserializer for the format by delegating to the registered provider, wrapped so
     * {@code mayBlock()} reflects the provider.
     *
     * @param format the resolved format key
     * @param type the value type
     * @param endpointConfig the merged serde config for this endpoint
     * @param <V> the value type
     * @return a deserializer for {@code type}
     * @throws IllegalArgumentException if no provider is registered for the format
     */
    public <V> KafkaDeserializer<V> deserializer(String format, Class<V> type, JsonObject endpointConfig) {
        KafkaSerdeProvider provider = provider(format);
        return wrap(provider, provider.deserializer(type, endpointConfig));
    }

    /**
     * Whether serdes for this format may block (the provider-level flag; the router path uses this
     * because no per-route serde is built before selection).
     *
     * @param format the resolved format key
     * @return {@code true} if the format's provider may block
     * @throws IllegalArgumentException if no provider is registered for the format
     */
    public boolean mayBlock(String format) {
        return provider(format).mayBlock();
    }

    /**
     * Builds a type-agnostic routing deserializer for Model-3 router property routing (delegates to
     * the provider, configured from the consumer's merged {@code endpointConfig}, wrapped so
     * {@code mayBlock()} reflects the provider). Built once per router consumer.
     *
     * @param format the resolved format key
     * @param endpointConfig the merged serde configuration view for this consumer
     * @return a type-agnostic deserializer yielding the format's concrete payload object
     * @throws IllegalArgumentException if no provider is registered for the format
     * @throws UnsupportedOperationException if the provider does not support property-based routing
     */
    public KafkaDeserializer<Object> routingDeserializer(String format, JsonObject endpointConfig) {
        KafkaSerdeProvider provider = provider(format);
        return wrap(provider, provider.routingDeserializer(endpointConfig));
    }

    /**
     * Reads the discriminator field from a record returned by the {@link #routingDeserializer}
     * (delegates to the provider).
     *
     * @param format the resolved format key
     * @param deserializedValue the record from the routing deserializer
     * @param property the discriminator field name
     * @return the field value as a string
     * @throws IllegalArgumentException if no provider is registered for the format
     * @throws UnsupportedOperationException if the provider does not support property-based routing
     */
    public String matchValue(String format, Object deserializedValue, String property) {
        return provider(format).matchValue(deserializedValue, property);
    }

    /**
     * Converts the value produced by the routing deserializer into the concrete route type by
     * delegating to the provider's {@link KafkaSerdeProvider#convertRouted} implementation. Providers
     * may reuse the intermediate representation (e.g. a parsed object tree) rather than
     * re-parsing the wire bytes.
     *
     * @param format the resolved format key
     * @param routingValue the value returned by the routing deserializer
     * @param type the concrete route value type
     * @param endpointConfig the merged serde configuration view for this consumer
     * @param <V> the route value type
     * @return the routing value converted to {@code type}
     * @throws IllegalArgumentException if no provider is registered for the format
     * @throws DeserializationException if the conversion fails
     */
    public <V> V convertRouted(String format, Object routingValue, Class<V> type, JsonObject endpointConfig) {
        return provider(format).convertRouted(routingValue, type, endpointConfig);
    }

    // --- Internals ---

    private static <V> KafkaSerializer<V> wrap(KafkaSerdeProvider provider, KafkaSerializer<V> raw) {
        return new KafkaSerializer<V>() {
            @Override
            public byte[] serialize(V value, String topic, Map<String, String> headers) {
                return raw.serialize(value, topic, headers);
            }

            @Override
            public boolean mayBlock() {
                return provider.mayBlock();
            }

            @Override
            public void close() {
                raw.close();
            }
        };
    }

    private static <V> KafkaDeserializer<V> wrap(KafkaSerdeProvider provider, KafkaDeserializer<V> raw) {
        return new KafkaDeserializer<V>() {
            @Override
            public V deserialize(byte[] data, String topic, Map<String, String> headers)
                    throws DeserializationException {
                return raw.deserialize(data, topic, headers);
            }

            @Override
            public boolean mayBlock() {
                return provider.mayBlock();
            }

            @Override
            public void close() {
                raw.close();
            }
        };
    }

    private static String normalize(String format) {
        if (format == null) {
            return null;
        }
        String normalized = format.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
