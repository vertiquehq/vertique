// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Overrides for one named Flyway instance, bound from {@code flyway.instances.<name>}.
 *
 * <p>Only {@code name} is required. Every other component is {@code null} when it was omitted or set
 * to JSON {@code null}, which means "inherit": the runner resolves it against the root
 * {@link FlywayConfig} and the built-in defaults. An explicit {@code false}, empty list or empty map
 * is a value that replaces the inherited one. Lists and maps replace, never merge.
 *
 * <p><strong>Secret hygiene.</strong> {@code password} and {@code placeholders} are accepted on input
 * but Jackson never serializes them, and {@link #toString()} renders the password redacted and the
 * placeholder keys without values.
 *
 * @param name the instance name; must match a wired migration declaration
 * @param mode the instance mode; may not be more permissive than the root mode
 * @param locations migration locations replacing the declared ones
 * @param schemas the managed schema list replacing the root list; {@code []} clears it
 * @param table the history table
 * @param target the target version
 * @param outOfOrder whether out-of-order migrations are allowed
 * @param baselineOnMigrate whether Flyway baselines a schema that has no history
 * @param baselineVersion the baseline version
 * @param validateOnMigrate whether Flyway validates before migrating
 * @param placeholders placeholder values replacing the root map; {@code {}} clears it
 * @param user the migration user; set together with {@code password}
 * @param password the migration password; set together with {@code user}
 */
public record FlywayInstanceConfig(
        String name,
        FlywayMode mode,
        List<String> locations,
        List<String> schemas,
        String table,
        String target,
        Boolean outOfOrder,
        Boolean baselineOnMigrate,
        String baselineVersion,
        Boolean validateOnMigrate,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        Map<String, String> placeholders,

        String user,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password) {

    /**
     * Creates instance overrides, keeping absent components {@code null} and copying present
     * collections.
     *
     * @throws NullPointerException if {@code name} or an element of a present collection is
     *     {@code null}
     */
    public FlywayInstanceConfig {
        Objects.requireNonNull(name, "name must not be null");
        locations = locations == null ? null : List.copyOf(locations);
        schemas = schemas == null ? null : List.copyOf(schemas);
        placeholders = placeholders == null ? null : Map.copyOf(placeholders);
    }

    /**
     * Redacted rendering: the password is never included and the placeholders render only their keys.
     *
     * @return a redacted string rendering of these overrides
     */
    @Override
    public String toString() {
        return "FlywayInstanceConfig[name=" + name
                + ", mode=" + mode
                + ", locations=" + locations
                + ", schemas=" + schemas
                + ", table=" + table
                + ", target=" + target
                + ", outOfOrder=" + outOfOrder
                + ", baselineOnMigrate=" + baselineOnMigrate
                + ", baselineVersion=" + baselineVersion
                + ", validateOnMigrate=" + validateOnMigrate
                + ", placeholders=" + (placeholders == null ? "null" : placeholders.keySet())
                + ", user=" + user
                + ", password=" + (password != null ? "<redacted>" : "null")
                + "]";
    }
}
