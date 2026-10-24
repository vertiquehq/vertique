// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.sqlclient.PrepareOptions;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.spi.DatabaseMetadata;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the guarded connection handed to deadline-bounded transaction work: it delegates the
 * transaction and metadata accessors, refuses every statement variant that executes after the
 * connection is marked expired (without reaching the pooled connection), and always refuses the
 * calls that would bypass the execute-time check or take over the pooled connection's lifecycle.
 */
class DeadlineGuardedConnectionTest {

    private static final String SQL = "SELECT 1";
    private static final Tuple TUPLE = Tuple.of(1);
    private static final Function<Row, String> MAPPER = row -> "mapped";
    private static final Collector<Row, ?, List<String>> COLLECTOR =
            Collectors.mapping(row -> "collected", Collectors.toList());

    /** A statement variant: how to build it on a connection, and which interface methods it exercises. */
    private record Variant(String name, Set<String> covers, Function<SqlConnection, Supplier<Future<?>>> build) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A mocked pooled connection whose query objects succeed, with a count of executed statements. */
    private static final class Fixture {
        final SqlConnection delegate = mock(SqlConnection.class);
        final List<Object> queries;

        @SuppressWarnings("unchecked")
        Fixture() {
            Query<RowSet<Row>> query = mock(Query.class);
            Query<RowSet<String>> queryMapped = mock(Query.class);
            Query<Object> queryCollected = mock(Query.class);
            PreparedQuery<RowSet<Row>> prepared = mock(PreparedQuery.class);
            PreparedQuery<RowSet<String>> preparedMapped = mock(PreparedQuery.class);
            PreparedQuery<Object> preparedCollected = mock(PreparedQuery.class);
            queries = List.of(query, queryMapped, queryCollected, prepared, preparedMapped, preparedCollected);

            doReturn(query).when(delegate).query(SQL);
            doReturn(prepared).when(delegate).preparedQuery(SQL);
            doReturn(queryMapped).when(query).mapping(any());
            doReturn(queryCollected).when(query).collecting(any());
            doReturn(preparedMapped).when(prepared).mapping(any());
            doReturn(preparedCollected).when(prepared).collecting(any());
            for (Object q : queries) {
                doReturn(Future.succeededFuture("delegate")).when((Query<?>) q).execute();
            }
            for (Object q : List.of(prepared, preparedMapped, preparedCollected)) {
                PreparedQuery<?> p = (PreparedQuery<?>) q;
                doReturn(Future.succeededFuture("delegate")).when(p).execute(any(Tuple.class));
                doReturn(Future.succeededFuture("delegate")).when(p).executeBatch(any());
            }
        }

        long executeCalls() {
            return queries.stream()
                    .flatMap(q -> mockingDetails(q).getInvocations().stream())
                    .map(i -> i.getMethod().getName())
                    .filter(n -> n.equals("execute") || n.equals("executeBatch"))
                    .count();
        }
    }

