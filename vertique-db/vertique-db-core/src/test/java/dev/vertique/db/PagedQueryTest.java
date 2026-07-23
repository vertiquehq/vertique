// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.OrderDirection;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PagedQuery;
import dev.vertique.db.query.QueryClause;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PagedQueryTest {

    @Mock
    private SqlClient client;

    @Mock
    private DbExceptionMapper exceptionMapper;

    // -- Constructor validation --

    @Test
    void build_shouldFailIfSqlNull() {
        var b = StubPagedQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id");
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void build_shouldFailIfClientNull() {
        var b = StubPagedQuery.<String>builder()
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id");
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void build_shouldFailIfFailureMapperNull() {
        var b = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .mapping(row -> "x")
                .orderBy("id");
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void build_shouldFailIfMapperNull() {
        var b = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .orderBy("id");
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void build_shouldFailIfOrderKeysNull() {
        var b = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x");
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void orderBy_shouldFailIfColumnsEmpty() {
        var b = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x");
        assertThrows(IllegalArgumentException.class, () -> b.orderBy(OrderDirection.ASC, new String[0]));
    }

    // -- Builder defaults --

    @Test
    void build_defaultPageSize_is20() {
        var q = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id")
                .build();
        assertEquals(20, q.defaultPageSize());
    }

    @Test
    void build_orderBy_defaultDirectionIsAsc() {
        var q = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id")
                .build();
        assertEquals(OrderDirection.ASC, q.orderKeys().get(0).direction());
    }

    @Test
    void build_customPageSize() {
        var q = StubPagedQuery.<String>builder()
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
    void build_orderByDesc_setsDescDirection() {
        var q = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy(OrderDirection.DESC, "id")
                .build();
        assertEquals(OrderDirection.DESC, q.orderKeys().get(0).direction());
    }

    @Test
    void build_orderByOrderKey_setsKeys() {
        var q = StubPagedQuery.<String>builder()
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

    // -- hasWhereClause detection --

    @Test
    void hasWhereClause_detectsWhere() {
        var q = buildStubQuery("SELECT * FROM items WHERE id = $1");
        assertTrue(q.publicHasWhereClause(q.sql()));
    }

    @Test
    void hasWhereClause_detectsLowercase() {
        var q = buildStubQuery("SELECT * FROM items where id = $1");
        assertTrue(q.publicHasWhereClause(q.sql()));
    }

    @Test
    void hasWhereClause_falseWithoutWhere() {
        var q = buildStubQuery("SELECT * FROM items");
        assertFalse(q.publicHasWhereClause(q.sql()));
    }

    // -- Accessors --

    @Test
    void orderKeys_returnsUnmodifiableList() {
        var q = buildStubQuery("SELECT 1");
        List<OrderKey> keys = q.orderKeys();
        assertEquals(1, keys.size());
        assertEquals("id", keys.get(0).column());
        assertThrows(UnsupportedOperationException.class, () -> keys.add(OrderKey.asc("other")));
    }

    // -- Concrete stub for testing the abstract PagedQuery class --

    private StubPagedQuery<String> buildStubQuery(String sql) {
        return StubPagedQuery.<String>builder()
                .on(client)
                .sql(sql)
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id")
                .build();
    }

    static final class StubPagedQuery<T> extends PagedQuery<T> {

        StubPagedQuery(Builder<T> b) {
            super(b);
        }

        static <T> Builder<T> builder() {
            return new Builder<>();
        }

        @Override
        protected String buildPaginatedSql(
                String baseSql,
                java.util.List<OrderKey> orderKeys,
                boolean hasKeysetValues,
                java.util.List<Object> keysetValues,
                int baseParamCount,
                int fetchCount,
                QueryClause queryClause) {
            // Stub: just return baseSql for testing
            return baseSql;
        }

        public boolean publicHasWhereClause(String sql) {
            return hasWhereClause(sql);
        }

        static final class Builder<T> extends PagedQuery.Builder<T, Builder<T>> {

            @Override
            protected Builder<T> self() {
                return this;
            }

            @Override
            public StubPagedQuery<T> build() {
                return new StubPagedQuery<>(this);
            }
        }
    }
}
