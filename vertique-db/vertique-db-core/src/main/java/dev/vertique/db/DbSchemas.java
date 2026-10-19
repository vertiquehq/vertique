// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable placement metadata: an explicit schema name for each named placement (for example
 * {@code job}). An absent mapping leaves the connection's default schema in use.
 *
 * <p>Names and schemas are literal strings. This class only requires them to be non-blank; it
 * applies no vendor identifier rules, quoting or length limits, and renders no SQL. Consumers
 * validate the values at their own engine boundary.
 */
public final class DbSchemas {

    private static final DbSchemas EMPTY = new DbSchemas(Map.of());

    private final Map<String, String> mappings;

    /**
     * Creates placement metadata from a defensive copy of the given mappings.
     *
     * @param mappings schema name per placement name
     * @throws NullPointerException if the map or any key or value is {@code null}
     * @throws IllegalArgumentException if any key or value is blank
     */
    public DbSchemas(Map<String, String> mappings) {
        Map<String, String> copy = Map.copyOf(Objects.requireNonNull(mappings, "mappings must not be null"));
        for (Map.Entry<String, String> entry : copy.entrySet()) {
            if (entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Schema mapping names must not be blank");
            }
            if (entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Schema mapping for '" + entry.getKey() + "' must not be blank");
            }
        }
        this.mappings = copy;
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
     * Returns every mapping.
     *
     * @return an immutable map from placement name to schema name
     */
    public Map<String, String> mappings() {
        return mappings;
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
