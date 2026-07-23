// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration helper that resolves native property keys from either flat
 * ({@code "bootstrap.servers"}) or hierarchically expanded ({@code {"bootstrap":{"servers":"..."}}})
 * JSON configuration.
 *
 * <p>Vert.x's {@code hierarchical=true} properties loader expands dotted keys into nested
 * JSON objects, but Kafka native properties inherently use dots. This helper transparently
 * handles both forms.
 */
public final class KafkaConfigHelper {

    private KafkaConfigHelper() {}

    // --- Key resolution ---

    /**
     * Resolves a dotted key from a {@link JsonObject}, trying the literal flat key first,
     * then walking the nested path produced by Vert.x's hierarchical config expansion.
     *
     * <p>For example, {@code "bootstrap.servers"} is first looked up as a flat key. If not
     * found, the path {@code bootstrap → servers} is traversed through nested objects.
     *
     * @param obj the {@link JsonObject} to search
     * @param dottedKey the dotted key (e.g., {@code "bootstrap.servers"})
     * @return the resolved value as a {@link String}, or {@code null} if not found
     */
    public static String resolveString(JsonObject obj, String dottedKey) {
        // Try flat key first
        Object flat = obj.getValue(dottedKey);
        if (flat != null) {
            return flat.toString();
        }

        JsonConfigPaths.LookupResult nested = JsonConfigPaths.resolve(obj, dottedKey);
        return nested.status() == JsonConfigPaths.LookupStatus.PRESENT && nested.value() != null
                ? nested.value().toString()
                : null;
    }

    // --- Flattening ---

    /**
     * Recursively flattens a (possibly nested) {@link JsonObject} into a flat {@link Map}
     * with dotted keys, restoring the original Kafka property names from Vert.x's
     * hierarchical expansion. Null values are skipped.
     *
     * <p>For example, a nested structure {@code {"bootstrap":{"servers":"host:9092"}}} is
     * flattened to {@code {"bootstrap.servers" -> "host:9092"}}.
     *
     * @param obj the {@link JsonObject} to flatten
     * @return a mutable map of dotted keys to string values
     */
    public static Map<String, String> flattenToMap(JsonObject obj) {
        Map<String, String> result = new HashMap<>();
        flattenRecursive("", obj, result);
        return result;
    }

