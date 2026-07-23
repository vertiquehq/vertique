// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.annotation.Nullable;

/**
 * {@link KafkaSerdeProvider} implementation for the {@code "json"} format.
 *
 * <p>Delegates serialization to {@link JacksonKafkaSerializer} and deserialization to
 * {@link JacksonKafkaDeserializer}. The backing Jackson {@code ObjectMapper} is resolved per endpoint
 * with a three-tier precedence (see {@link #resolveMapper}): a <strong>non-blank</strong>
 * {@code jsonProfile} bag key wins outright — an explicit {@code "vertx"} selects the shared
 * {@code DatabindCodec.mapper()} and <em>stops</em> (it does not fall through to the global default),
 * any other id is looked up in the injected {@link JsonMapperProfileRegistry} (FR-JSON-034/035); when
 * the bag carries no explicit id, the global {@code json.jsonProfile} default from the injected
 * {@link JsonConfig} applies (pre-resolved once at construction); otherwise the {@code vertx} floor.
 * An unknown id fails fast with a {@code JsonProfileConfigurationException} (a bag id at
 * deserializer-build time; the global {@code json.jsonProfile} at construction/startup).
 *
 * <p>For property-based routing, the {@link #routingDeserializer} parses the wire bytes into a
 * {@code JsonNode} tree (one parse per record regardless of the number of property routes) using the
 * resolved profile mapper, and {@link #matchValue} reads the discriminator field from the tree.
 * {@link #convertRouted} then converts the pre-parsed tree to the route's target type via
 * {@link com.fasterxml.jackson.databind.ObjectMapper#treeToValue}, avoiding a second wire-byte
 * parse.
 *
 * <p>This provider lives in the {@code vertique-kafka-json} module. Format-agnostic infrastructure
 * (dispatcher, registry, validation) in {@code vertique-kafka-core} remains Jackson-free.
 */
public final class JsonSerdeProvider implements KafkaSerdeProvider {

    private final JsonMapperProfileRegistry registry;
    private final JsonConfig jsonConfig;

    /**
     * The pre-resolved global-default {@link ObjectMapper}, or {@code null} when no non-blank
     * {@code json.jsonProfile} is configured. Computed once at construction time from
     * {@code jsonConfig.jsonProfile()} to avoid a per-message {@link JsonProfileId#of} allocation
     * and registry lookup on the global-default path.
     */
    @Nullable
    private final ObjectMapper globalDefaultMapper;

