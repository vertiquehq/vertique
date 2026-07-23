// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.DbExceptionMapper;
import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.query.OrderKey;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies {@link PgOffsetPagedQuery} SQL validation (ORDER BY, LIMIT, OFFSET, lock clauses) and
 * SQL composition (ORDER BY generation, LIMIT, OFFSET, NULLS handling, query clauses).
 *
 * <p>SQL composition tests call {@link PgOffsetPagedQuery#buildDataSql} directly via the
 * package-private access available from within the same package, exercising the SQL generation
 * logic in isolation without a database connection.
 */
@ExtendWith(MockitoExtension.class)
class PgOffsetPagedQueryTest {

    @Mock
    private SqlClient client;

    @Mock
    private DbExceptionMapper exceptionMapper;

    // --- SQL validation ---

    @Nested
    @DisplayName("SQL validation")
    class SqlValidation {

        @ParameterizedTest
        @DisplayName("rejects SQL containing top-level ORDER BY")
        @ValueSource(
                strings = {
                    "SELECT * FROM items ORDER BY name",
                    "SELECT * FROM items order by name",
                    "SELECT * FROM items WHERE id = $1 ORDER BY name ASC"
                })
        void build_rejectsOrderBy(String sql) {
            assertThrows(IllegalArgumentException.class, () -> buildQuery(sql, "id"));
        }

        @ParameterizedTest
        @DisplayName("rejects SQL containing top-level LIMIT")
        @ValueSource(
                strings = {
                    "SELECT * FROM items LIMIT 10",
                    "SELECT * FROM items WHERE id = $1 LIMIT 10",
                    "SELECT * FROM items limit 10"
                })
        void build_rejectsLimit(String sql) {
            assertThrows(IllegalArgumentException.class, () -> buildQuery(sql, "id"));
        }

        @ParameterizedTest
        @DisplayName("rejects SQL containing top-level OFFSET")
        @ValueSource(
                strings = {
                    "SELECT * FROM items OFFSET 10",
                    "SELECT * FROM items WHERE id = $1 OFFSET 10",
                    "SELECT * FROM items offset 10"
                })
        void build_rejectsOffset(String sql) {
            assertThrows(IllegalArgumentException.class, () -> buildQuery(sql, "id"));
        }

        @ParameterizedTest
        @DisplayName("rejects inline lock clauses")
        @ValueSource(
                strings = {
                    "SELECT * FROM items FOR UPDATE",
                    "SELECT * FROM items FOR SHARE",
                    "SELECT * FROM items FOR NO KEY UPDATE",
                    "SELECT * FROM items FOR KEY SHARE"
                })
        void build_rejectsInlineLockClauses(String sql) {
            assertThrows(IllegalArgumentException.class, () -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("accepts valid SQL with WHERE clause")
        void build_acceptsValidSql() {
            assertDoesNotThrow(() -> buildQuery("SELECT id, name FROM items WHERE status = $1", "name", "id"));
        }

        @Test
        @DisplayName("accepts valid SQL without WHERE clause")
        void build_acceptsSqlWithoutWhere() {
            assertDoesNotThrow(() -> buildQuery("SELECT id, name FROM items", "name", "id"));
        }

        @Test
        @DisplayName("orderBy rejects SQL injection attempt in column name")
        void orderBy_rejectsSqlInjection() {
            assertThrows(
                    InvalidDataAccessUsageException.class,
                    () -> buildQuery("SELECT * FROM items", "id; DROP TABLE items--"));
        }
    }

    // --- SQL composition ---

    @Nested
    @DisplayName("SQL composition")
    class SqlComposition {

        @Test
        @DisplayName("single ASC column: ORDER BY col ASC LIMIT pageSize OFFSET 0 on page 0")
        void buildDataSql_singleColumnAsc_pageZero() {
            var q = buildQuery("SELECT id, name FROM items", "name");
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.asc("name")), 0, 20, null);
            assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC LIMIT 20 OFFSET 0", sql);
        }

        @Test
        @DisplayName("composite ASC columns: ORDER BY col1, col2 LIMIT pageSize OFFSET page*pageSize")
        void buildDataSql_compositeColumnsAsc() {
            var q = buildQuery("SELECT id, name FROM items", "name", "id");
            // page 2, pageSize 20 → offset = 40
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.asc("name"), OrderKey.asc("id")), 40, 20, null);
            assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC, \"id\" ASC LIMIT 20 OFFSET 40", sql);
        }

        @Test
        @DisplayName("descending order: ORDER BY col DESC LIMIT pageSize OFFSET 0")
        void buildDataSql_descending() {
            var q = buildQuery("SELECT id, name FROM items", "name");
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.desc("name")), 0, 20, null);
            assertEquals("SELECT id, name FROM items ORDER BY \"name\" DESC LIMIT 20 OFFSET 0", sql);
        }

        @Test
        @DisplayName("mixed directions: ORDER BY col1 DESC, col2 ASC LIMIT 10 OFFSET 30")
        void buildDataSql_mixedDirections() {
            var q = PgOffsetPagedQuery.<String>builder()
                    .on(client)
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .sql("SELECT id, priority FROM items")
                    .orderBy(OrderKey.desc("priority"), OrderKey.asc("id"))
                    .build();
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.desc("priority"), OrderKey.asc("id")), 30, 10, null);
            assertEquals(
                    "SELECT id, priority FROM items ORDER BY \"priority\" DESC, \"id\" ASC LIMIT 10 OFFSET 30", sql);
        }

        @Test
        @DisplayName("NULLS FIRST: ORDER BY col ASC NULLS FIRST LIMIT pageSize OFFSET 0")
        void buildDataSql_nullsFirst() {
            var q = PgOffsetPagedQuery.<String>builder()
                    .on(client)
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .sql("SELECT id, name FROM items")
                    .orderBy(OrderKey.asc("name").nullsFirst(), OrderKey.asc("id"))
                    .build();
            String sql = q.buildDataSql(
                    q.sql(), List.of(OrderKey.asc("name").nullsFirst(), OrderKey.asc("id")), 0, 20, null);
            assertEquals(
                    "SELECT id, name FROM items ORDER BY \"name\" ASC NULLS FIRST, \"id\" ASC LIMIT 20 OFFSET 0", sql);
        }

        @Test
        @DisplayName("NULLS LAST: ORDER BY col ASC NULLS LAST LIMIT pageSize OFFSET 0")
        void buildDataSql_nullsLast() {
            var q = PgOffsetPagedQuery.<String>builder()
                    .on(client)
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .sql("SELECT id, name FROM items")
                    .orderBy(OrderKey.asc("name").nullsLast(), OrderKey.asc("id"))
                    .build();
            String sql =
                    q.buildDataSql(q.sql(), List.of(OrderKey.asc("name").nullsLast(), OrderKey.asc("id")), 0, 20, null);
            assertEquals(
                    "SELECT id, name FROM items ORDER BY \"name\" ASC NULLS LAST, \"id\" ASC LIMIT 20 OFFSET 0", sql);
        }

        @Test
        @DisplayName("with query clause: LIMIT pageSize OFFSET 0 FOR UPDATE")
        void buildDataSql_withQueryClause() {
            var q = buildQuery("SELECT id, name FROM items", "name");
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.asc("name")), 0, 20, PgLockMode.FOR_UPDATE);
            assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC LIMIT 20 OFFSET 0 FOR UPDATE", sql);
        }

        @Test
        @DisplayName("page 0 produces OFFSET 0")
        void buildDataSql_pageZero_producesOffsetZero() {
            var q = buildQuery("SELECT id FROM items", "id");
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.asc("id")), 0, 20, null);
            assertTrue(sql.contains("OFFSET 0"), "SQL must contain OFFSET 0 for page 0");
        }

        @Test
        @DisplayName("page 2 with pageSize 15 produces OFFSET 30")
        void buildDataSql_page2_pageSize15_producesOffset30() {
            var q = buildQuery("SELECT id FROM items", "id");
            // page=2, pageSize=15 → offset = 2 * 15 = 30
            String sql = q.buildDataSql(q.sql(), List.of(OrderKey.asc("id")), 30, 15, null);
            assertTrue(sql.contains("LIMIT 15"), "SQL must contain LIMIT 15");
            assertTrue(sql.contains("OFFSET 30"), "SQL must contain OFFSET 30");
        }
    }

    // --- SQL validation with complex queries (SqlScanner-aware) ---

    @Nested
    @DisplayName("Complex SQL validation")
    class ComplexSqlValidation {

        @Test
        @DisplayName("accepts CTE with ORDER BY inside the CTE body")
        void build_acceptsCteWithOrderByInsideCte() {
            String sql = "WITH ranked AS (SELECT *, ROW_NUMBER() OVER (ORDER BY name) AS rn FROM items)"
                    + " SELECT * FROM ranked WHERE rn > 0";
            assertDoesNotThrow(() -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("accepts subquery with ORDER BY inside the subquery")
        void build_acceptsSubqueryWithOrderByInside() {
            String sql =
                    "SELECT * FROM (SELECT * FROM items ORDER BY name LIMIT 100) sub" + " WHERE sub.id IS NOT NULL";
            assertDoesNotThrow(() -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("accepts window function with ORDER BY inside OVER clause")
        void build_acceptsWindowFunctionOrderBy() {
            String sql =
                    "SELECT id, ROW_NUMBER() OVER (PARTITION BY category ORDER BY created_at) AS row_num FROM items";
            assertDoesNotThrow(() -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("accepts string literal containing ORDER BY text")
        void build_acceptsStringLiteralContainingOrderBy() {
            String sql = "SELECT * FROM items WHERE description = 'sorted ORDER BY name'";
            assertDoesNotThrow(() -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("still rejects top-level ORDER BY even when a subquery is present")
        void build_rejectsTopLevelOrderByWithSubquery() {
            String sql = "SELECT * FROM (SELECT id FROM items) sub ORDER BY id";
            assertThrows(IllegalArgumentException.class, () -> buildQuery(sql, "id"));
        }
    }

    // --- Helper ---

    private PgOffsetPagedQuery<String> buildQuery(String sql, String... columns) {
        return PgOffsetPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql(sql)
                .orderBy(columns)
                .build();
    }
}
