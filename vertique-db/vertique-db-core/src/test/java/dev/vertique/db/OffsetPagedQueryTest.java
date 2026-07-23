// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.NullHandling;
import dev.vertique.db.query.OffsetPagedQuery;
import dev.vertique.db.query.OrderDirection;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.QueryClause;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies {@link OffsetPagedQuery} builder validation, defaults, accessors, and count SQL
 * construction using a concrete {@link StubOffsetPagedQuery} inner class.
 */
@ExtendWith(MockitoExtension.class)
class OffsetPagedQueryTest {

    @Mock
    private SqlClient client;

    @Mock
    private DbExceptionMapper exceptionMapper;

    // --- Builder null checks ---

    @Nested
    @DisplayName("Builder null checks")
    class BuilderNullChecks {

        @Test
        @DisplayName("build() fails when sql is null")
        void build_failsWhenSqlNull() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .orderBy("id");
            assertThrows(NullPointerException.class, b::build);
        }

        @Test
        @DisplayName("build() fails when client is null")
        void build_failsWhenClientNull() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .orderBy("id");
            assertThrows(NullPointerException.class, b::build);
        }

        @Test
        @DisplayName("build() fails when exceptionMapper is null")
        void build_failsWhenFailureMapperNull() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .mapping(row -> "x")
                    .orderBy("id");
            assertThrows(NullPointerException.class, b::build);
        }

        @Test
        @DisplayName("build() fails when mapper is null")
        void build_failsWhenMapperNull() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .orderBy("id");
            assertThrows(NullPointerException.class, b::build);
        }

        @Test
        @DisplayName("build() fails when orderKeys is null (orderBy never called)")
        void build_failsWhenOrderKeysNull() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x");
            assertThrows(NullPointerException.class, b::build);
        }
    }

    // --- Builder defaults ---

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("default page size is 20")
        void defaultPageSize_is20() {
            var q = buildStubQuery("SELECT 1");
            assertEquals(20, q.defaultPageSize());
        }

        @Test
        @DisplayName("default order direction is ASC")
        void defaultOrderDirection_isAsc() {
            var q = buildStubQuery("SELECT 1");
            assertEquals(OrderDirection.ASC, q.orderKeys().get(0).direction());
        }

        @Test
        @DisplayName("default null handling is DISALLOW")
        void defaultNullHandling_isDisallow() {
            var q = buildStubQuery("SELECT 1");
            assertEquals(NullHandling.DISALLOW, q.orderKeys().get(0).nullHandling());
        }
    }

    // --- Builder custom values ---

    @Nested
    @DisplayName("Builder custom values")
    class BuilderCustomValues {

        @Test
        @DisplayName("pageSize(50) sets default page size to 50")
        void pageSize_setsCustomDefault() {
            var q = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .orderBy("id")
                    .pageSize(50)
                    .build();
            assertEquals(50, q.defaultPageSize());
        }

        @Test
        @DisplayName("orderBy with DESC sets descending direction")
        void orderBy_desc_setsDescDirection() {
            var q = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .orderBy(OrderDirection.DESC, "id")
                    .build();
            assertEquals(OrderDirection.DESC, q.orderKeys().get(0).direction());
        }

        @Test
        @DisplayName("orderBy with OrderKey objects sets keys correctly")
        void orderBy_orderKey_setsKeys() {
            var q = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .orderBy(OrderKey.asc("created_at"), OrderKey.desc("id"))
                    .build();
            List<OrderKey> keys = q.orderKeys();
            assertEquals(2, keys.size());
            assertEquals("created_at", keys.get(0).column());
            assertEquals(OrderDirection.ASC, keys.get(0).direction());
            assertEquals("id", keys.get(1).column());
            assertEquals(OrderDirection.DESC, keys.get(1).direction());
        }

        @Test
        @DisplayName("orderBy with NullHandling.NULLS_LAST sets null handling correctly")
        void orderBy_withNullHandling_setsNullHandling() {
            var q = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x")
                    .orderBy(OrderDirection.ASC, NullHandling.NULLS_LAST, "name", "id")
                    .build();
            assertEquals(NullHandling.NULLS_LAST, q.orderKeys().get(0).nullHandling());
            assertEquals(NullHandling.NULLS_LAST, q.orderKeys().get(1).nullHandling());
        }
    }

    // --- Column validation ---

    @Nested
    @DisplayName("Column validation")
    class ColumnValidation {

        @Test
        @DisplayName("orderBy rejects empty columns array")
        void orderBy_rejectsEmptyColumns() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x");
            assertThrows(IllegalArgumentException.class, () -> b.orderBy(OrderDirection.ASC, new String[0]));
        }

        @Test
        @DisplayName("orderBy rejects duplicate column names")
        void orderBy_rejectsDuplicateColumns() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x");
            assertThrows(IllegalArgumentException.class, () -> b.orderBy("id", "id"));
        }

        @Test
        @DisplayName("orderBy rejects SQL injection in column name")
        void orderBy_rejectsSqlInjection() {
            var b = StubOffsetPagedQuery.<String>builder()
                    .on(client)
                    .sql("SELECT 1")
                    .exceptionMapper(exceptionMapper)
                    .mapping(row -> "x");
            assertThrows(Exception.class, () -> b.orderBy("id; DROP TABLE items--"));
        }
    }

    // --- Accessors ---

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("orderKeys() returns an unmodifiable list")
        void orderKeys_returnsUnmodifiableList() {
            var q = buildStubQuery("SELECT 1");
            List<OrderKey> keys = q.orderKeys();
            assertEquals(1, keys.size());
            assertEquals("id", keys.get(0).column());
            assertThrows(UnsupportedOperationException.class, () -> keys.add(OrderKey.asc("other")));
        }

        @Test
        @DisplayName("sql() returns the configured SQL")
        void sql_returnsSql() {
            var q = buildStubQuery("SELECT * FROM items");
            assertEquals("SELECT * FROM items", q.sql());
        }

        @Test
        @DisplayName("client() returns the configured client")
        void client_returnsClient() {
            var q = buildStubQuery("SELECT 1");
            assertSame(client, q.client());
        }

        @Test
        @DisplayName("defaultPageSize() returns the configured page size")
        void defaultPageSize_returnsPageSize() {
            var q = buildStubQuery("SELECT 1");
            assertEquals(20, q.defaultPageSize());
        }
    }

    // --- Count SQL ---

    @Nested
    @DisplayName("Count SQL")
    class CountSql {

        @Test
        @DisplayName("buildCountSql() wraps base SQL in COUNT(*) subquery")
        void buildCountSql_wrapsBaseSql() {
            var q = buildStubQuery("SELECT id, name FROM items");
            assertEquals("SELECT COUNT(*) FROM (SELECT id, name FROM items) _cnt", q.publicBuildCountSql());
        }

        @Test
        @DisplayName("buildCountSql() works with WHERE clause")
        void buildCountSql_withWhere() {
            var q = buildStubQuery("SELECT id FROM items WHERE status = $1");
            assertEquals("SELECT COUNT(*) FROM (SELECT id FROM items WHERE status = $1) _cnt", q.publicBuildCountSql());
        }
    }

    // --- Helper ---

    private StubOffsetPagedQuery<String> buildStubQuery(String sql) {
        return StubOffsetPagedQuery.<String>builder()
                .on(client)
                .sql(sql)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id")
                .build();
    }

    // --- Concrete stub for testing the abstract OffsetPagedQuery class ---

    /**
     * Minimal concrete subclass of {@link OffsetPagedQuery} used for unit testing the abstract
     * class's builder validation, defaults, and accessor methods without requiring a real database.
     *
     * @param <T> the domain type
     */
    static final class StubOffsetPagedQuery<T> extends OffsetPagedQuery<T> {

        /**
         * Creates a stub instance from the given builder.
         *
         * @param b the builder
         */
        StubOffsetPagedQuery(Builder<T> b) {
            super(b);
        }

        /**
         * Creates a new stub builder.
         *
         * @param <T> the domain type
         * @return a new builder
         */
        static <T> Builder<T> builder() {
            return new Builder<>();
        }

        /** {@inheritDoc} */
        @Override
        protected String buildDataSql(
                String baseSql, List<OrderKey> orderKeys, long offset, int limit, QueryClause queryClause) {
            // Stub: return baseSql unchanged for testing
            return baseSql;
        }

        /**
         * Exposes the package-private {@link #buildCountSql()} for testing.
         *
         * @return the count SQL string
         */
        public String publicBuildCountSql() {
            return buildCountSql();
        }

        /**
         * Builder for {@link StubOffsetPagedQuery}.
         *
         * @param <T> the domain type
         */
        static final class Builder<T> extends OffsetPagedQuery.Builder<T, Builder<T>> {

            /** {@inheritDoc} */
            @Override
            protected Builder<T> self() {
                return this;
            }

            /** {@inheritDoc} */
            @Override
            public StubOffsetPagedQuery<T> build() {
                return new StubOffsetPagedQuery<>(this);
            }
        }
    }
}
