// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests for the transaction deadline against a real PostgreSQL instance via
 * Testcontainers. The repository runs on a pool of size one, so a stale connection handle would
 * reach the next holder's physical connection; a second pool observes the database from outside.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgTransactionDeadlineIT {

    private static final String INSERT = "INSERT INTO deadline_test (name) VALUES ($1)";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("deadline_test_db");
    static Vertx vertx;
    static Pool pool;
    static Pool observer;
    static PgSqlRepository repository;

    private final List<Probe> probes = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        db.start();
        vertx = Vertx.vertx();
        pool = buildPool(1);
        observer = buildPool(2);
        repository = new PgSqlRepository(pool, new PgDbExceptionMapper());

        await(observer.query("CREATE TABLE deadline_test (id SERIAL PRIMARY KEY, name VARCHAR(255))")
                .execute());
        await(observer.query("""
                        CREATE FUNCTION deadline_test_sleep_at_commit() RETURNS trigger AS $$
                        BEGIN
                            PERFORM pg_sleep(1);
                            RETURN NULL;
                        END
                        $$ LANGUAGE plpgsql
                        """).execute());
        await(observer.query("""
                        CREATE CONSTRAINT TRIGGER deadline_test_slow_commit
                            AFTER INSERT ON deadline_test
                            DEFERRABLE INITIALLY DEFERRED
                            FOR EACH ROW WHEN (NEW.name = 'd')
                            EXECUTE FUNCTION deadline_test_sleep_at_commit()
                        """).execute());
        // Warm the single pooled connection so connection setup does not eat into the deadlines.
        await(pool.query("SELECT 1").execute());
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (observer != null) {
                await(observer.query("DROP TABLE IF EXISTS deadline_test").execute());
                await(observer.query("DROP FUNCTION IF EXISTS deadline_test_sleep_at_commit()")
                        .execute());
            }
        } finally {
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
    }

    @BeforeEach
    void emptyTable() throws Exception {
        await(observer.query("TRUNCATE deadline_test").execute());
    }

    @AfterEach
    void openEveryGate() {
        probes.forEach(Probe::openGate);
    }

    enum Scenario {
        IDLE_STALL,
        LATE_CONTINUATION,
        IN_FLIGHT,
        LATE_COMMIT,
        SPENT_BUDGET,
        NO_DEADLINE,
        CAPTURED_AFTER_COMPLETION
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void transactionPastItsDeadlineRollsBackAndLateStatementsWriteNothing(Scenario scenario) throws Exception {
        switch (scenario) {
            case IDLE_STALL -> idleStall();
            case LATE_CONTINUATION -> lateContinuation();
            case IN_FLIGHT -> inFlight();
            case LATE_COMMIT -> lateCommit();
            case SPENT_BUDGET -> spentBudget();
            case NO_DEADLINE -> noDeadline();
            case CAPTURED_AFTER_COMPLETION -> capturedAfterCompletion();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mappers")
    void deadlineExceptionPassesTheExceptionMapperUntranslated(String name, Supplier<PgDbExceptionMapper> mapper)
            throws Exception {
        // Given a repository whose mapper may rewrite any DataAccessException
        PgSqlRepository mapped = new PgSqlRepository(pool, mapper.get());
        Probe probe = probe();

        // When a deadline-bounded transaction stalls past its deadline
        Future<Void> result =
                mapped.transaction().deadline(Duration.ofMillis(200)).execute(stalling(probe));
        Throwable failure = failureOf(result);

        // Then the failure is the deadline exception itself, not a rewritten one
        assertInstanceOf(TransactionDeadlineExceededException.class, failure);
        assertEquals(TransactionDeadlineExceededException.class, failure.getClass());
        assertNotEquals("rewritten", failure.getMessage());
        assertFalse(failure instanceof TransientDataAccessException);
    }

    static Stream<Arguments> mappers() {
        Supplier<PgDbExceptionMapper> rewriting = () -> {
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            mapper.on(DataAccessException.class, (e, ctx) -> new DataAccessException("rewritten"));
            return mapper;
        };
        Supplier<PgDbExceptionMapper> defaults = PgDbExceptionMapper::new;
        return Stream.of(
                Arguments.of("custom mapper rewriting every DataAccessException", rewriting),
                Arguments.of("default PgDbExceptionMapper", defaults));
    }

    // (a) idle stall: the work inserts row a, then waits on a gate that stays closed
    private void idleStall() throws Exception {
        Probe probe = probe();

        Future<Void> result =
                repository.transaction().deadline(Duration.ofSeconds(1)).execute(stalling(probe));
        Throwable failure = failureOf(result);

        assertInstanceOf(TransactionDeadlineExceededException.class, failure);
        assertEquals(0, rowsOf("a"), "the stalled work's row must be rolled back");
        assertFalse(holdsLock(probe.pid), "the rollback must have released the transaction's lock");
        assertEquals("dev.vertique.db", probe.clientPackage, "the work is handed the guarded connection");
    }

    // (b) late continuation: the gate opens after the deadline while the next holder uses the pool
    private void lateContinuation() throws Exception {
        Probe probe = probe();
        AtomicInteger refused = new AtomicInteger();
        CompletableFuture<Throwable> lateOutcome = new CompletableFuture<>();
        Function<SqlConnection, Future<Void>> work =
                conn -> stalling(probe).apply(conn).compose(v -> {
                    Future<?> late = conn.preparedQuery(INSERT).execute(Tuple.of("late"));
                    late.onComplete(ar -> {
                        if (ar.failed() && ar.cause() instanceof TransactionDeadlineExceededException) {
                            refused.incrementAndGet();
                        }
                        lateOutcome.complete(ar.cause());
                    });
                    return late.mapEmpty();
                });

        long start = System.nanoTime();
        Future<Void> result =
                repository.transaction().deadline(Duration.ofSeconds(1)).execute(work);
        sleepUntil(start, 1100);
        // the next holder takes the pool's only connection and keeps it across the late continuation
        Future<Long> nextHolder = repository.transaction().execute(conn -> conn.query("SELECT 1")
                .execute()
                .compose(r -> vertx.timer(500))
                .compose(r -> conn.preparedQuery("SELECT count(*) FROM deadline_test WHERE name = $1")
                        .execute(Tuple.of("late")))
                .map(rows -> rows.iterator().next().getLong(0)));
        Future<Long> autocommit = pool.preparedQuery("SELECT count(*) FROM deadline_test WHERE name = $1")
                .execute(Tuple.of("late"))
                .map(rows -> rows.iterator().next().getLong(0));
        sleepUntil(start, 1300);
        probe.openGate();

        Throwable failure = failureOf(result);
        Throwable lateFailure = lateOutcome.get(10, TimeUnit.SECONDS);

        assertInstanceOf(TransactionDeadlineExceededException.class, failure);
        assertInstanceOf(TransactionDeadlineExceededException.class, lateFailure);
        assertEquals(1, refused.get(), "exactly the continuation's statement is refused");
        assertEquals(0L, await(nextHolder), "the next holder's transaction must not see the late row");
        assertEquals(0L, await(autocommit), "no autocommit statement may see the late row");
        assertEquals(0, rowsOf("a"));
        assertEquals(0, rowsOf("late"), "the late row must exist nowhere");
    }

    // (c) in flight: a statement already running at the deadline finishes first, then the rollback runs
    private void inFlight() throws Exception {
        Probe probe = probe();
        Function<SqlConnection, Future<Void>> work = conn -> {
            probe.record(conn);
            return conn.query("SELECT pg_backend_pid()")
                    .execute()
                    .compose(rows -> {
                        probe.pid = rows.iterator().next().getInteger(0);
                        return conn.preparedQuery(INSERT).execute(Tuple.of("c"));
                    })
                    .compose(r -> vertx.timer(500))
                    .compose(r -> conn.query("SELECT pg_sleep(2)").execute())
                    .mapEmpty();
        };

        long start = System.nanoTime();
        Future<Void> result =
                repository.transaction().deadline(Duration.ofSeconds(1)).execute(work);
        sleepUntil(start, 1500);
        boolean lockedAfterDeadline = holdsLock(probe.pid);
        Throwable failure = failureOf(result);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertInstanceOf(TransactionDeadlineExceededException.class, failure);
        assertTrue(lockedAfterDeadline, "the in-flight statement keeps the transaction's lock past the deadline");
        assertTrue(elapsedMillis >= 2300, "the failure arrives after pg_sleep returns, took " + elapsedMillis + " ms");
        assertFalse(holdsLock(probe.pid), "the rollback must have released the lock");
        assertEquals(0, rowsOf("c"));
    }

    // (d) late commit: the work completes before the deadline, the commit ends after it
    private void lateCommit() throws Exception {
        Function<SqlConnection, Future<Void>> work = conn -> {
            Future<Void> wait = vertx.timer(900).mapEmpty();
            return conn.preparedQuery(INSERT).execute(Tuple.of("d")).compose(r -> wait);
        };

        Future<Void> result =
                repository.transaction().deadline(Duration.ofSeconds(1)).execute(work);
        AsyncResult<Void> outcome = await(result.transform(Future::succeededFuture));

        assertTrue(outcome.succeeded(), "work completed before the deadline must commit, got " + outcome.cause());
        assertEquals(1, rowsOf("d"));
    }

    // (e) spent budget: the pool acquisition outlasts the deadline, so the work never runs
    private void spentBudget() throws Exception {
        CompletableFuture<Void> holderHasConnection = new CompletableFuture<>();
        Future<Void> holder = repository.transaction().execute(conn -> {
            holderHasConnection.complete(null);
            return vertx.timer(50).mapEmpty();
        });
        holderHasConnection.get(10, TimeUnit.SECONDS);
        AtomicBoolean ran = new AtomicBoolean();

        Future<Void> result = repository
                .transaction()
                .deadline(Duration.ofMillis(1))
                .execute(conn -> {
                    ran.set(true);
                    return Future.succeededFuture();
                });
        Throwable failure = failureOf(result);
        await(holder);

        assertInstanceOf(TransactionDeadlineExceededException.class, failure);
        assertFalse(ran.get(), "the work must not run when the budget is already spent");
    }

    // (f) no deadline: the same work as (a) succeeds and is handed the pooled connection itself
    private void noDeadline() throws Exception {
        Probe probe = probe();
        vertx.setTimer(100, id -> probe.openGate());

        Future<Void> result = repository.transaction().execute(stalling(probe));
        AsyncResult<Void> outcome = await(result.transform(Future::succeededFuture));

        assertTrue(outcome.succeeded(), "got " + outcome.cause());
        assertEquals(1, rowsOf("a"));
        assertNotEquals("dev.vertique.db", probe.clientPackage, "no guard is installed without a deadline");
    }

    // (g) captured after completion: the work finishes normally, then a statement through its connection is refused
    private void capturedAfterCompletion() throws Exception {
        List<SqlConnection> captured = new ArrayList<>();
        Function<SqlConnection, Future<Void>> work = conn -> {
            captured.add(conn);
            return conn.preparedQuery(INSERT).execute(Tuple.of("g")).mapEmpty();
        };

        await(repository.transaction().deadline(Duration.ofSeconds(5)).execute(work));
        Throwable late = failureOf(captured.getFirst().preparedQuery(INSERT).execute(Tuple.of("late")));

        assertInstanceOf(TransactionDeadlineExceededException.class, late);
        assertEquals(1, rowsOf("g"), "the work's own row is committed");
        assertEquals(0, rowsOf("late"), "the late statement must write nothing");
    }

    /** What a piece of work observed, and the gate that releases it. */
    private static final class Probe {
        final Promise<Void> gate = Promise.promise();
        volatile Integer pid;
        volatile Context context;
        volatile String clientPackage;

        void record(SqlConnection conn) {
            context = Vertx.currentContext();
            clientPackage = conn.getClass().getPackageName();
        }

        /** Opens the gate on the work's own context, as a real continuation would resume. */
        void openGate() {
            Context c = context;
            if (c != null) {
                c.runOnContext(v -> gate.tryComplete());
            } else {
                gate.tryComplete();
            }
        }
    }

    private Probe probe() {
        Probe probe = new Probe();
        probes.add(probe);
        return probe;
    }

    /** Work that inserts row a, then waits on the probe's gate. */
    private static Function<SqlConnection, Future<Void>> stalling(Probe probe) {
        return conn -> {
            probe.record(conn);
            return conn.query("SELECT pg_backend_pid()")
                    .execute()
                    .compose(rows -> {
                        probe.pid = rows.iterator().next().getInteger(0);
                        return conn.preparedQuery(INSERT).execute(Tuple.of("a"));
                    })
                    .compose(r -> probe.gate.future());
        };
    }

    private static int rowsOf(String name) throws Exception {
        return await(observer.preparedQuery("SELECT count(*) FROM deadline_test WHERE name = $1")
                .execute(Tuple.of(name))
                .map(rows -> rows.iterator().next().getInteger(0)));
    }

    private static boolean holdsLock(int pid) throws Exception {
        return await(observer.preparedQuery(
                        "SELECT count(*) FROM pg_locks WHERE pid = $1 AND locktype = 'transactionid' AND granted")
                .execute(Tuple.of(pid))
                .map(rows -> rows.iterator().next().getLong(0) > 0));
    }

    private static void sleepUntil(long startNanos, long millisAfterStart) throws InterruptedException {
        long remaining = millisAfterStart - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        AsyncResult<?> outcome = await(future.transform(Future::succeededFuture));
        assertTrue(outcome.failed(), "expected the transaction to fail");
        return outcome.cause();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static Pool buildPool(int maxSize) {
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
}
