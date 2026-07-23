// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.IsolationLevel;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.WorkflowExceptionMapper;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.PrepareOptions;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.sqlclient.spi.DatabaseMetadata;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests verifying the setup-statement budget of {@link PgWorkflowTransactionRunner}.
 *
 * <p>A statement-counting {@link Pool} decorator records every {@code query(String)} issued on the
 * transaction connection. The runner must:
 *
 * <ul>
 *   <li>issue <b>zero</b> {@code SET} statements for a write transaction ({@code level == null});</li>
 *   <li>issue exactly <b>one</b> {@code SET TRANSACTION ISOLATION LEVEL REPEATABLE READ} for a
 *       {@link IsolationLevel#REPEATABLE_READ} transaction;</li>
 *   <li><b>never</b> issue a {@code SET TRANSACTION READ ONLY} statement.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowTransactionRunnerStatementCountIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_tx_runner_count_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool realPool;
    static CountingPool countingPool;
    static WorkflowTransactionRunner<SqlClient> runner;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        realPool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        countingPool = new CountingPool(realPool);
        runner = new PgWorkflowTransactionRunner(
                new WorkflowTxRunnerRepository(countingPool, new WorkflowPgExceptionMapper()),
                new WorkflowExceptionMapper());
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (realPool != null) {
            realPool.close();
        }
    }

    @BeforeEach
    void resetCounters() {
        countingPool.captured.clear();
    }

    /**
     * A write transaction (null isolation level) must issue zero {@code SET} statements before the
     * body runs.
     */
    @Test
    @DisplayName("write transaction (null level) issues zero SET statements")
    void writeTransactionIssuesZeroSetStatements(VertxTestContext ctx) {
        runner.inTransaction(null, tx -> Future.succeededFuture(null))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "write transaction must succeed, got: " + ar.cause());
                    assertEquals(
                            0,
                            countSet(),
                            "write transaction must issue zero SET statements, captured=" + setStatements());
                    ctx.completeNow();
                }));
    }

    /**
     * A REPEATABLE READ transaction must issue exactly one
     * {@code SET TRANSACTION ISOLATION LEVEL REPEATABLE READ} statement.
     */
    @Test
    @DisplayName("query transaction (REPEATABLE_READ) issues exactly one SET ISOLATION statement")
    void queryTransactionIssuesOneSetStatement(VertxTestContext ctx) {
        runner.inTransaction(IsolationLevel.REPEATABLE_READ, tx -> Future.succeededFuture(null))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query transaction must succeed, got: " + ar.cause());
                    List<String> sets = setStatements();
                    assertEquals(1, sets.size(), "REPEATABLE_READ must issue exactly one SET, captured=" + sets);
                    assertTrue(
                            sets.get(0).toUpperCase(Locale.ROOT).contains("ISOLATION LEVEL REPEATABLE READ"),
                            "the SET must set REPEATABLE READ isolation, got: " + sets.get(0));
                    ctx.completeNow();
                }));
    }

    /**
     * Neither a write nor a REPEATABLE READ transaction may issue a {@code SET TRANSACTION READ ONLY}
     * statement.
     */
    @Test
    @DisplayName("no SET TRANSACTION READ ONLY statement is ever issued")
    void noReadOnlySetStatementIsEverIssued(VertxTestContext ctx) {
        runner.inTransaction(null, tx -> Future.succeededFuture(null))
                .compose(v -> runner.inTransaction(IsolationLevel.REPEATABLE_READ, tx -> Future.succeededFuture(null)))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "both transactions must succeed, got: " + ar.cause());
                    boolean anyReadOnly = countingPool.captured.stream()
                            .anyMatch(sql -> sql.toUpperCase(Locale.ROOT).contains("READ ONLY"));
                    assertTrue(!anyReadOnly, "no READ ONLY statement may be issued, captured=" + countingPool.captured);
                    ctx.completeNow();
                }));
    }

    // --- Helpers ---

    private long countSet() {
        return countingPool.captured.stream()
                .filter(sql -> sql.toUpperCase(Locale.ROOT).startsWith("SET"))
                .count();
    }

    private List<String> setStatements() {
        return countingPool.captured.stream()
                .filter(sql -> sql.toUpperCase(Locale.ROOT).startsWith("SET"))
                .toList();
    }

    // --- Statement-counting decorators ---

    /**
     * A {@link Pool} decorator that, on {@link #withTransaction(Function)}, wraps the transaction
     * connection in a {@link CountingConnection} so the SQL of every {@code query(String)} is
     * recorded. All other calls delegate to the real pool.
     */
    static final class CountingPool implements Pool {

        final Pool delegate;
        final List<String> captured = new CopyOnWriteArrayList<>();

        CountingPool(Pool delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> Future<T> withTransaction(Function<SqlConnection, Future<T>> function) {
            return delegate.withTransaction(conn -> function.apply(new CountingConnection(conn, captured)));
        }

        @Override
        public <T> Future<T> withTransaction(
                TransactionPropagation propagation, Function<SqlConnection, Future<T>> function) {
            return delegate.withTransaction(
                    propagation, conn -> function.apply(new CountingConnection(conn, captured)));
        }

        @Override
        public <T> Future<T> withConnection(Function<SqlConnection, Future<T>> function) {
            return delegate.withConnection(conn -> function.apply(new CountingConnection(conn, captured)));
        }

        @Override
        public Future<SqlConnection> getConnection() {
            return delegate.getConnection().map(conn -> new CountingConnection(conn, captured));
        }

        @Override
        public Query<RowSet<Row>> query(String sql) {
            captured.add(sql);
            return delegate.query(sql);
        }

        @Override
        public PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
            return delegate.preparedQuery(sql);
        }

        @Override
        public PreparedQuery<RowSet<Row>> preparedQuery(String sql, PrepareOptions options) {
            return delegate.preparedQuery(sql, options);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public Future<Void> close() {
            return delegate.close();
        }
    }

    /**
     * A {@link SqlConnection} decorator that records the SQL text of every {@code query(String)} call
     * (this is where {@code applyTransactionOptions} issues its {@code SET} statements) and delegates
     * everything else to the real connection.
     */
    static final class CountingConnection implements SqlConnection {

        private final SqlConnection delegate;
        private final List<String> captured;

        CountingConnection(SqlConnection delegate, List<String> captured) {
            this.delegate = delegate;
            this.captured = captured;
        }

        @Override
        public Query<RowSet<Row>> query(String sql) {
            captured.add(sql);
            return delegate.query(sql);
        }

        @Override
        public PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
            return delegate.preparedQuery(sql);
        }

        @Override
        public PreparedQuery<RowSet<Row>> preparedQuery(String sql, PrepareOptions options) {
            return delegate.preparedQuery(sql, options);
        }

        @Override
        public Future<PreparedStatement> prepare(String sql) {
            return delegate.prepare(sql);
        }

        @Override
        public Future<PreparedStatement> prepare(String sql, PrepareOptions options) {
            return delegate.prepare(sql, options);
        }

        @Override
        public SqlConnection exceptionHandler(io.vertx.core.Handler<Throwable> handler) {
            delegate.exceptionHandler(handler);
            return this;
        }

        @Override
        public SqlConnection closeHandler(io.vertx.core.Handler<Void> handler) {
            delegate.closeHandler(handler);
            return this;
        }

        @Override
        public Future<Transaction> begin() {
            return delegate.begin();
        }

        @Override
        public Transaction transaction() {
            return delegate.transaction();
        }

        @Override
        public boolean isSSL() {
            return delegate.isSSL();
        }

        @Override
        public DatabaseMetadata databaseMetadata() {
            return delegate.databaseMetadata();
        }

        @Override
        public Future<Void> close() {
            return delegate.close();
        }
    }
}
