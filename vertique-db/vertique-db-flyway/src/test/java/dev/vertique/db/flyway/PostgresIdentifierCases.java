// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.ArrayList;
import java.util.List;

/**
 * The PostgreSQL identifier cases shared by the named-migration tests. The first four constants are a
 * literal copy of the lists the runtime placement tests use for {@code PgTableNames}, so both sides of
 * the PostgreSQL boundary are held to one table; the derived lists adapt it to the two kinds of names
 * the migration backend checks.
 *
 * <ul>
 *   <li>Schema names follow the runtime rule unchanged: at most {@value #MAX_IDENTIFIER_LENGTH}
 *       characters, and the system names beginning with {@code pg_} or equal to {@code
 *       information_schema} are rejected.
 *   <li>History table names use the same syntax but are capped at {@value #MAX_HISTORY_TABLE_LENGTH}
 *       characters, which leaves room for the {@code _pk} and {@code _s_idx} relations native Flyway
 *       derives from them. The system schema names are ordinary, valid table names.
 * </ul>
 *
 * <p>Blank names are listed for completeness; the runner rejects them before the database rules run.
 */
final class PostgresIdentifierCases {

    /** Longest PostgreSQL identifier. */
    static final int MAX_IDENTIFIER_LENGTH = 63;

    /** Longest named history table: room is left for the {@code _s_idx} suffix native Flyway appends. */
    static final int MAX_HISTORY_TABLE_LENGTH = MAX_IDENTIFIER_LENGTH - "_s_idx".length();

    /** Names that are valid as a schema and as a table: single segment, {@code [A-Za-z_][A-Za-z0-9_]*}, at most 63 characters. */
    static final List<String> ACCEPTED_IDENTIFIERS =
            List.of("a", "_", "_x", "x1", "job_executions", "Jobs", "MixedCase_1", "x".repeat(MAX_IDENTIFIER_LENGTH));

    /** Names that are invalid as a schema and as a table. */
    static final List<String> REJECTED_IDENTIFIERS = List.of(
            "",
            " ",
            "a.b",
            ".a",
            "a.",
            "\"x\"",
            "x\"y",
            "a'b",
            "a-b",
            "my schema",
            "tab\tx",
            "jobs\n",
            "\njobs",
            "1jobs",
            "jöbs",
            "jobs; DROP SCHEMA public",
            "jobs\"; DROP SCHEMA public; --",
            "x".repeat(MAX_IDENTIFIER_LENGTH + 1));

    /** Names that are valid single segments but reserved by PostgreSQL for system schemas. */
    static final List<String> REJECTED_SCHEMA_NAMES = List.of("pg_x", "pg_catalog", "pg_", "information_schema");

    private PostgresIdentifierCases() {}

    /**
     * Returns the schema names the PostgreSQL backend must accept.
     *
     * @return the accepted schema names, including the 63-character one
     */
    static List<String> acceptedSchemas() {
        return ACCEPTED_IDENTIFIERS;
    }

    /**
     * Returns the schema names the PostgreSQL backend must reject: every invalid identifier, including
     * the 64-character one, and every reserved system schema name.
     *
     * @return the rejected schema names
     */
    static List<String> rejectedSchemas() {
        List<String> rejected = new ArrayList<>(REJECTED_IDENTIFIERS);
        rejected.addAll(REJECTED_SCHEMA_NAMES);
        return List.copyOf(rejected);
    }

    /**
     * Returns the history table names the PostgreSQL backend must accept: the accepted identifiers with
     * the longest one shortened to the 57-character cap, plus the system schema names, which are valid
     * table names.
     *
     * @return the accepted history table names
     */
    static List<String> acceptedHistoryTables() {
        List<String> accepted = new ArrayList<>();
        for (String name : ACCEPTED_IDENTIFIERS) {
            accepted.add(name.length() > MAX_HISTORY_TABLE_LENGTH ? "x".repeat(MAX_HISTORY_TABLE_LENGTH) : name);
        }
        accepted.addAll(REJECTED_SCHEMA_NAMES);
        return List.copyOf(accepted);
    }

    /**
     * Returns the history table names the PostgreSQL backend must reject: the invalid identifiers with
     * the over-long one replaced by a 58-character name, plus the names that are valid PostgreSQL
     * identifiers but longer than the history table cap.
     *
     * @return the rejected history table names
     */
    static List<String> rejectedHistoryTables() {
        List<String> rejected = new ArrayList<>();
        for (String name : REJECTED_IDENTIFIERS) {
            rejected.add(name.length() > MAX_IDENTIFIER_LENGTH ? "x".repeat(MAX_HISTORY_TABLE_LENGTH + 1) : name);
        }
        rejected.add("x".repeat(MAX_IDENTIFIER_LENGTH));
        rejected.add("x".repeat(MAX_IDENTIFIER_LENGTH + 1));
        return List.copyOf(rejected);
    }
}
