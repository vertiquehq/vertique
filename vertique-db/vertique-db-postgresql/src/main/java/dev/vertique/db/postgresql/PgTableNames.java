// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.query.SqlIdentifier;
import java.util.Objects;

/**
 * Qualifies table names with the schema mapped in {@link DbSchemas}, for every PostgreSQL adapter
 * and application that builds SQL against placed tables.
 *
 * <p>Each name is a single PostgreSQL identifier segment: it matches {@code
 * [A-Za-z_][A-Za-z0-9_]{0,62}}. A dotted value is rejected rather than split. Schema names beginning
 * with {@code pg_} and {@code information_schema} are reserved by PostgreSQL and rejected as schemas.
 * Case is preserved.
 */
public final class PgTableNames {

    /** Longest identifier PostgreSQL stores without truncation. */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private static final String RESERVED_SCHEMA_PREFIX = "pg_";

    private static final String INFORMATION_SCHEMA = "information_schema";

    private PgTableNames() {}

    /**
     * Returns the SQL reference for a table of the given placement.
     *
     * <p>When {@code schemas} maps {@code instanceName}, the result is the quoted schema and the
     * quoted table joined by a dot, for example {@code "jobs"."job_executions"}. Otherwise the
     * validated table segment is returned unquoted and unqualified.
     *
     * @param schemas the placement metadata
     * @param instanceName the placement name whose mapping applies
     * @param table the table name, a single identifier segment
     * @return the table reference to place in SQL
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the table, or the mapped schema, is not a valid single
     *     PostgreSQL identifier segment, or the mapped schema is reserved by PostgreSQL
     */
    public static String qualify(DbSchemas schemas, String instanceName, String table) {
        Objects.requireNonNull(schemas, "schemas must not be null");
        Objects.requireNonNull(instanceName, "instanceName must not be null");
        Objects.requireNonNull(table, "table must not be null");
        if (!isIdentifier(table)) {
            throw new IllegalArgumentException("Table name is not a valid single-segment PostgreSQL identifier");
        }
        String schema = schemas.schema(instanceName).orElse(null);
        if (schema == null) {
            return table;
        }
        if (!isIdentifier(schema)) {
            throw new IllegalArgumentException(
                    "Schema mapped for '" + instanceName + "' is not a valid single-segment PostgreSQL identifier");
        }
        if (schema.startsWith(RESERVED_SCHEMA_PREFIX) || schema.equals(INFORMATION_SCHEMA)) {
            throw new IllegalArgumentException("Schema mapped for '" + instanceName + "' is reserved by PostgreSQL");
        }
        return SqlIdentifier.quote(schema) + "." + SqlIdentifier.quote(table);
    }

    private static boolean isIdentifier(String name) {
        int length = name.length();
        if (length == 0 || length > MAX_IDENTIFIER_LENGTH) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean letterOrUnderscore = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
            boolean digit = c >= '0' && c <= '9';
            if (!letterOrUnderscore && !(digit && i > 0)) {
                return false;
            }
        }
        return true;
    }
}
