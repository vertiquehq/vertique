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

@ExtendWith(MockitoExtension.class)
class PgPagedQueryTest {

    @Mock
    private SqlClient client;

    @Mock
    private DbExceptionMapper exceptionMapper;

    // --- SQL validation ---

    @ParameterizedTest
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
    void build_acceptsValidSql() {
        assertDoesNotThrow(() -> buildQuery("SELECT id, name FROM items WHERE status = $1", "name", "id"));
    }

    @Test
    void build_acceptsSqlWithoutWhere() {
        assertDoesNotThrow(() -> buildQuery("SELECT id, name FROM items", "name", "id"));
    }

    @Test
    @DisplayName("orderBy rejects SQL injection attempt")
    void orderBy_rejectsSqlInjection() {
        assertThrows(
                InvalidDataAccessUsageException.class,
                () -> buildQuery("SELECT * FROM items", "id; DROP TABLE items--"));
    }

    // --- SQL composition: first page (no keyset) ---

    @Test
    void buildPaginatedSql_firstPage_singleColumn() {
        var q = buildQuery("SELECT id, name FROM items", "name");
        String sql = q.buildPaginatedSql(q.sql(), List.of(OrderKey.asc("name")), false, List.of(), 0, 21, null);
        assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC LIMIT 21", sql);
    }

