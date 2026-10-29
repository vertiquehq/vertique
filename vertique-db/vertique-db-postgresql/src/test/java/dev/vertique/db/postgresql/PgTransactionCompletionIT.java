// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.TransactionCompletion;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the behaviour {@link TransactionCompletion} relies on, against a real PostgreSQL instance: the connection's
 * transaction completion settles exactly once, after the commit is visible to other sessions, and reports every way a
 * transaction can fail to commit.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgTransactionCompletionIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("completion_test_db");
    static Vertx vertx;
    static Pool pool;
    static Pool observer;
    static PgSqlRepository repository;

    /** What one transaction told its callbacks. */
    static final class Signals {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch settled = new CountDownLatch(1);

        void awaitSettled() throws InterruptedException {
            assertTrue(settled.await(20, TimeUnit.SECONDS), "the transaction never settled");
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        db.start();
        vertx = Vertx.vertx();
        pool = pool(1);
        observer = pool(2);
        repository = new PgSqlRepository(pool, new PgDbExceptionMapper());
        await(pool.query("DROP TABLE IF EXISTS completion_test").execute());
        await(pool.query("CREATE TABLE completion_test (id INT PRIMARY KEY, name TEXT)")
                .execute());
        await(pool.query("""
                        CREATE TABLE completion_deferred (
                            id INT PRIMARY KEY,
                            parent_id INT,
                            CONSTRAINT fk_deferred FOREIGN KEY (parent_id) REFERENCES completion_test (id)
                                DEFERRABLE INITIALLY DEFERRED)
                        """).execute());
        await(pool.query("SELECT 1").execute()); // warm the single pooled connection
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (observer != null) {
            observer.close();
        }
        if (vertx != null) {
            vertx.close();
        }
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        await(observer.query("DELETE FROM completion_deferred").execute());
        await(observer.query("DELETE FROM completion_test").execute());
    }

    @Test
    @DisplayName("commit callback runs once, after the row is visible to another session")
    void commitIsVisibleWhenCallbackRuns() throws Exception {
        Signals signals = new Signals();
        AtomicBoolean visible = new AtomicBoolean();
        await(repository.transaction().execute(conn -> insert(conn, 1).map(v -> {
            TransactionCompletion.onSettled(
                    conn,
                    () -> {
                        signals.events.add("commit");
                        observer.query("SELECT count(*) FROM completion_test WHERE id = 1")
                                .execute()
                                .onSuccess(rows -> {
                                    visible.set(rows.iterator().next().getLong(0) == 1L);
                                    signals.settled.countDown();
                                });
                    },
                    cause -> {
                        signals.events.add("failure");
                        signals.settled.countDown();
                    });
            return null;
        })));
        signals.awaitSettled();
        assertEquals(List.of("commit"), signals.events);
        assertTrue(visible.get(), "the commit must be visible to another session when the callback runs");
    }

    @Test
    @DisplayName("a failing body reports a failure and never a commit")
    void bodyFailureReportsFailure() throws Exception {
        Signals signals =
                register(conn -> insert(conn, 2).compose(v -> Future.failedFuture(new IllegalStateException("body"))));
        signals.awaitSettled();
        assertEquals(List.of("failure"), signals.events);
        assertNotNull(signals.failure.get());
        assertEquals(0L, count("completion_test"));
    }

    @Test
    @DisplayName("an explicit rollback reports a failure carrying a rollback exception")
    void explicitRollbackReportsFailure() throws Exception {
        Signals signals = new Signals();
        Future<?> result = pool.withTransaction(conn -> insert(conn, 3).compose(v -> {
            listen(conn, signals);
            return conn.transaction().rollback();
        }));
        signals.awaitSettled();
        settle(result);
        assertEquals(List.of("failure"), signals.events);
        System.out.println("SPIKE explicit rollback cause: " + signals.failure.get());
        assertEquals(0L, count("completion_test"));
    }

    @Test
    @DisplayName("a deferred constraint that fails at COMMIT reports a failure")
    void deferredConstraintFailureAtCommit() throws Exception {
        Signals signals = new Signals();
        Future<?> result = repository.transaction().execute(conn -> conn.preparedQuery(
                        "INSERT INTO completion_deferred (id, parent_id) VALUES ($1, $2)")
                .execute(Tuple.of(1, 999))
                .map(rows -> {
                    listen(conn, signals);
                    return null;
                }));
        signals.awaitSettled();
        settle(result);
        assertTrue(result.failed(), "the commit must fail");
        assertEquals(List.of("failure"), signals.events);
        System.out.println("SPIKE deferred-constraint cause: " + signals.failure.get());
    }

    @Test
    @DisplayName("a failed SQL statement fails the Vert.x transaction even when a savepoint would recover it")
    void sqlFailureInsideSavepointFailsTheTransaction() throws Exception {
        Signals signals = new Signals();
        Future<?> result = repository.transaction().execute(conn -> insert(conn, 4)
                .compose(v -> {
                    listen(conn, signals);
                    return conn.query("SAVEPOINT s1").execute();
                })
                .compose(v -> insert(conn, 4).recover(err -> Future.succeededFuture())) // duplicate key
                .compose(v -> conn.query("ROLLBACK TO SAVEPOINT s1").execute()));
        signals.awaitSettled();
        settle(result);
        System.out.println("SPIKE sql error in savepoint: events=" + signals.events + " result=" + result.cause());
        assertEquals(List.of("failure"), signals.events);
        assertTrue(result.failed());
        assertEquals(0L, count("completion_test"));
    }

    @Test
    @DisplayName("a savepoint rollback does not settle the outer transaction")
    void savepointRollbackDoesNotSettle() throws Exception {
        Signals signals = new Signals();
        await(repository.transaction().execute(conn -> {
            listen(conn, signals);
            return insert(conn, 5)
                    .compose(v -> conn.query("SAVEPOINT s2").execute())
                    .compose(v -> insert(conn, 6))
                    .compose(v -> conn.query("ROLLBACK TO SAVEPOINT s2").execute())
                    .map(v -> {
                        assertTrue(signals.events.isEmpty(), "nothing may fire before the outer commit");
                        return null;
                    });
        }));
        signals.awaitSettled();
        assertEquals(List.of("commit"), signals.events);
        assertEquals(1L, count("completion_test"));
    }

    @Test
    @DisplayName("several registrations are independent and a throwing callback does not stop the others")
    void severalCallbacksAreIndependent() throws Exception {
        Signals signals = new Signals();
        CountDownLatch both = new CountDownLatch(2);
        await(repository.transaction().execute(conn -> insert(conn, 7).map(v -> {
            TransactionCompletion.onSettled(
                    conn,
                    () -> {
                        both.countDown();
                        throw new IllegalStateException("callback failure");
                    },
                    cause -> {});
            TransactionCompletion.onSettled(conn, both::countDown, cause -> {});
            return null;
        })));
        assertTrue(both.await(20, TimeUnit.SECONDS), "both commit callbacks must run");
        assertEquals(1L, count("completion_test"));
        assertTrue(signals.events.isEmpty());
    }

    @Test
    @DisplayName("an unsupported handle, a missing transaction and a settled transaction are rejected")
    void invalidRegistrationsFailFast() throws Exception {
        assertThrows(IllegalStateException.class, () -> TransactionCompletion.onSettled(pool, () -> {}, cause -> {}));
        AtomicReference<SqlConnection> held = new AtomicReference<>();
        await(pool.getConnection().compose(conn -> {
            held.set(conn);
            assertThrows(
                    IllegalStateException.class, () -> TransactionCompletion.onSettled(conn, () -> {}, cause -> {}));
            return conn.close();
        }));
        AtomicReference<SqlConnection> afterCommit = new AtomicReference<>();
        await(pool.withTransaction(conn -> {
            afterCommit.set(conn);
            return insert(conn, 8);
        }));
        assertThrows(
                IllegalStateException.class,
                () -> TransactionCompletion.onSettled(afterCommit.get(), () -> {}, cause -> {}));
    }

    @Test
    @DisplayName("a pooled connection reused by the next transaction does not inherit an earlier registration")
    void pooledConnectionReuse() throws Exception {
        Signals first = register(conn -> insert(conn, 9));
        first.awaitSettled();
        List<String> second = new CopyOnWriteArrayList<>();
        await(pool.withTransaction(conn -> insert(conn, 10).map(v -> {
            second.add("work");
            return null;
        })));
        Thread.sleep(200);
        assertEquals(List.of("commit"), first.events, "the first registration must fire exactly once");
        assertEquals(List.of("work"), second);
    }

    @Test
    @DisplayName("the deadline-guarded connection forwards the signal on commit")
    void deadlineGuardedCommit() throws Exception {
        Signals signals = new Signals();
        await(repository.transaction().deadline(Duration.ofSeconds(10)).execute(conn -> insert(conn, 11)
                .map(v -> {
                    listen(conn, signals);
                    return null;
                })));
        signals.awaitSettled();
        assertEquals(List.of("commit"), signals.events);
    }

    @Test
    @DisplayName("the deadline-guarded connection forwards the signal when the deadline rolls the transaction back")
    void deadlineGuardedExpiry() throws Exception {
        Signals signals = new Signals();
        Future<?> result = repository
                .transaction()
                .deadline(Duration.ofMillis(300))
                .execute(conn -> {
                    listen(conn, signals);
                    return conn.query("SELECT pg_sleep(2)").execute();
                });
        signals.awaitSettled();
        settle(result);
        assertTrue(result.failed());
        assertTrue(result.cause() instanceof TransactionDeadlineExceededException, String.valueOf(result.cause()));
        assertEquals(List.of("failure"), signals.events);
        assertEquals(0L, count("completion_test"));
    }

    @Test
    @DisplayName("a backend terminated mid-transaction either reports a failure or never settles, never a commit")
    void terminatedBackend() throws Exception {
        Signals signals = new Signals();
        Future<?> result = repository.transaction().execute(conn -> insert(conn, 12)
                .compose(v -> {
                    listen(conn, signals);
                    return conn.query("SELECT pg_terminate_backend(pg_backend_pid())")
                            .execute();
                })
                .compose(v -> conn.query("SELECT 1").execute()));
        boolean settled = signals.settled.await(10, TimeUnit.SECONDS);
        settle(result);
        System.out.println("SPIKE terminated backend: settled=" + settled + " events=" + signals.events
                + " resultFailed=" + result.failed());
        assertFalse(signals.events.contains("commit"), "a terminated backend must never report a commit");
        assertEquals(0L, count("completion_test"));
        await(pool.query("SELECT 1").execute()); // the pool must recover
    }

    // --- helpers ---

    private static Signals register(java.util.function.Function<SqlConnection, Future<?>> work) throws Exception {
        Signals signals = new Signals();
        Future<?> result = repository.transaction().execute(conn -> {
            listen(conn, signals);
            return work.apply(conn);
        });
        result.otherwise(cause -> null)
                .toCompletionStage()
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
        return signals;
    }

    private static void listen(SqlConnection conn, Signals signals) {
        TransactionCompletion.onSettled(
                conn,
                () -> {
                    signals.events.add("commit");
                    signals.settled.countDown();
                },
                cause -> {
                    signals.events.add("failure");
                    signals.failure.set(cause);
                    signals.settled.countDown();
                });
    }

    private static Future<?> insert(SqlConnection conn, int id) {
        return conn.preparedQuery("INSERT INTO completion_test (id, name) VALUES ($1, $2)")
                .execute(Tuple.of(id, "row-" + id));
    }

    private static long count(String table) throws Exception {
        return await(observer.query("SELECT count(*) FROM " + table).execute())
                .iterator()
                .next()
                .getLong(0);
    }

    private static Pool pool(int maxSize) {
        DbPoolConfig config = db.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(maxSize))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    /** Waits for a transaction's own future, which completes after its completion callbacks. */
    private static void settle(Future<?> result) throws Exception {
        result.otherwise(cause -> null)
                .toCompletionStage()
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
