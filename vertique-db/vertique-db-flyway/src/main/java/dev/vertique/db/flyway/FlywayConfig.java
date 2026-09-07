// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * <p><strong>Secret hygiene.</strong> {@code password} and {@code placeholders} are read from
 * configuration but are {@link JsonProperty.Access#WRITE_ONLY}: Jackson never serializes them back
 * out. {@link #toString()} renders the password redacted, the placeholder keys without values, and a
 * {@code password=} parameter in {@code jdbcUrl} masked, on the class and on its builder.
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

        @Override
        public String toString() {
            return "FlywayConfig.FlywayConfigBuilder[jdbcUrl=" + redactJdbcUrl(jdbcUrl)
                    + ", user=" + user
                    + ", password=" + (password != null ? "<redacted>" : "null")
                    + "]";
        }
    }
}
