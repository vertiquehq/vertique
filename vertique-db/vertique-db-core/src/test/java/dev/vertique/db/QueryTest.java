// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.IncorrectResultSizeDataAccessException;
import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.query.Query;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies Query builder preconditions, cardinality enforcement, count column validation, and
 * stream() precondition checks.
 */
@ExtendWith(MockitoExtension.class)
class QueryTest {

    @Mock
    private SqlClient client;

    @Mock
    private DbExceptionMapper exceptionMapper;

    @Mock
    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    @Mock
    @SuppressWarnings("rawtypes")
    private RowSet rowSet;

    @Mock
    private Row row;

    private StubQuery.Builder<String> builder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Lenient pass-through: preserve exception type through recover() chains.
        // Lenient because not all tests reach the recover() branch.
        lenient()
                .when(exceptionMapper.translate(any(Throwable.class), anyString()))
                .thenAnswer(inv -> {
                    Throwable t = inv.getArgument(0);
                    if (t instanceof DataAccessException dae) {
                        return dae;
                    }
                    return new DataAccessException(inv.getArgument(1), t);
                });
        builder = StubQuery.<String>builder().on(client).exceptionMapper(exceptionMapper);
    }

    // -- Constructor null checks --

    @Test
    void build_shouldFailIfSqlNull() {
        var b = StubQuery.<String>builder().on(client).exceptionMapper(exceptionMapper);
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void build_shouldFailIfClientNull() {
        var b = StubQuery.<String>builder().sql("SELECT 1").exceptionMapper(exceptionMapper);
        assertThrows(NullPointerException.class, b::build);
    }

    @Test
    void build_shouldFailIfFailureMapperNull() {
        var b = StubQuery.<String>builder().on(client).sql("SELECT 1");
        assertThrows(NullPointerException.class, b::build);
    }

    // -- buildSql --

    @Test
    void buildSql_returnsBaseSqlWhenNoClause() {
        var q = StubQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql("SELECT 1")
                .build();
        assertEquals("SELECT 1", q.buildSql());
    }

    @Test
    void buildSql_appendsQueryClause() {
        var q = StubQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql("SELECT 1")
                .queryClause(() -> "FOR UPDATE")
                .build();
        assertEquals("SELECT 1 FOR UPDATE", q.buildSql());
    }

    // -- requireMapper guards --

    @Test
    void one_throwsWithoutMapper() {
        assertThrows(IllegalStateException.class, () -> builder.sql("SELECT 1").one());
    }

    @Test
    void list_throwsWithoutMapper() {
        assertThrows(IllegalStateException.class, () -> builder.sql("SELECT 1").list());
    }

    @Test
    void returning_throwsWithoutMapper() {
        assertThrows(IllegalStateException.class, () -> builder.sql("SELECT 1").returning());
    }

    @Test
    void returningOptional_throwsWithoutMapper() {
        assertThrows(IllegalStateException.class, () -> builder.sql("SELECT 1").returningOptional());
    }

    // -- batch + queryClause guard --

    @Test
    void execute_rejectsBatchWithQueryClause() {
        Future<Integer> result = builder.sql("INSERT INTO t VALUES ($1)")
                .queryClause(() -> "SOME CLAUSE")
                .batch(List.of(Tuple.of("a")))
                .execute();

        assertTrue(result.failed());
        assertInstanceOf(InvalidDataAccessUsageException.class, result.cause());
    }

    // -- Cardinality checks --

    @Nested
    @DisplayName("one() cardinality enforcement")
    class OneCardinalityTests {

        @Test
        @DisplayName("one() fails with IncorrectResultSizeDataAccessException when multiple rows returned")
        @SuppressWarnings("unchecked")
        void one_failsWhenMultipleRowsReturned() {
            RowSet<String> mockRows = mock(RowSet.class);
            RowIterator<String> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(true, true, false);
            when(mockIterator.next()).thenReturn("first", "second");

            PreparedQuery<RowSet<String>> mappedQuery = mock(PreparedQuery.class);
            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.mapping(any())).thenReturn(mappedQuery);
            when(mappedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<java.util.Optional<String>> result = builder.sql("SELECT name FROM t")
                    .mapping(r -> r.getString(0))
                    .one();

            assertTrue(result.failed());
            assertInstanceOf(IncorrectResultSizeDataAccessException.class, result.cause());
            IncorrectResultSizeDataAccessException ex = (IncorrectResultSizeDataAccessException) result.cause();
            assertEquals(1, ex.expectedSize());
        }

        @Test
        @DisplayName("one() succeeds with Optional.empty() when no rows returned")
        @SuppressWarnings("unchecked")
        void one_succeedsWithEmptyWhenNoRows() {
            RowSet<String> mockRows = mock(RowSet.class);
            RowIterator<String> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(false);

            PreparedQuery<RowSet<String>> mappedQuery = mock(PreparedQuery.class);
            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.mapping(any())).thenReturn(mappedQuery);
            when(mappedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<java.util.Optional<String>> result = builder.sql("SELECT name FROM t")
                    .mapping(r -> r.getString(0))
                    .one();

            assertTrue(result.succeeded());
            assertTrue(result.result().isEmpty());
        }

        @Test
        @DisplayName("one() succeeds with value when exactly one row returned")
        @SuppressWarnings("unchecked")
        void one_succeedsWithValueWhenOneRow() {
            RowSet<String> mockRows = mock(RowSet.class);
            RowIterator<String> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(true, false);
            when(mockIterator.next()).thenReturn("hello");

            PreparedQuery<RowSet<String>> mappedQuery = mock(PreparedQuery.class);
            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.mapping(any())).thenReturn(mappedQuery);
            when(mappedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<java.util.Optional<String>> result = builder.sql("SELECT name FROM t")
                    .mapping(r -> r.getString(0))
                    .one();

            assertTrue(result.succeeded());
            assertTrue(result.result().isPresent());
            assertEquals("hello", result.result().get());
        }
    }

    @Nested
    @DisplayName("returningOptional() cardinality enforcement")
    class ReturningOptionalCardinalityTests {

        @Test
        @DisplayName(
                "returningOptional() fails with IncorrectResultSizeDataAccessException when multiple rows returned")
        @SuppressWarnings("unchecked")
        void returningOptional_failsWhenMultipleRowsReturned() {
            RowSet<String> mockRows = mock(RowSet.class);
            RowIterator<String> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(true, true, false);
            when(mockIterator.next()).thenReturn("first", "second");

            PreparedQuery<RowSet<String>> mappedQuery = mock(PreparedQuery.class);
            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.mapping(any())).thenReturn(mappedQuery);
            when(mappedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<java.util.Optional<String>> result = builder.sql("INSERT INTO t VALUES ($1) RETURNING name")
                    .mapping(r -> r.getString(0))
                    .returningOptional();

            assertTrue(result.failed());
            assertInstanceOf(IncorrectResultSizeDataAccessException.class, result.cause());
        }
    }

    @Nested
    @DisplayName("returning() cardinality enforcement")
    class ReturningCardinalityTests {

        @Test
        @DisplayName("returning() fails with IncorrectResultSizeDataAccessException when multiple rows returned")
        @SuppressWarnings("unchecked")
        void returning_failsWhenMultipleRowsReturned() {
            RowSet<String> mockRows = mock(RowSet.class);
            RowIterator<String> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(true, true, false);
            when(mockIterator.next()).thenReturn("first", "second");

            PreparedQuery<RowSet<String>> mappedQuery = mock(PreparedQuery.class);
            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.mapping(any())).thenReturn(mappedQuery);
            when(mappedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<String> result = builder.sql("INSERT INTO t VALUES ($1) RETURNING name")
                    .mapping(r -> r.getString(0))
                    .returning();

            assertTrue(result.failed());
            assertInstanceOf(IncorrectResultSizeDataAccessException.class, result.cause());
            IncorrectResultSizeDataAccessException ex = (IncorrectResultSizeDataAccessException) result.cause();
            assertEquals(1, ex.expectedSize());
        }

        @Test
        @DisplayName("returning() fails with DataAccessException when no rows returned")
        @SuppressWarnings("unchecked")
        void returning_failsWhenNoRowsReturned() {
            RowSet<String> mockRows = mock(RowSet.class);
            RowIterator<String> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(false);

            PreparedQuery<RowSet<String>> mappedQuery = mock(PreparedQuery.class);
            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.mapping(any())).thenReturn(mappedQuery);
            when(mappedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<String> result = builder.sql("INSERT INTO t VALUES ($1) RETURNING name")
                    .mapping(r -> r.getString(0))
                    .returning();

            assertTrue(result.failed());
            assertInstanceOf(dev.vertique.db.exception.DataAccessException.class, result.cause());
            assertFalse(result.cause() instanceof IncorrectResultSizeDataAccessException);
        }
    }

    @Nested
    @DisplayName("count() column validation")
    class CountColumnValidationTests {

        @Test
        @DisplayName("count() fails with InvalidDataAccessUsageException when result has multiple columns")
        @SuppressWarnings("unchecked")
        void count_failsWhenMultipleColumns() {
            RowSet<Row> mockRows = mock(RowSet.class);
            RowIterator<Row> mockIterator = mock(RowIterator.class);
            Row mockRow = mock(Row.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(true, false);
            when(mockIterator.next()).thenReturn(mockRow);
            when(mockRow.size()).thenReturn(3);

            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<Long> result = builder.sql("SELECT id, name, status FROM t").count();

            assertTrue(result.failed());
            assertInstanceOf(InvalidDataAccessUsageException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("count()"));
        }

        @Test
        @DisplayName("count() returns 0L when no rows returned")
        @SuppressWarnings("unchecked")
        void count_returnsZeroWhenNoRows() {
            RowSet<Row> mockRows = mock(RowSet.class);
            RowIterator<Row> mockIterator = mock(RowIterator.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(false);

            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<Long> result = builder.sql("SELECT COUNT(*) FROM t").count();

            assertTrue(result.succeeded());
            assertEquals(0L, result.result());
        }

        @Test
        @DisplayName("count() returns the value from a single-column row")
        @SuppressWarnings("unchecked")
        void count_returnsValueFromSingleColumnRow() {
            RowSet<Row> mockRows = mock(RowSet.class);
            RowIterator<Row> mockIterator = mock(RowIterator.class);
            Row mockRow = mock(Row.class);
            when(mockRows.iterator()).thenReturn(mockIterator);
            when(mockIterator.hasNext()).thenReturn(true, false);
            when(mockIterator.next()).thenReturn(mockRow);
            when(mockRow.size()).thenReturn(1);
            when(mockRow.getLong(0)).thenReturn(42L);

            when(client.preparedQuery(anyString())).thenReturn((PreparedQuery) preparedQuery);
            when(preparedQuery.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mockRows));

            Future<Long> result = builder.sql("SELECT COUNT(*) FROM t").count();

            assertTrue(result.succeeded());
            assertEquals(42L, result.result());
        }
    }

    @Nested
    @DisplayName("stream() preconditions")
    class StreamPreconditionTests {

        @Test
        @DisplayName("stream() throws IllegalStateException without mapper")
        void stream_throwsWithoutMapper() {
            assertThrows(IllegalStateException.class, () -> builder.sql("SELECT id FROM t").stream(100));
        }

        @Test
        @DisplayName("stream() with Pool returns failed Future with InvalidDataAccessUsageException")
        void stream_failsWithPool() {
            Pool pool = mock(Pool.class);
            Future<?> result = StubQuery.<String>builder()
                    .on(pool)
                    .exceptionMapper(exceptionMapper)
                    .sql("SELECT id FROM t")
                    .mapping(r -> r.getString(0))
                    .stream(100);

            assertTrue(result.failed());
            assertInstanceOf(InvalidDataAccessUsageException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("stream()"));
        }

        @Test
        @DisplayName("stream() with fetchSize == 0 returns failed Future with IllegalArgumentException")
        void stream_failsWithZeroFetchSize() {
            SqlConnection conn = mock(SqlConnection.class);
            Future<?> result = StubQuery.<String>builder()
                    .on(conn)
                    .exceptionMapper(exceptionMapper)
                    .sql("SELECT id FROM t")
                    .mapping(r -> r.getString(0))
                    .stream(0);

            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("fetchSize"));
        }

        @Test
        @DisplayName("stream() with negative fetchSize returns failed Future with IllegalArgumentException")
        void stream_failsWithNegativeFetchSize() {
            SqlConnection conn = mock(SqlConnection.class);
            Future<?> result = StubQuery.<String>builder()
                    .on(conn)
                    .exceptionMapper(exceptionMapper)
                    .sql("SELECT id FROM t")
                    .mapping(r -> r.getString(0))
                    .stream(-5);

            assertTrue(result.failed());
            assertInstanceOf(IllegalArgumentException.class, result.cause());
        }
    }

    // -- Concrete stub for testing the abstract Query class --

    static final class StubQuery<T> extends Query<T> {

        StubQuery(Builder<T> b) {
            super(b);
        }

        static <T> Builder<T> builder() {
            return new Builder<>();
        }

        static final class Builder<T> extends Query.Builder<T, Builder<T>> {

            @Override
            protected Builder<T> self() {
                return this;
            }

            @Override
            public StubQuery<T> build() {
                return new StubQuery<>(this);
            }
        }
    }
}
