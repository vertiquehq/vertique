// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
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
}
