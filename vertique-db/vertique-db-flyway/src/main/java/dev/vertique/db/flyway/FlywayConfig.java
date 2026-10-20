// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.KeyedBy;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Flyway migration configuration. Deserialized from the {@code "flyway"} section of the
 * application config.
 *
 * <p>Supports separate DDL credentials for migrations (best practice: use a privileged user for
 * migrations, a restricted user for runtime). Falls back to {@link
 * dev.vertique.db.DbPoolConfig} credentials when {@link #user()} / {@link #password()} are not
 * set.
 *
 * <p>Example config:
 *
 * <pre>{@code
 * {
 *   "flyway": {
 *     "mode": "VALIDATE",
 *     "jdbcUrl": "jdbc:postgresql://localhost:5432/mydb",
 *     "user": "ddl_user",
 *     "password": "ddl_secret",
 *     "locations": ["classpath:db/migration", "classpath:db/migration/extra"],
 *     "schemas": ["public"],
 *     "placeholders": { "schema": "public" },
 *     "outOfOrder": false,
 *     "cleanDisabled": true
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Named instances.</strong> By default the runner performs one Flyway run with one history
 * table. Setting {@link #named()} selects named mode: every wired
 * {@link dev.vertique.db.MigrationDefinition} becomes an instance with its own history, the root
 * policy fields above are inherited unless an instance overrides them, root {@code locations} is not
 * inherited, and the root {@code mode} is a ceiling that an instance may lower but never raise. The
 * optional {@link #instances()} object only overrides settings of individual instances and has no
 * effect on which mode is used; overrides without {@code named} are rejected rather than ignored.
 *
 * <pre>{@code
 * {
 *   "flyway": {
 *     "mode": "MIGRATE",
 *     "named": true,
 *     "instances": {
 *       "job": {},
 *       "app": { "target": "42" }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Secret hygiene.</strong> {@code password} and {@code placeholders} are read from
 * configuration but are {@link JsonProperty.Access#WRITE_ONLY}: Jackson never serializes them back
 * out, and the same holds for every nested {@link FlywayInstanceConfig}. {@link #toString()} renders
 * the password redacted, the placeholder keys without values, a {@code password=} parameter in
 * {@code jdbcUrl} masked and every instance in its redacted form, on the class and on its builder.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class FlywayConfig {

    // --- Core ---

    /** The Flyway operation mode. Defaults to {@link FlywayMode#MIGRATE}. */
    @Builder.Default
    private final FlywayMode mode = FlywayMode.MIGRATE;

    /** JDBC URL for the migration connection. If not set, uses the pool config host/port/database. */
    private final String jdbcUrl;

    /**
     * DDL user for migrations. If {@code null}, falls back to {@link
     * dev.vertique.db.DbPoolConfig#user()}.
     */
    private final String user;

    /**
     * DDL password for migrations. If {@code null}, falls back to {@link
     * dev.vertique.db.DbPoolConfig#password()}.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private final String password;

    // --- Migration locations ---

    /**
     * Classpath or filesystem locations for migration scripts. Each entry may be a classpath
     * location (e.g., {@code "classpath:db/migration"}) or a filesystem path (e.g.,
     * {@code "filesystem:/opt/migrations"}). Defaults to {@code ["classpath:db/migration"]}.
     */
    @Builder.Default
    private final List<String> locations = List.of("classpath:db/migration");

    // --- Schema management ---

    /**
     * Target migration version. When {@code null} (the default), Flyway migrates to the latest
     * available version.
     */
    private final String target;

    /**
     * Schemas managed by Flyway. When empty (the default), Flyway uses the default schema of the
     * connection.
     */
    @Builder.Default
    private final List<String> schemas = List.of();

    // --- Template substitution ---

    /**
     * Placeholder key-value pairs for template substitution in migration scripts. Flyway replaces
     * {@code ${key}} occurrences in SQL scripts with the corresponding value. Defaults to an empty
     * map (no substitutions).
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Builder.Default
    private final Map<String, String> placeholders = Map.of();

    // --- Migration policy ---

    /**
     * Whether out-of-order migrations are allowed. When {@code true}, Flyway applies migrations
     * that were added with a lower version than already-applied ones. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean outOfOrder = false;

    /**
     * Whether {@code flyway clean} is disabled. Defaults to {@code true} as a production safety
     * net — enabling {@code clean} in production can wipe the entire schema.
     */
    @Builder.Default
    private final boolean cleanDisabled = true;

    // --- Baseline ---

    /**
     * Whether to automatically apply a baseline to an existing database on first migration.
     * Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean baselineOnMigrate = false;

    /**
     * Baseline version applied when {@link #baselineOnMigrate()} is {@code true}. Defaults to
     * {@code "1"}.
     */
    @Builder.Default
    private final String baselineVersion = "1";

    /**
     * Whether to automatically validate applied migrations against the available ones when the
     * schema history table is created. Defaults to {@code true}.
     */
    @Builder.Default
    private final boolean validateOnMigrate = true;

    // --- Named instances ---

    /**
     * Whether to run every wired migration declaration as its own named instance with its own
     * history table. Defaults to {@code false}: one Flyway run with one history table.
     */
    @Builder.Default
    private final boolean named = false;

    /**
     * Per-instance overrides of a named run; {@code null} or empty means every instance uses the
     * root settings. Valid only with {@link #named()}. Bound from the keyed
     * {@code flyway.instances} object: each key becomes the {@code name} of its entry.
     */
    private final List<FlywayInstanceConfig> instances;

    /**
     * Maximum number of one-second retries Flyway makes to acquire its history-table lock when another
     * process holds it. {@code null} (the default) keeps Flyway's own default of {@code 50}; {@code -1}
     * retries without a bound. Values below {@code -1} are rejected. Applies to the single-Flyway path
     * and, unless an instance overrides it, to every named instance.
     */
    private final Integer lockRetryCount;

    // --- Secret hygiene ---

    /**
     * Redacted rendering: the DDL {@code password} is never included, a {@code password=} parameter
     * embedded in {@code jdbcUrl} is masked, and {@code placeholders} renders only its keys, so a log line
     * or exception message can never reveal a credential.
     *
     * @return a redacted string rendering of this config
     */
    @Override
    public String toString() {
        return "FlywayConfig[mode=" + mode
                + ", jdbcUrl=" + redactJdbcUrl(jdbcUrl)
                + ", user=" + user
                + ", password=" + (password != null ? "<redacted>" : "null")
                + ", locations=" + locations
                + ", target=" + target
                + ", schemas=" + schemas
                + ", placeholders=" + (placeholders == null || placeholders.isEmpty() ? "{}" : placeholders.keySet())
                + ", outOfOrder=" + outOfOrder
                + ", cleanDisabled=" + cleanDisabled
                + ", baselineOnMigrate=" + baselineOnMigrate
                + ", baselineVersion=" + baselineVersion
                + ", validateOnMigrate=" + validateOnMigrate
                + ", named=" + named
                + ", instances=" + instances
                + ", lockRetryCount=" + lockRetryCount
                + "]";
    }

    private static final Pattern URL_PASSWORD = Pattern.compile("([;&?]password=)[^;&]*");

    /** Masks a {@code password=} query parameter so a credential embedded in the URL never renders. */
    private static String redactJdbcUrl(String url) {
        return url == null ? "null" : URL_PASSWORD.matcher(url).replaceAll("$1<redacted>");
    }

    /**
     * Builder shell declared so the generated builder does not render the password: Lombok merges this
     * class with the generated one and keeps this {@code toString()} instead of generating its own.
     */
    public static class FlywayConfigBuilder {

        /**
         * Sets the named instance overrides from an immutable copy of the list. Carries the keyed-object
         * binding because Jackson reads annotations from this builder method, not from the
         * {@code instances} field.
         *
         * @param instances the instance overrides, or {@code null} for none
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        @KeyedBy("name")
        public FlywayConfigBuilder instances(List<FlywayInstanceConfig> instances) {
            this.instances = instances == null ? null : List.copyOf(instances);
            return this;
        }

        @Override
        public String toString() {
            return "FlywayConfig.FlywayConfigBuilder[jdbcUrl=" + redactJdbcUrl(jdbcUrl)
                    + ", user=" + user
                    + ", password=" + (password != null ? "<redacted>" : "null")
                    + ", instances=" + instances
                    + "]";
        }
    }
}
