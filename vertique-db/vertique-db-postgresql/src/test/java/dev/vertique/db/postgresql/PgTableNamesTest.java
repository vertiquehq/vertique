// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.DbSchemas;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Unit tests for {@link PgTableNames#qualify}: only single-segment PostgreSQL identifiers are
 * accepted, a mapped schema qualifies the table with both segments quoted, and an unmapped
 * instance gets the validated table unchanged.
 */
@DisplayName("PgTableNames")
class PgTableNamesTest {

    /** Longest identifier PostgreSQL stores without truncation. */
    static final int MAX_IDENTIFIER_LENGTH = 63;

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

    @TestFactory
    @DisplayName("qualifies only single-segment PostgreSQL schemas")
    Stream<DynamicTest> qualifiesOnlySingleSegmentPostgresSchemas() {
        Stream<DynamicTest> accepted = ACCEPTED_IDENTIFIERS.stream()
                .flatMap(name -> Stream.of(mappedSchemaIsQuotedWithTable(name), unmappedTableIsReturnedUnquoted(name)));
        // A blank schema cannot even be placed in a DbSchemas, so blank names are table-only cases.
        Stream<DynamicTest> rejectedSchemas = REJECTED_IDENTIFIERS.stream()
                .filter(name -> !name.isBlank())
                .map(PgTableNamesTest::invalidSchemaIsRejected);
        Stream<DynamicTest> rejectedTables = REJECTED_IDENTIFIERS.stream()
                .flatMap(name ->
                        Stream.of(invalidTableIsRejectedWhenMapped(name), invalidTableIsRejectedUnmapped(name)));
        Stream<DynamicTest> reserved = REJECTED_SCHEMA_NAMES.stream().map(PgTableNamesTest::reservedSchemaIsRejected);
        return Stream.of(
                        accepted,
                        rejectedSchemas,
                        rejectedTables,
                        reserved,
                        Stream.of(
                                mappedPairIsQuotedPerSegment(),
                                otherInstanceMappingDoesNotAffectQualification(),
                                invalidMappingOfOtherInstanceDoesNotAffectQualification()))
                .flatMap(s -> s);
    }

    private static DynamicTest mappedSchemaIsQuotedWithTable(String name) {
        return DynamicTest.dynamicTest("mapped schema " + label(name) + " is quoted and case-preserved", () -> {
            // Given an instance mapped to a valid schema
            DbSchemas schemas = new DbSchemas(Map.of("job", name));

            // When qualifying a table
            String qualified = PgTableNames.qualify(schemas, "job", "Job_Executions");

            // Then both segments are quoted with their case preserved
            assertEquals("\"" + name + "\".\"Job_Executions\"", qualified);
        });
    }

    private static DynamicTest unmappedTableIsReturnedUnquoted(String name) {
        return DynamicTest.dynamicTest("unmapped table " + label(name) + " is returned unquoted", () -> {
            // Given no mapping for the instance
            DbSchemas schemas = DbSchemas.empty();

            // When qualifying a valid table; Then the validated table is returned as is
            assertEquals(name, PgTableNames.qualify(schemas, "job", name));
        });
    }

    private static DynamicTest invalidSchemaIsRejected(String name) {
        return DynamicTest.dynamicTest("rejects schema " + label(name), () -> {
            // Given an instance mapped to an invalid schema
            DbSchemas schemas = new DbSchemas(Map.of("job", name));

            // When qualifying a valid table; Then it is rejected
            assertThrows(IllegalArgumentException.class, () -> PgTableNames.qualify(schemas, "job", "job_executions"));
        });
    }

    private static DynamicTest invalidTableIsRejectedWhenMapped(String name) {
        return DynamicTest.dynamicTest("rejects table " + label(name) + " when the schema is mapped", () -> {
            // Given an instance mapped to a valid schema
            DbSchemas schemas = new DbSchemas(Map.of("job", "jobs"));

            // When qualifying an invalid table; Then it is rejected
            assertThrows(IllegalArgumentException.class, () -> PgTableNames.qualify(schemas, "job", name));
        });
    }

    private static DynamicTest invalidTableIsRejectedUnmapped(String name) {
        return DynamicTest.dynamicTest("rejects table " + label(name) + " when no schema is mapped", () -> {
            // Given no mapping for the instance
            DbSchemas schemas = DbSchemas.empty();

            // When qualifying an invalid table; Then it is still rejected, not passed through
            assertThrows(IllegalArgumentException.class, () -> PgTableNames.qualify(schemas, "job", name));
        });
    }

    private static DynamicTest reservedSchemaIsRejected(String name) {
        return DynamicTest.dynamicTest("rejects reserved schema " + label(name), () -> {
            // Given an instance mapped to a PostgreSQL system schema name
            DbSchemas schemas = new DbSchemas(Map.of("job", name));

            // When qualifying a valid table; Then it is rejected
            assertThrows(IllegalArgumentException.class, () -> PgTableNames.qualify(schemas, "job", "job_executions"));
        });
    }

    private static DynamicTest mappedPairIsQuotedPerSegment() {
        return DynamicTest.dynamicTest("qualifies a mapped table as quoted schema dot quoted table", () -> {
            // Given the job instance mapped to the jobs schema
            DbSchemas schemas = new DbSchemas(Map.of("job", "jobs"));

            // When qualifying the job table; Then both segments are quoted
            assertEquals("\"jobs\".\"job_executions\"", PgTableNames.qualify(schemas, "job", "job_executions"));
        });
    }

    private static DynamicTest otherInstanceMappingDoesNotAffectQualification() {
        return DynamicTest.dynamicTest("a mapping for another instance does not qualify this one", () -> {
            // Given only the workflow instance is mapped
            DbSchemas schemas = new DbSchemas(Map.of("workflow", "wf"));

            // When qualifying a job table; Then it stays unqualified
            assertEquals("job_executions", PgTableNames.qualify(schemas, "job", "job_executions"));
        });
    }

    private static DynamicTest invalidMappingOfOtherInstanceDoesNotAffectQualification() {
        return DynamicTest.dynamicTest("an invalid mapping for another instance is not this instance's concern", () -> {
            // Given the workflow instance is mapped to an invalid schema and the job instance is not mapped
            DbSchemas schemas = new DbSchemas(Map.of("workflow", "wf.invalid"));

            // When qualifying a job table; Then it stays unqualified
            assertEquals("job_executions", PgTableNames.qualify(schemas, "job", "job_executions"));
        });
    }

    private static String label(String name) {
        String printable = name.replace("\n", "\\n").replace("\t", "\\t");
        return printable.length() > 40 ? "<" + name.length() + " chars>" : "'" + printable + "'";
    }
}
