// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
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
 * <p><strong>Strict keys.</strong> Unlike the root {@link FlywayConfig}, an instance object accepts
 * only the override fields below: any other key, such as a misspelled field name, is rejected while
 * the configuration is bound, with a message that names the instance and the key but never a value.
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
 * @param lockRetryCount the number of one-second retries Flyway makes to acquire its history-table lock,
 *     {@code -1} for no bound; {@code null} inherits the root value
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
        String password,

        Integer lockRetryCount) {

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
     * Rejects a key that is not an override field. Jackson routes every key without a record component
     * here; the method never stores anything, so it takes no part in equality, rendering or
     * serialization.
     *
     * @param key the unsupported key
     * @param value the unsupported key's value, deliberately left out of the failure message
     * @throws ConfigurationException always; the message names {@code flyway.instances.<name>}, the key
     *     and the supported fields, never a value
     */
    @JsonAnySetter
    void rejectUnsupportedKey(String key, Object value) {
        throw new ConfigurationException("flyway.instances." + name + ": the key '" + key
                + "' is not a supported instance override; supported fields are " + SUPPORTED_FIELDS);
    }

    /** The override fields, taken from the record components so there is no second list to maintain. */
    private static final List<String> SUPPORTED_FIELDS = Arrays.stream(FlywayInstanceConfig.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(component -> !component.equals("name"))
            .toList();

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
                + ", lockRetryCount=" + lockRetryCount
                + "]";
    }
}
