// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.query.OrderDirection;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PagedQuery;
import dev.vertique.db.query.QueryClause;
import io.vertx.core.Future;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
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

    // -- Tiebreaker warning --

    @Test
    @SuppressWarnings("unchecked")
    void page_withoutUniqueKey_warnsAboutTheTiebreakerOnlyOnce() throws ReflectiveOperationException {
        // The warning is once per process; rearm it so the result does not depend on test order
        var warned = PagedQuery.class.getDeclaredField("TIEBREAKER_WARNING_EMITTED");
        warned.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) warned.get(null)).set(false);
        PreparedQuery<RowSet<Row>> prepared = Mockito.mock(PreparedQuery.class);
        Mockito.when(client.preparedQuery(Mockito.anyString())).thenReturn(prepared);
        Mockito.when(prepared.execute(Mockito.any(Tuple.class))).thenReturn(Future.failedFuture("no database"));
        var builder = StubPagedQuery.<String>builder()
                .on(client)
                .sql("SELECT 1")
                .exceptionMapper(exceptionMapper)
                .mapping(row -> "x")
                .orderBy("id");

        ch.qos.logback.classic.Logger queryLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PagedQuery.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.setContext(queryLogger.getLoggerContext());
        appender.start();
        queryLogger.addAppender(appender);
        try {
            builder.page();
            builder.page();
            builder.page();
        } finally {
            queryLogger.detachAppender(appender);
            appender.stop();
        }

        long warnings = appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("unique tiebreaker"))
                .count();
        assertEquals(1, warnings, "the missing-tiebreaker warning is documented as one-time");
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
