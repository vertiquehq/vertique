// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.Savepoint;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for transaction options (isolation level, read-only mode) and the
 * {@link Savepoint} utility against a real PostgreSQL instance via Testcontainers.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgTransactionIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("tx_test");
    static Pool pool;
    static PgDbExceptionMapper exceptionMapper;
    static PgSqlRepository repository;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        db.start();
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        exceptionMapper = new PgDbExceptionMapper();
        repository = new PgSqlRepository(pool, exceptionMapper);

        pool.query("CREATE TABLE tx_test (id SERIAL PRIMARY KEY, name VARCHAR(255))")
                .execute()
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
        db.close();
    }

    @Test
    @DisplayName("transaction with SERIALIZABLE isolation sets correct level")
    void shouldUseSerializableIsolation(VertxTestContext ctx) {
        repository
                .transaction()
                .serializable()
                .execute(conn -> conn.query("SHOW transaction_isolation")
                        .execute()
                        .map(rows -> rows.iterator().next().getString(0)))
                .onSuccess(level -> {
                    ctx.verify(() -> assertEquals("serializable", level));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("transaction with REPEATABLE_READ isolation sets correct level")
    void shouldUseRepeatableReadIsolation(VertxTestContext ctx) {
        repository
                .transaction()
                .repeatableRead()
                .execute(conn -> conn.query("SHOW transaction_isolation")
                        .execute()
                        .map(rows -> rows.iterator().next().getString(0)))
                .onSuccess(level -> {
                    ctx.verify(() -> assertEquals("repeatable read", level));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("transaction with readOnly mode fails on INSERT")
    void shouldUseReadOnlyMode(VertxTestContext ctx) {
        repository
                .transaction()
                .readOnly()
                .execute(conn -> conn.preparedQuery("INSERT INTO tx_test (name) VALUES ($1)")
                        .execute(Tuple.of("should-fail")))
                .onSuccess(v -> ctx.failNow(new AssertionError("Expected failure but succeeded")))
                .onFailure(err -> {
                    ctx.verify(() -> assertTrue(
                            err instanceof DataAccessException,
                            "Expected DataAccessException but got: "
                                    + err.getClass().getName()));
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("transaction with default options uses read committed isolation")
    void shouldUseDefaultIsolationWhenNoOptions(VertxTestContext ctx) {
        repository
                .transaction()
                .execute(conn -> conn.query("SHOW transaction_isolation")
                        .execute()
                        .map(rows -> rows.iterator().next().getString(0)))
                .onSuccess(level -> {
                    ctx.verify(() -> assertEquals("read committed", level));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("Savepoint rolls back partial work within a transaction")
    void shouldSupportSavepointUtility(VertxTestContext ctx) {
        repository
                .transaction()
                .execute(conn ->
                        // Insert first row (outside savepoint)
                        conn.preparedQuery("INSERT INTO tx_test (name) VALUES ($1) RETURNING id")
                                .execute(Tuple.of("before-savepoint"))
                                .compose(rows -> {
                                    int beforeId = rows.iterator().next().getInteger(0);
                                    // Use savepoint around second insert that will be rolled back
                                    return Savepoint.execute(conn, "sp1", c -> c.preparedQuery(
                                                            "INSERT INTO tx_test (name) VALUES ($1) RETURNING id")
                                                    .execute(Tuple.of("inside-savepoint"))
                                                    .map(r ->
                                                            r.iterator().next().getInteger(0))
                                                    .compose(insideId ->
                                                            // Fail after insert to trigger savepoint rollback
                                                            Future.failedFuture(new RuntimeException("rollback this"))))
                                            .recover(err -> Future.succeededFuture(-1))
                                            .map(beforeId);
                                })
                                .compose(beforeId ->
                                        // Verify only the first row exists in this transaction
                                        conn.query("SELECT COUNT(*) FROM tx_test WHERE name = 'inside-savepoint'")
                                                .execute()
                                                .map(rows ->
                                                        rows.iterator().next().getInteger(0))))
                .onSuccess(count -> {
                    ctx.verify(
                            () -> assertEquals(0, count, "Row inserted inside savepoint should have been rolled back"));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }
}