    static Stream<Arguments> variants() {
        return Stream.of(
                        new Variant("query().execute()", Set.of("Query#execute()"), c -> {
                            Query<?> q = c.query(SQL);
                            return q::execute;
                        }),
                        new Variant(
                                "query().mapping().execute()",
                                Set.of("Query#mapping(Function)", "Query#execute()"),
                                c -> {
                                    Query<?> q = c.query(SQL).mapping(MAPPER);
                                    return q::execute;
                                }),
                        new Variant(
                                "query().collecting().execute()",
                                Set.of("Query#collecting(Collector)", "Query#execute()"),
                                c -> {
                                    Query<?> q = c.query(SQL).collecting(COLLECTOR);
                                    return q::execute;
                                }),
                        new Variant("preparedQuery().execute()", Set.of("PreparedQuery#execute()"), c -> {
                            PreparedQuery<?> q = c.preparedQuery(SQL);
                            return q::execute;
                        }),
                        new Variant("preparedQuery().execute(tuple)", Set.of("PreparedQuery#execute(Tuple)"), c -> {
                            PreparedQuery<?> q = c.preparedQuery(SQL);
                            return () -> q.execute(TUPLE);
                        }),
                        new Variant(
                                "preparedQuery().executeBatch(tuples)",
                                Set.of("PreparedQuery#executeBatch(List)"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL);
                                    return () -> q.executeBatch(List.of(TUPLE));
                                }),
                        new Variant(
                                "preparedQuery().mapping().execute()",
                                Set.of("PreparedQuery#mapping(Function)", "PreparedQuery#execute()"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL).mapping(MAPPER);
                                    return q::execute;
                                }),
                        new Variant(
                                "preparedQuery().mapping().execute(tuple)",
                                Set.of("PreparedQuery#mapping(Function)", "PreparedQuery#execute(Tuple)"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL).mapping(MAPPER);
                                    return () -> q.execute(TUPLE);
                                }),
                        new Variant(
                                "preparedQuery().mapping().executeBatch(tuples)",
                                Set.of("PreparedQuery#mapping(Function)", "PreparedQuery#executeBatch(List)"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL).mapping(MAPPER);
                                    return () -> q.executeBatch(List.of(TUPLE));
                                }),
                        new Variant(
                                "preparedQuery().collecting().execute()",
                                Set.of("PreparedQuery#collecting(Collector)", "PreparedQuery#execute()"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL).collecting(COLLECTOR);
                                    return q::execute;
                                }),
                        new Variant(
                                "preparedQuery().collecting().execute(tuple)",
                                Set.of("PreparedQuery#collecting(Collector)", "PreparedQuery#execute(Tuple)"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL).collecting(COLLECTOR);
                                    return () -> q.execute(TUPLE);
                                }),
                        new Variant(
                                "preparedQuery().collecting().executeBatch(tuples)",
                                Set.of("PreparedQuery#collecting(Collector)", "PreparedQuery#executeBatch(List)"),
                                c -> {
                                    PreparedQuery<?> q = c.preparedQuery(SQL).collecting(COLLECTOR);
                                    return () -> q.executeBatch(List.of(TUPLE));
                                }))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    @DisplayName("delegates the transaction and refuses every statement variant after expiry")
    void delegatesTheTransactionAndRefusesEveryStatementVariantAfterExpiry(Variant variant) {
        // Given a guarded connection over a pooled connection, and a statement built before expiry
        Fixture f = new Fixture();
        Transaction transaction = mock(Transaction.class);
        DatabaseMetadata metadata = mock(DatabaseMetadata.class);
        doReturn(transaction).when(f.delegate).transaction();
        doReturn(true).when(f.delegate).isSSL();
        doReturn(metadata).when(f.delegate).databaseMetadata();
        DeadlineGuardedConnection guarded = new DeadlineGuardedConnection(f.delegate);
        Supplier<Future<?>> builtBeforeExpiry = variant.build().apply(guarded);
        Supplier<Future<?>> alsoBuiltBeforeExpiry = variant.build().apply(guarded);

        // When it executes before expiry, then the connection is marked expired
        Future<?> beforeExpiry = builtBeforeExpiry.get();

        // Then it reached the delegate once
        assertTrue(beforeExpiry.succeeded(), "a statement before expiry must reach the delegate");
        assertEquals(1, f.executeCalls());

        // When the connection is marked expired and the variant executes again
        guarded.expire();
        Future<?> builtEarlyRunLate = alsoBuiltBeforeExpiry.get();
        Future<?> builtAndRunLate = variant.build().apply(guarded).get();

        // Then both fail with the deadline exception without reaching the delegate
        assertTrue(builtEarlyRunLate.failed());
        assertInstanceOf(TransactionDeadlineExceededException.class, builtEarlyRunLate.cause());
        assertTrue(builtAndRunLate.failed());
        assertInstanceOf(TransactionDeadlineExceededException.class, builtAndRunLate.cause());
        assertEquals(1, f.executeCalls(), "no statement may reach the delegate after expiry");
        assertEquals(2, guarded.refusedStatements());

        // And prepare, begin and close always fail as usage errors without reaching the delegate
        for (Future<?> refused : List.of(
                guarded.prepare(SQL), guarded.prepare(SQL, new PrepareOptions()), guarded.begin(), guarded.close())) {
            assertTrue(refused.failed());
            assertInstanceOf(InvalidDataAccessUsageException.class, refused.cause());
        }
        verify(f.delegate, never()).prepare(any(String.class));
        verify(f.delegate, never()).prepare(any(String.class), any(PrepareOptions.class));
        verify(f.delegate, never()).begin();
        verify(f.delegate, never()).close();

        // And the accessors delegate and the handler setters register on the delegate and return the guard
        assertSame(transaction, guarded.transaction());
        assertTrue(guarded.isSSL());
        assertSame(metadata, guarded.databaseMetadata());
        Handler<Throwable> exceptionHandler = t -> {};
        Handler<Void> closeHandler = v -> {};
        assertSame(guarded, guarded.exceptionHandler(exceptionHandler));
        assertSame(guarded, guarded.closeHandler(closeHandler));
        verify(f.delegate).exceptionHandler(exceptionHandler);
        verify(f.delegate).closeHandler(closeHandler);
        assertInstanceOf(SqlConnection.class, guarded);
    }

    @Test
    @DisplayName("prepare, begin and close fail as usage errors before expiry too")
    void prepareBeginAndCloseFailBeforeExpiry() {
        Fixture f = new Fixture();
        DeadlineGuardedConnection guarded = new DeadlineGuardedConnection(f.delegate);

        for (Future<?> refused : List.of(guarded.prepare(SQL), guarded.begin(), guarded.close())) {
            assertTrue(refused.failed());
            assertInstanceOf(InvalidDataAccessUsageException.class, refused.cause());
        }
        verify(f.delegate, never()).begin();
        verify(f.delegate, never()).close();
    }

    @Test
    @DisplayName("every Query and PreparedQuery method that returns a future or a query is covered by a variant")
    void everyFutureOrQueryReturningMethodIsCoveredByAVariant() {
        Set<String> covered = variants()
                .map(a -> (Variant) a.get()[0])
                .flatMap(v -> v.covers().stream())
                .collect(Collectors.toSet());

        Set<String> declared = new HashSet<>();
        for (Class<?> type : List.of(Query.class, PreparedQuery.class)) {
            for (Method m : type.getMethods()) {
                boolean futureOrQuery = Future.class.isAssignableFrom(m.getReturnType())
                        || Query.class.isAssignableFrom(m.getReturnType());
                if (futureOrQuery && !m.isBridge() && !m.isSynthetic()) {
                    String params = Arrays.stream(m.getParameterTypes())
                            .map(Class::getSimpleName)
                            .collect(Collectors.joining(","));
                    declared.add(type.getSimpleName() + "#" + m.getName() + "(" + params + ")");
                }
            }
        }

        Set<String> uncovered = new HashSet<>(declared);
        uncovered.removeAll(covered);
        assertTrue(uncovered.isEmpty(), "methods without a statement variant: " + uncovered);
    }
}
