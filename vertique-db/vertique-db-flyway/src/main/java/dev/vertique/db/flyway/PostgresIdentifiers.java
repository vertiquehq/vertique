// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static dev.vertique.db.flyway.NamedMigrationFailure.CONFIGURATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.OP_CONFIGURATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.OP_RUN;
import static dev.vertique.db.flyway.NamedMigrationFailure.PREFLIGHT;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PostgreSQL identifier rules of named migration, all case-sensitive.
 *
 * <ul>
 *   <li>A schema name is a single identifier segment matching {@code [A-Za-z_][A-Za-z0-9_]{0,62}}; names
 *       beginning with {@code pg_} and {@code information_schema} are reserved by PostgreSQL.
 *   <li>A history table name uses the same syntax with at most 57 characters, leaving room for the
 *       {@code _pk} and {@code _s_idx} relations native Flyway derives from it.
 *   <li>Within one schema, no history table may equal another history table or one of its derived
 *       relation names.
 * </ul>
 */
final class PostgresIdentifiers {

    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Pattern HISTORY_TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,56}");
    private static final String RESERVED_SCHEMA_PREFIX = "pg_";
    private static final String INFORMATION_SCHEMA = "information_schema";

    /** Suffixes of the relations native Flyway creates beside a PostgreSQL history table. */
    private static final List<String> DERIVED_SUFFIXES = List.of("", "_pk", "_s_idx");

    private PostgresIdentifiers() {}

    /**
     * Applies the schema-name and history-table rules to every view and rejects collisions among the
     * history tables of views whose schema is known.
     *
     * @param views the resolved views, disabled ones included
     * @throws MigrationRunException with failure code {@code CONFIGURATION} when a rule is broken
     */
    static void validate(List<MigrationInstanceView> views) {
        for (MigrationInstanceView view : views) {
            for (String schema : view.schemas()) {
                checkSchema(view.name(), schema);
            }
            if (view.schema() != null) {
                checkSchema(view.name(), view.schema());
            }
            if (!HISTORY_TABLE.matcher(view.table()).matches()) {
                throw new MigrationRunException(
                        "instance '" + view.name() + "' names a history table that breaks the PostgreSQL rules for"
                                + " named history tables: one identifier of letters, digits and underscores, not"
                                + " starting with a digit, at most 57 characters",
                        CONFIGURATION,
                        OP_CONFIGURATION,
                        null);
            }
        }
        String collision = collision(views);
        if (collision != null) {
            throw new MigrationRunException(collision, CONFIGURATION, OP_CONFIGURATION, null);
        }
    }

    /**
     * Checks a default schema resolved from an instance's own connection against the schema-name rules.
     *
     * @param instance the instance
     * @param schema the resolved default schema
     * @throws MigrationRunException with failure code {@code PREFLIGHT} when the name breaks a rule
     */
    static void checkResolvedSchema(String instance, String schema) {
        if (!isSchemaName(schema)) {
            throw new MigrationRunException(
                    "the connection default schema of instance '" + instance
                            + "' breaks the PostgreSQL schema-name rules for named migration",
                    PREFLIGHT,
                    OP_RUN,
                    null);
        }
    }

    /**
     * Finds the first collision among the history tables of views whose schema is known, counting the
     * relations native Flyway derives from each table.
     *
     * @param views the views
     * @return a fixed detail naming the two instances, or {@code null} when nothing collides
     */
    static String collision(List<MigrationInstanceView> views) {
        Map<List<String>, String> owners = new HashMap<>();
        for (MigrationInstanceView view : views) {
            if (view.schema() == null) {
                continue;
            }
            for (String suffix : DERIVED_SUFFIXES) {
                String previous = owners.putIfAbsent(List.of(view.schema(), view.table() + suffix), view.name());
                if (previous != null && !previous.equals(view.name())) {
                    return "the history tables of instances '" + previous + "' and '" + view.name()
                            + "' collide in one schema, counting the key and index relations native Flyway"
                            + " derives from each history table";
                }
            }
        }
        return null;
    }

    private static void checkSchema(String instance, String schema) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new MigrationRunException(
                    "instance '" + instance + "' manages a schema whose name breaks the PostgreSQL schema-name"
                            + " rules: one identifier of letters, digits and underscores, not starting with a"
                            + " digit, at most 63 characters",
                    CONFIGURATION,
                    OP_CONFIGURATION,
                    null);
        }
        if (isReserved(schema)) {
            throw new MigrationRunException(
                    "instance '" + instance + "' manages a schema whose name PostgreSQL reserves for system schemas",
                    CONFIGURATION,
                    OP_CONFIGURATION,
                    null);
        }
    }

    private static boolean isSchemaName(String schema) {
        return SCHEMA.matcher(schema).matches() && !isReserved(schema);
    }

    private static boolean isReserved(String schema) {
        return schema.startsWith(RESERVED_SCHEMA_PREFIX) || schema.equals(INFORMATION_SCHEMA);
    }
}