    /**
     * Recursively walks a {@link JsonObject}, accumulating dotted-key entries into
     * {@code result}. Nested objects are traversed; all other non-null values are
     * converted to strings via {@link Object#toString()}.
     *
     * @param prefix the key prefix accumulated so far (empty string at the root)
     * @param obj the current {@link JsonObject} node to traverse
     * @param result the accumulator map
     */
    private static void flattenRecursive(String prefix, JsonObject obj, Map<String, String> result) {
        for (String key : obj.fieldNames()) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            Object val = obj.getValue(key);
            if (val instanceof JsonObject nested) {
                flattenRecursive(fullKey, nested, result);
            } else if (val != null) {
                result.put(fullKey, val.toString());
            }
        }
    }

    // --- SASL helpers ---

    /**
     * Puts a value into the map only when it is non-null and non-blank.
     *
     * @param map the target map
     * @param key the property key
     * @param value the value to conditionally insert
     */
    public static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * Builds the merged serde-config view handed to a {@code KafkaSerdeProvider}: the resolved
     * {@code serdeProperties} map plus the canonical {@code schemaRegistry} block, optionally
     * carrying the resolved JSON mapper profile id. This is the single definition of that view
     * shape, used by both the consumer config and the producer factory and read by every provider
     * (FR-AVRO-009, FR-JSON-035).
     *
     * <p>The top-level {@code "jsonProfile"} key is added <strong>only</strong> when
     * {@code jsonProfile} is non-null and non-blank, so the {@code vertx}/default serde path
     * sees a byte-for-byte unchanged bag. The JSON serde provider reads this key to resolve the
     * backing {@code ObjectMapper}; the Avro provider ignores it.
     *
     * @param schemaRegistry the {@code kafka.schemaRegistry} block (registry URL etc.)
     * @param serdeProperties the already-merged {@code serdeProperties} for the endpoint
     * @param jsonProfile the resolved JSON mapper profile id, or {@code null}/blank for the
     *     framework default ({@code vertx})
     * @return a {@code JsonObject} with {@code serdeProperties} and {@code schemaRegistry} keys, plus
     *     {@code jsonProfile} when a profile is selected
     */
    public static JsonObject serdeConfig(JsonObject schemaRegistry, JsonObject serdeProperties, String jsonProfile) {
        JsonObject config =
                new JsonObject().put("serdeProperties", serdeProperties).put("schemaRegistry", schemaRegistry);
        if (jsonProfile != null && !jsonProfile.isBlank()) {
            config.put("jsonProfile", jsonProfile);
        }
        return config;
    }

    /**
     * Builds the merged serde-config view with no JSON mapper profile selected, delegating to
     * {@link #serdeConfig(JsonObject, JsonObject, String)} with a {@code null} profile. This is the
     * default path: because no {@code "jsonProfile"} key is added, the produced bag is
     * byte-for-byte identical to the pre-profile shape, so the {@code vertx}/default serde path is
     * unchanged. The producer factory and every caller that carries no declaration-time profile use
     * this overload (ADR-0127); per-consumer config can still inject a profile through the
     * three-argument form upstream.
     *
     * @param schemaRegistry the {@code kafka.schemaRegistry} block (registry URL etc.)
     * @param serdeProperties the already-merged {@code serdeProperties} for the endpoint
     * @return a {@code JsonObject} with {@code serdeProperties} and {@code schemaRegistry} keys and no
     *     {@code jsonProfile} key
     */
    public static JsonObject serdeConfig(JsonObject schemaRegistry, JsonObject serdeProperties) {
        return serdeConfig(schemaRegistry, serdeProperties, null);
    }

    /**
     * Constructs a JAAS config string for SASL authentication.
     *
     * <p>Special characters in username and password are escaped to prevent injection.
     * The login module class is chosen based on the mechanism:
     * {@code SCRAM-*} mechanisms use {@code ScramLoginModule}; all others use
     * {@code PlainLoginModule}.
     *
     * @param mechanism the SASL mechanism (e.g., {@code "PLAIN"}, {@code "SCRAM-SHA-256"})
     * @param username the SASL username
     * @param password the SASL password
     * @return the JAAS config string ready for use as {@code sasl.jaas.config}
     */
    public static String buildJaasConfig(String mechanism, String username, String password) {
        String loginModule = mechanism.startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        return loginModule + " required username=\"" + escapeJaasValue(username) + "\" password=\""
                + escapeJaasValue(password) + "\";";
    }

    /**
     * Escapes special characters in JAAS config string values.
     * Backslashes and double-quotes are escaped to prevent config injection.
     *
     * @param value the raw credential value
     * @return the escaped value safe for embedding in a JAAS config string
     */
    public static String escapeJaasValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Auto-constructs the {@code sasl.jaas.config} property in {@code properties} when SASL
     * is configured but no JAAS config is present.
     *
     * <p>If {@code security.protocol} contains {@code "SASL"} and {@code sasl.jaas.config}
     * is absent, but both {@code sasl.username} and {@code sasl.password} are present, this
     * method removes those helper keys and inserts a correctly formatted JAAS config string.
     * If {@code sasl.jaas.config} is already set, the helper keys are removed without further
     * action.
     *
     * @param properties the mutable Kafka property map to update in place
     */
    public static void autoConstructSaslJaasConfig(Map<String, String> properties) {
        String securityProtocol = properties.get("security.protocol");
        if (securityProtocol != null
                && securityProtocol.contains("SASL")
                && !properties.containsKey("sasl.jaas.config")) {
            String username = properties.remove("sasl.username");
            String password = properties.remove("sasl.password");
            if (username != null && password != null) {
                String mechanism = properties.getOrDefault("sasl.mechanism", "PLAIN");
                properties.put("sasl.jaas.config", buildJaasConfig(mechanism, username, password));
            }
        } else {
            // Remove helper keys if jaas.config was set explicitly or SASL is not in use
            properties.remove("sasl.username");
            properties.remove("sasl.password");
        }
    }
}