    /**
     * Creates a provider that resolves named JSON mapper profiles from the given registry, applying
     * the global {@code json.jsonProfile} default when the per-endpoint serde bag carries no
     * (blank) {@code jsonProfile} key.
     *
     * <p>Precedence inside {@link #resolveMapper}: bag {@code jsonProfile} (non-blank) &gt;
     * {@code json.jsonProfile} (non-blank) &gt; {@code vertx} floor ({@link DatabindCodec#mapper()}).
     *
     * <p>The global-default mapper is resolved once at construction time — when
     * {@code jsonConfig.jsonProfile()} is non-null and non-blank, the result of
     * {@link JsonMapperProfileRegistry#mapper(JsonProfileId)} is cached in a private final field.
     * The per-message path then avoids a repeated {@code JsonProfileId.of} allocation and registry
     * lookup.
     *
     * @param registry   the JSON mapper profile registry used to resolve non-{@code vertx} profile ids
     * @param jsonConfig the global JSON config carrying {@code json.jsonProfile}; use
     *                   {@link JsonConfig#defaults()} when no global default is configured
     */
    public JsonSerdeProvider(JsonMapperProfileRegistry registry, JsonConfig jsonConfig) {
        this.registry = registry;
        this.jsonConfig = jsonConfig;
        String globalId = jsonConfig.jsonProfile();
        this.globalDefaultMapper =
                (globalId != null && !globalId.isBlank()) ? registry.mapper(JsonProfileId.of(globalId)) : null;
    }

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
     * Returns {@code false} — JSON is the default fallback format and is never auto-detected for
     * a specific type.
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
     * Builds a Jackson JSON serializer for the given value type, backed by the {@code ObjectMapper}
     * resolved from the bag's {@code jsonProfile} key.
     *
     * @param type the value type to serialize
     * @param endpointConfig the merged serde configuration view (read for {@code jsonProfile})
     * @param <V> the value type
     * @return a new {@link JacksonKafkaSerializer} using the resolved profile mapper
     */
    @Override
    public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
        return new JacksonKafkaSerializer<>(resolveMapper(endpointConfig));
    }

    /**
     * Builds a Jackson JSON deserializer for the given value type, backed by the {@code ObjectMapper}
     * resolved from the bag's {@code jsonProfile} key.
     *
     * @param type the value type to deserialize
     * @param endpointConfig the merged serde configuration view (read for {@code jsonProfile})
     * @param <V> the value type
     * @return a new {@link JacksonKafkaDeserializer} for {@code type} using the resolved profile mapper
     */
    @Override
    public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
        return new JacksonKafkaDeserializer<>(type, resolveMapper(endpointConfig));
    }

    /**
     * Builds a type-agnostic routing deserializer that parses the wire bytes into a
     * {@link JsonNode} tree using the {@code ObjectMapper} resolved from the bag's
     * {@code jsonProfile} key. The tree is returned as the routing value so that
     * {@link #matchValue} can read the discriminator field without a second parse, and
     * {@link #convertRouted} can map the tree to the route's target type without re-parsing the
     * original bytes.
     *
     * @param endpointConfig the merged serde configuration view (read for {@code jsonProfile})
     * @return a deserializer yielding a {@link JsonNode} tree, or {@code null} when the input is
     *     {@code null} or produces a null tree
     */
    @Override
    public KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) {
        ObjectMapper mapper = resolveMapper(endpointConfig);
        return (data, topic, headers) -> {
            if (data == null) {
                return null;
            }
            try {
                return mapper.readTree(data);
            } catch (Exception e) {
                throw new DeserializationException("Failed to parse JSON for property-based routing", e);
            }
        };
    }

    /**
     * Reads the discriminator field named {@code property} from a {@link JsonNode} routing value.
     * Returns {@code null} when the value is not a {@link JsonNode}.
     *
     * @param deserializedValue the routing value (expected to be a {@link JsonNode})
     * @param property the discriminator field name
     * @return the field value as a string (empty string when the field is absent or null), or
     *     {@code null} if the value is not a {@link JsonNode}
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
     * {@link com.fasterxml.jackson.databind.ObjectMapper#treeToValue} on the {@code ObjectMapper}
     * resolved from the bag's {@code jsonProfile} key, reusing the already-parsed tree without a
     * second wire-byte parse.
     *
     * @param routingValue the value returned by the routing deserializer (expected to be a
     *     {@link JsonNode})
     * @param type the concrete route value type
     * @param endpointConfig the merged serde configuration view (read for {@code jsonProfile})
     * @param <V> the route value type
     * @return the routing value converted to {@code type}
     * @throws DeserializationException if the conversion fails or the routing value is not a
     *     {@link JsonNode}; the exception message is value-free (no record content) while the
     *     Jackson cause is preserved as {@link DeserializationException#getCause()} for diagnostics
     */
    @Override
    public <V> V convertRouted(Object routingValue, Class<V> type, JsonObject endpointConfig) {
        if (!(routingValue instanceof JsonNode tree)) {
            throw new DeserializationException(
                    "JSON convertRouted expects a JsonNode routing value but got: "
                            + (routingValue == null
                                    ? "null"
                                    : routingValue.getClass().getName()),
                    null);
        }
        try {
            return resolveMapper(endpointConfig).treeToValue(tree, type);
        } catch (Exception e) {
            // Message is intentionally value-free: Jackson exception messages can embed offending
            // scalar values from the untrusted record, which must not leak into operator logs or
            // DLQ headers. The Jackson exception is preserved as the cause for stacktrace debugging.
            throw new DeserializationException("Failed to convert JSON tree to " + type.getName(), e);
        }
    }

    // --- Internals ---

    /**
     * Resolves the backing {@code ObjectMapper} for an endpoint from its serde-config bag, applying a
     * three-tier precedence:
     * <ol>
     *   <li>Bag {@code jsonProfile} (non-blank) — an explicit per-endpoint / kafka-boundary
     *       selection wins outright and stops resolution; an explicit {@code "vertx"} resolves to
     *       {@link DatabindCodec#mapper()} and does NOT fall through to the global default.</li>
     *   <li>{@code json.jsonProfile} global default (non-blank) — the {@link JsonConfig} tier, applied
     *       only when the bag carries no explicit selection.</li>
     *   <li>{@link DatabindCodec#mapper()} — the reserved {@code vertx} floor.</li>
     * </ol>
     *
     * <p>An unknown <strong>bag</strong> id throws {@code JsonProfileConfigurationException} here —
     * fail-fast at deserializer-build time. The global {@code json.jsonProfile} default never fails in
     * this method: it is pre-resolved (and validated) once in the constructor, so an unknown global id
     * fails fast at provider construction (startup) instead.
     *
     * @param endpointConfig the merged serde configuration view
     * @return the resolved {@code ObjectMapper}; {@link DatabindCodec#mapper()} for the default path
     */
    private ObjectMapper resolveMapper(JsonObject endpointConfig) {
        String bagId = endpointConfig != null ? endpointConfig.getString("jsonProfile") : null;
        if (bagId != null && !bagId.isBlank()) {
            // An explicit per-endpoint / kafka-boundary selection wins outright and STOPS resolution —
            // including an explicit "vertx", which resolves to DatabindCodec.mapper() and must NOT fall
            // through to the json.jsonProfile global default (precedence correctness).
            return JsonProfileId.VERTX.value().equals(bagId)
                    ? DatabindCodec.mapper()
                    : registry.mapper(JsonProfileId.of(bagId));
        }
        // No explicit selection: apply the json.jsonProfile global default (pre-resolved once at
        // construction to avoid a per-message JsonProfileId.of allocation), else the vertx floor.
        return globalDefaultMapper != null ? globalDefaultMapper : DatabindCodec.mapper();
    }

    /**
     * Returns a human-readable string for diagnostics.
     *
     * @return a description of this provider
     */
    @Override
    public String toString() {
        return "JsonSerdeProvider[format=json]";
    }
}