    @Test
    void buildPaginatedSql_firstPage_compositeColumns() {
        var q = buildQuery("SELECT id, name FROM items", "name", "id");
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name"), OrderKey.asc("id")), false, List.of(), 0, 21, null);
        assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC, \"id\" ASC LIMIT 21", sql);
    }

    @Test
    void buildPaginatedSql_firstPage_descending() {
        var q = buildQuery("SELECT id, name FROM items", "name");
        String sql = q.buildPaginatedSql(q.sql(), List.of(OrderKey.desc("name")), false, List.of(), 0, 21, null);
        assertEquals("SELECT id, name FROM items ORDER BY \"name\" DESC LIMIT 21", sql);
    }

    // --- SQL composition: subsequent page (with keyset, Tier A uniform direction) ---

    @Test
    void buildPaginatedSql_withKeyset_noBaseWhere() {
        var q = buildQuery("SELECT id, name FROM items", "name", "id");
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name"), OrderKey.asc("id")), true, List.of("Alice", 42), 0, 21, null);
        assertEquals(
                "SELECT id, name FROM items WHERE (\"name\", \"id\") > ($1, $2) ORDER BY \"name\" ASC, \"id\" ASC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_withKeyset_withBaseWhere() {
        var q = buildQuery("SELECT id, name FROM items WHERE status = $1", "name", "id");
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name"), OrderKey.asc("id")), true, List.of("Alice", 42), 1, 21, null);
        assertEquals(
                "SELECT id, name FROM items WHERE status = $1 AND (\"name\", \"id\") > ($2, $3)"
                        + " ORDER BY \"name\" ASC, \"id\" ASC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_withKeyset_descending() {
        var q = buildQuery("SELECT id, name FROM items", "name");
        String sql = q.buildPaginatedSql(q.sql(), List.of(OrderKey.desc("name")), true, List.of("Alice"), 0, 21, null);
        assertEquals("SELECT id, name FROM items WHERE (\"name\") < ($1) ORDER BY \"name\" DESC LIMIT 21", sql);
    }

    // --- SQL composition: with query clause ---

    @Test
    void buildPaginatedSql_withQueryClause() {
        var q = buildQuery("SELECT id, name FROM items", "name");
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name")), false, List.of(), 0, 21, PgLockMode.FOR_UPDATE);
        assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC LIMIT 21 FOR UPDATE", sql);
    }

    @Test
    void buildPaginatedSql_withKeysetAndQueryClause() {
        var q = buildQuery("SELECT id, name FROM items WHERE status = $1", "name", "id");
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.asc("name"), OrderKey.asc("id")),
                true,
                List.of("Alice", 42),
                1,
                21,
                PgLockMode.FOR_UPDATE_SKIP_LOCKED);
        assertEquals(
                "SELECT id, name FROM items WHERE status = $1 AND (\"name\", \"id\") > ($2, $3)"
                        + " ORDER BY \"name\" ASC, \"id\" ASC LIMIT 21 FOR UPDATE SKIP LOCKED",
                sql);
    }

    // --- SQL composition: backward navigation (reversed direction) ---

    @Test
    void buildPaginatedSql_backward_reversesDirection() {
        var q = buildQuery("SELECT id, name FROM items", "name", "id");
        // backward: original direction ASC → effective direction DESC
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.desc("name"), OrderKey.desc("id")), true, List.of("Alice", 42), 0, 21, null);
        assertEquals(
                "SELECT id, name FROM items WHERE (\"name\", \"id\") < ($1, $2) ORDER BY \"name\" DESC, \"id\" DESC LIMIT 21",
                sql);
    }

    // --- SQL composition: multiple base params ---

    @Test
    void buildPaginatedSql_multipleBaseParams_keysetStartsAfter() {
        var q = buildQuery("SELECT id, name FROM items WHERE a = $1 AND b = $2 AND c = $3", "name", "id");
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name"), OrderKey.asc("id")), true, List.of("Alice", 42), 3, 21, null);
        assertEquals(
                "SELECT id, name FROM items WHERE a = $1 AND b = $2 AND c = $3"
                        + " AND (\"name\", \"id\") > ($4, $5) ORDER BY \"name\" ASC, \"id\" ASC LIMIT 21",
                sql);
    }

    // --- SQL composition: Tier B (mixed directions, no nulls) ---

    @Test
    void buildPaginatedSql_tierB_mixedDirections_noNulls() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name, priority FROM items")
                .orderBy(OrderKey.desc("priority"), OrderKey.asc("id"))
                .build();
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.desc("priority"), OrderKey.asc("id")), true, List.of(5, 42), 0, 21, null);
        assertEquals(
                "SELECT id, name, priority FROM items"
                        + " WHERE ((\"priority\" < $1) OR (\"priority\" = $1 AND \"id\" > $2))"
                        + " ORDER BY \"priority\" DESC, \"id\" ASC LIMIT 21",
                sql);
    }

    // --- SQL composition: Tier C (null-aware) ---

    @Test
    void buildPaginatedSql_tierC_nullsLast_asc_nonNullCursor() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.asc("name").nullsLast(), OrderKey.asc("id"))
                .build();
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.asc("name").nullsLast(), OrderKey.asc("id")),
                true,
                List.of("Alice", 42),
                0,
                21,
                null);
        // Tier C: null-aware — non-null cursor with NULLS_LAST ASC: (name > $1 OR name IS NULL)
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE (((\"name\" > $1 OR \"name\" IS NULL)) OR (\"name\" = $1 AND \"id\" > $2))"
                        + " ORDER BY \"name\" ASC NULLS LAST, \"id\" ASC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_tierC_nullsLast_asc_nullCursor_appendsFalse() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.asc("name").nullsLast(), OrderKey.asc("id"))
                .build();
        // Cursor at null name + id=42: nothing comes after null in NULLS_LAST ASC
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.asc("name").nullsLast(), OrderKey.asc("id")),
                true,
                asList(null, 42),
                0,
                21,
                null);
        // For NULLS_LAST ASC with null cursor: first column → FALSE
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE ((FALSE) OR (\"name\" IS NULL AND \"id\" > $1))"
                        + " ORDER BY \"name\" ASC NULLS LAST, \"id\" ASC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_tierC_nullsFirst_asc_nullCursor() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.asc("name").nullsFirst(), OrderKey.asc("id"))
                .build();
        // Cursor at null name + id=42: NULLS_FIRST ASC → non-null values come after null
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.asc("name").nullsFirst(), OrderKey.asc("id")),
                true,
                asList(null, 42),
                0,
                21,
                null);
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE ((\"name\" IS NOT NULL) OR (\"name\" IS NULL AND \"id\" > $1))"
                        + " ORDER BY \"name\" ASC NULLS FIRST, \"id\" ASC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_tierC_nullsFirstColumn_orderByIncludesNullsFirst() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.asc("name").nullsFirst(), OrderKey.asc("id"))
                .build();
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name").nullsFirst(), OrderKey.asc("id")), false, List.of(), 0, 21, null);
        assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC NULLS FIRST, \"id\" ASC LIMIT 21", sql);
    }

    @Test
    void buildPaginatedSql_tierC_nullsLastColumn_orderByIncludesNullsLast() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.asc("name").nullsLast(), OrderKey.asc("id"))
                .build();
        String sql = q.buildPaginatedSql(
                q.sql(), List.of(OrderKey.asc("name").nullsLast(), OrderKey.asc("id")), false, List.of(), 0, 21, null);
        assertEquals("SELECT id, name FROM items ORDER BY \"name\" ASC NULLS LAST, \"id\" ASC LIMIT 21", sql);
    }

    // --- SQL composition: Tier C DESC direction tests ---

    @Test
    void buildPaginatedSql_tierC_desc_nullsFirst_nonNullCursor() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.desc("name").nullsFirst(), OrderKey.desc("id"))
                .build();
        // DESC + NULLS_FIRST: nulls at beginning, non-null cursor → simple comparison (no IS NULL branch)
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.desc("name").nullsFirst(), OrderKey.desc("id")),
                true,
                List.of("Alice", 42),
                0,
                21,
                null);
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE (((\"name\" < $1)) OR (\"name\" = $1 AND \"id\" < $2))"
                        + " ORDER BY \"name\" DESC NULLS FIRST, \"id\" DESC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_tierC_desc_nullsLast_nonNullCursor() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.desc("name").nullsLast(), OrderKey.desc("id"))
                .build();
        // DESC + NULLS_LAST: nulls at end, non-null cursor → include IS NULL branch
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.desc("name").nullsLast(), OrderKey.desc("id")),
                true,
                List.of("Alice", 42),
                0,
                21,
                null);
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE (((\"name\" < $1 OR \"name\" IS NULL)) OR (\"name\" = $1 AND \"id\" < $2))"
                        + " ORDER BY \"name\" DESC NULLS LAST, \"id\" DESC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_tierC_desc_nullsFirst_nullCursor() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.desc("name").nullsFirst(), OrderKey.desc("id"))
                .build();
        // DESC + NULLS_FIRST: nulls at beginning, null cursor → non-null values come after
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.desc("name").nullsFirst(), OrderKey.desc("id")),
                true,
                asList(null, 42),
                0,
                21,
                null);
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE ((\"name\" IS NOT NULL) OR (\"name\" IS NULL AND \"id\" < $1))"
                        + " ORDER BY \"name\" DESC NULLS FIRST, \"id\" DESC LIMIT 21",
                sql);
    }

    @Test
    void buildPaginatedSql_tierC_desc_nullsLast_nullCursor() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, name FROM items")
                .orderBy(OrderKey.desc("name").nullsLast(), OrderKey.desc("id"))
                .build();
        // DESC + NULLS_LAST: nulls at end, null cursor → FALSE (nothing after null at end)
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(OrderKey.desc("name").nullsLast(), OrderKey.desc("id")),
                true,
                asList(null, 42),
                0,
                21,
                null);
        assertEquals(
                "SELECT id, name FROM items"
                        + " WHERE ((FALSE) OR (\"name\" IS NULL AND \"id\" < $1))"
                        + " ORDER BY \"name\" DESC NULLS LAST, \"id\" DESC LIMIT 21",
                sql);
    }

    // --- SQL composition: Tier C param index regression (null in middle of composite keys) ---

    @Test
    void buildPaginatedSql_tierC_nullInMiddle_paramIndicesAreContiguous() {
        var q = PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql("SELECT id, priority, category FROM items")
                .orderBy(
                        OrderKey.desc("priority").nullsLast(),
                        OrderKey.asc("category").nullsFirst(),
                        OrderKey.asc("id"))
                .build();
        // Keyset: [5, null, "abc"] — middle value is null
        String sql = q.buildPaginatedSql(
                q.sql(),
                List.of(
                        OrderKey.desc("priority").nullsLast(),
                        OrderKey.asc("category").nullsFirst(),
                        OrderKey.asc("id")),
                true,
                asList(5, null, "abc"),
                0,
                21,
                null);
        // priority non-null → $1, category null → no placeholder, id non-null → $2
        // Verify contiguous placeholder numbering (no $3, only $1 and $2)
        assertTrue(sql.contains("$1"), "should contain $1 for priority");
        assertTrue(sql.contains("$2"), "should contain $2 for id");
        assertFalse(sql.contains("$3"), "should NOT contain $3 — only 2 non-null keyset values");
    }

    // --- SQL validation with complex queries (SqlScanner-aware) ---

    @Nested
    @DisplayName("SQL validation with complex queries")
    class ComplexSqlValidation {

        @Test
        @DisplayName("accepts CTE with ORDER BY inside the CTE")
        void build_acceptsCteWithOrderBy() {
            String sql = "WITH ranked AS (SELECT *, ROW_NUMBER() OVER (ORDER BY name) AS rn FROM items)"
                    + " SELECT * FROM ranked WHERE rn > 0";
            assertDoesNotThrow(() -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("accepts subquery with ORDER BY inside the subquery")
        void build_acceptsSubqueryWithOrderBy() {
            String sql = "SELECT * FROM (SELECT * FROM items ORDER BY name LIMIT 100) sub WHERE sub.id IS NOT NULL";
            // ORDER BY and LIMIT are inside the subquery, so they should be accepted
            assertDoesNotThrow(() -> buildQuery(sql, "id"));
        }

        @Test
        @DisplayName("accepts window function with ORDER BY inside the OVER clause")
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
        @DisplayName("still rejects top-level ORDER BY even in presence of subquery")
        void build_rejectsTopLevelOrderByWithSubquery() {
            String sql = "SELECT * FROM (SELECT id FROM items) sub ORDER BY id";
            assertThrows(IllegalArgumentException.class, () -> buildQuery(sql, "id"));
        }
    }

    // --- Helper ---

    private PgPagedQuery<String> buildQuery(String sql, String... columns) {
        return PgPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .sql(sql)
                .orderBy(columns)
                .build();
    }

    /** Creates a mutable list that allows null elements (List.of does not). */
    @SafeVarargs
    private static <T> List<T> asList(T... elements) {
        return java.util.Arrays.asList(elements);
    }
}
