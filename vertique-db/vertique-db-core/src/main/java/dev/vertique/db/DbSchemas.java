// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.vertique.core.exception.ConfigurationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable placement metadata: an explicit schema name for each named placement (for example
 * {@code job}). An absent mapping leaves the connection's default schema in use.
 *
 * <p>Bound from the {@code schemas} setting of the {@code db} config section, a JSON object from
 * placement name to schema name, and exposed as {@link DbPoolConfig#schemas()}.
 *
 * <p>Names and schemas are literal strings. This type only requires them to be non-blank; it applies
 * no vendor identifier rules, quoting or length limits, and renders no SQL. Consumers validate the
 * values at their own engine boundary.
 *
 * @param mappings schema name per placement name; an immutable defensive copy
 * @throws NullPointerException if the map or any key or value is {@code null}
 * @throws ConfigurationException if any key or value is blank; the message names the setting and
 *     never includes a configured value
 */
public record DbSchemas(@JsonValue Map<String, String> mappings) {

    private static final DbSchemas EMPTY = new DbSchemas(Map.of());

    /** Validates and copies the mappings. */
    public DbSchemas {
        mappings = Map.copyOf(mappings);
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey().isBlank()) {
                throw new ConfigurationException("Config path 'db.schemas' must not contain a blank key");
            }
            if (entry.getValue().isBlank()) {
                throw new ConfigurationException(
                        "Config path 'db.schemas." + entry.getKey() + "' must be a non-blank string");
            }
        }
    }

    /**
     * Binds the {@code schemas} object. Every value must be a JSON string: a number, boolean, object,
     * array or {@code null} is rejected instead of being coerced.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static DbSchemas fromConfig(Map<String, Object> raw) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof String schema)) {
                throw new ConfigurationException(
                        "Config path 'db.schemas." + entry.getKey() + "' must be a non-blank string");
            }
            mappings.put(entry.getKey(), schema);
        }
        return new DbSchemas(mappings);
    }

    /**
     * Returns placement metadata with no mappings.
     *
     * @return the empty placement metadata
     */
    public static DbSchemas empty() {
        return EMPTY;
    }

    /**
     * Returns the schema mapped to a placement name.
     *
     * @param instanceName the placement name
     * @return the schema, or empty when the name has no mapping
     * @throws NullPointerException if {@code instanceName} is {@code null}
     */
    public Optional<String> schema(String instanceName) {
        Objects.requireNonNull(instanceName, "instanceName must not be null");
        return Optional.ofNullable(mappings.get(instanceName));
    }
}
