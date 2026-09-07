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

/**
 * {@link KafkaSerdeProvider} implementation for the {@code "json"} format.
 *
 * <p>Delegates serialization to {@link JacksonKafkaSerializer} and deserialization to
 * {@link JacksonKafkaDeserializer}. The backing Jackson {@code ObjectMapper} is resolved per endpoint
 * with a two-tier precedence (see {@link #resolveMapper}): a <strong>non-blank</strong>
 * {@code jsonProfile} bag key wins outright and stops resolution — every bag id, including
 * {@code "system"}, is looked up in the injected {@link JsonMapperProfileRegistry}
 * (FR-JSON-034/035); when the bag carries no explicit id, the pre-resolved global default —
 * {@code registry.mapper(jsonConfig.effectiveProfile())}, the {@code vertique} floor unless the
 * application configures {@code json.jsonProfile} otherwise — applies. Profiles resolved through
 * the registry are the only mapper source in this class; the shared Vert.x codec mapper is never
 * read directly. An unknown id fails fast with a {@code JsonProfileConfigurationException} (a bag
 * id at deserializer-build time; the global {@code json.jsonProfile} at construction/startup).
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
     * The pre-resolved global-default {@link ObjectMapper}: {@code registry.mapper(jsonConfig.effectiveProfile())}.
     * Computed once at construction time so the per-message global-default path avoids a repeated
     * {@link JsonConfig#effectiveProfile()} call and registry lookup. Never {@code null} — an
     * unconfigured {@code json.jsonProfile} floors to the reserved {@code vertique} id, which the
     * registry always resolves.
     */
    private final ObjectMapper globalDefaultMapper;

    /**
     * Creates a provider that resolves named JSON mapper profiles from the given registry, applying
     * the global {@code json.jsonProfile} default when the per-endpoint serde bag carries no
     * (blank) {@code jsonProfile} key.
     *
     * <p>Precedence inside {@link #resolveMapper}: bag {@code jsonProfile} (non-blank, resolved
     * through the registry for every id including {@code "system"}) &gt; the pre-resolved global
     * default ({@code registry.mapper(jsonConfig.effectiveProfile())}).
     *
     * <p>The global-default mapper is resolved once at construction time — the result of
     * {@link JsonConfig#effectiveProfile()} is looked up via
     * {@link JsonMapperProfileRegistry#mapper(JsonProfileId)} and cached in a private final field.
     * The per-message path then avoids a repeated registry lookup.
     *
     * @param registry   the JSON mapper profile registry used to resolve every profile id, including
     *                   the reserved {@code system} and {@code vertique} ids
     * @param jsonConfig the global JSON config carrying {@code json.jsonProfile}; use
     *                   {@link JsonConfig#defaults()} when no global default is configured
     */
    public JsonSerdeProvider(JsonMapperProfileRegistry registry, JsonConfig jsonConfig) {
        this.registry = registry;
        this.jsonConfig = jsonConfig;
        this.globalDefaultMapper = registry.mapper(jsonConfig.effectiveProfile());
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
     * two-tier precedence:
     * <ol>
     *   <li>Bag {@code jsonProfile} (non-blank) — an explicit per-endpoint / kafka-boundary
     *       selection wins outright and stops resolution. Every id, including {@code "system"},
     *       resolves through the injected {@link JsonMapperProfileRegistry} — profiles are the only
     *       mapper source, so an explicit {@code "system"} selection resolves the registry's
     *       {@code system} mapper, not a raw Vert.x singleton.</li>
     *   <li>The pre-resolved global default — {@code registry.mapper(jsonConfig.effectiveProfile())},
     *       applied only when the bag carries no explicit selection.</li>
     * </ol>
     *
     * <p>An unknown <strong>bag</strong> id throws {@code JsonProfileConfigurationException} here —
     * fail-fast at deserializer-build time. The global default never fails in this method: it is
     * pre-resolved (and validated) once in the constructor, so an unknown global {@code json.jsonProfile}
     * id fails fast at provider construction (startup) instead.
     *
     * @param endpointConfig the merged serde configuration view
     * @return the resolved {@code ObjectMapper}, always sourced from the profile registry
     */
    private ObjectMapper resolveMapper(JsonObject endpointConfig) {
        String bagId = endpointConfig != null ? endpointConfig.getString("jsonProfile") : null;
        if (bagId != null && !bagId.isBlank()) {
            // An explicit per-endpoint / kafka-boundary selection wins outright and STOPS resolution —
            // every id, including "system", resolves through the registry and must NOT fall through to
            // the json.jsonProfile global default (precedence correctness).
            return registry.mapper(JsonProfileId.of(bagId));
        }
        // No explicit selection: apply the pre-resolved json.jsonProfile global default (the vertique
        // floor unless the application configures json.jsonProfile otherwise).
        return globalDefaultMapper;
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
