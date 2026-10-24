// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.LONG_LOCK_TIMEOUT_MS;
import static dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.Assembly;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.Held;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.TwoSessions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that every transaction that changes an instance takes the instance's execution lock, that requesters wait
 * for it under the configured timeout, and that the lock never collides with a definition lock.
 *
 * <p>Each test holds transactions open on separate connections and observes the database's lock table, so the
 * outcome does not depend on timing.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ExecutionLockIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_execution_lock_test")
            .withMigration("classpath:db/migration/workflow");

    private static Vertx vertx;
    private static Pool pool;

    /** Waits for an execution lock long enough that no test reaches the timeout unless it means to. */
    private static Assembly patient;

    /** Gives up on an execution lock after 200 ms. */
    private static Assembly impatient;

    @BeforeAll
    static void setUp(Vertx vertx) {
        ExecutionLockIT.vertx = vertx;
        pool = ExecutionLockTestSupport.pool(vertx, db.toPoolConfig(), 20);
        patient = Assembly.open(vertx, pool, LONG_LOCK_TIMEOUT_MS, true);
        impatient = Assembly.open(vertx, pool, 200L, true);
        await(pool.query("CREATE TABLE IF NOT EXISTS execution_lock_marker (id UUID PRIMARY KEY)")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static TwoSessions sessionsOf(Assembly assembly) {
        return new TwoSessions(assembly.runner(), pool, vertx);
    }

    @Test
    @DisplayName("a recorder waits for the open transaction of the same instance")
    void recordingWaitsForTheOpenTransactionOfTheSameInstance() {
        // Given a waiting instance X, and session A that signals X in a transaction it does not commit
        WorkflowInstanceId x = patient.startTick();
        TransactionalWorkflowOperations<SqlClient> operations =
                patient.component().transactionalOperations();
        try (TwoSessions sessions = sessionsOf(patient)) {
            Held<Void> a = sessions.holdOpen(tx -> operations.signal(x, "tick", "from-A", "dedup-A", tx));
            a.ready();

            // When session B signals X in its own transaction
            Held<Void> b = sessions.holdOpen(tx -> operations.signal(x, "tick", "from-B", "dedup-B", tx));

            // Then B waits for an advisory lock while A is open
            sessions.awaitBlocked(b);
            assertFalse(b.workFuture().isComplete(), "B must not finish while A holds the instance");

            // And after A commits, B completes
            a.commit();
            b.ready();
            b.commit();

            // And every history entry of A has a lower sequence than B's, with no duplicate or gap
            long signalA = historySequence(x, "dedup-A");
            long signalB = historySequence(x, "dedup-B");
            assertTrue(signalA < signalB, "A's signal (" + signalA + ") must precede B's (" + signalB + ")");
            List<Long> sequences = historySequences(x);
            for (int i = 0; i < sequences.size(); i++) {
                assertEquals(i + 1L, sequences.get(i), "history sequences of the instance must be contiguous");
            }
        }
    }

    @Test
    @DisplayName("queue sequences follow lock order and a rollback frees its sequence")
    void queueSequenceFollowsLockOrderAndARollbackFreesItsSequence() {
        // Given a pending instance with no queue row
        WorkflowInstanceId x = patient.insertPendingInstance();
        assertEquals(0L, queueRows(x), "the instance must start without a queue row");
        var commands = patient.commands();
        try (TwoSessions sessions = sessionsOf(patient)) {
            // When A locks the instance and allocates a sequence, and pauses
            Held<Long> a = sessions.holdOpen(
                    tx -> commands.lockInstance(x, tx).compose(locked -> commands.nextQueueSequence(x, tx)));
            assertEquals(1L, a.ready(), "A must get the first sequence");

            // And B locks the instance, which blocks, and allocates after A commits
            Held<Long> b = sessions.holdOpen(
                    tx -> commands.lockInstance(x, tx).compose(locked -> commands.nextQueueSequence(x, tx)));
            sessions.awaitBlocked(b);
            a.commit();
            assertEquals(2L, b.ready(), "B must get the later sequence");
            b.commit();

            // And C locks, allocates and rolls back, then D locks and allocates
            Held<Long> c = sessions.holdOpen(
                    tx -> commands.lockInstance(x, tx).compose(locked -> commands.nextQueueSequence(x, tx)));
            assertEquals(3L, c.ready(), "C must see the next free sequence");
            c.rollback();
            Held<Long> d = sessions.holdOpen(
                    tx -> commands.lockInstance(x, tx).compose(locked -> commands.nextQueueSequence(x, tx)));

            // Then D gets the sequence C had, because C's allocation left no trace
            assertEquals(3L, d.ready(), "D must get the sequence that C rolled back");
            d.commit();

            // And the queue row was created once and counts past D's sequence
            assertEquals(1L, queueRows(x), "the queue row must exist exactly once");
            assertEquals(4L, nextSequence(x), "the queue must hold the next free sequence");
        }
    }

    @Test
    @DisplayName("try mode never waits and does not collide with definition locks")
    void tryModeNeverWaitsAndDoesNotCollideWithDefinitionLocks() {
        // Given X, whose execution lock session A holds, and a definition lock whose bigint key equals the 64-bit
        // value of X's two integers, which session D holds
        WorkflowInstanceId x = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId y = new WorkflowInstanceId(UUID.randomUUID());
        var commands = patient.commands();
        try (TwoSessions sessions = sessionsOf(patient)) {
            Held<Void> a = sessions.holdOpen(tx -> commands.lockInstance(x, tx));
            a.ready();
            List<long[]> keys = sessions.twoIntegerLocksHeldBy(a.pid());
            assertEquals(1, keys.size(), "taking an execution lock must hold exactly one two-integer advisory lock");
            long definitionKey = (keys.get(0)[0] << 32) | keys.get(0)[1];
            Held<Void> d = sessions.holdOpen(tx -> tx.preparedQuery("SELECT pg_advisory_xact_lock($1)")
                    .execute(Tuple.of(definitionKey))
                    .mapEmpty());
            d.ready(Duration.ofSeconds(5));

            // When B tries X in try mode, and tries instance Y, and A acquires X's lock again
            boolean tryOnX = await(pool.withTransaction(tx -> commands.tryLockInstance(x, tx)), Duration.ofSeconds(5));
            Held<Boolean> onY = sessions.holdOpen(tx -> commands.tryLockInstance(y, tx));
            boolean tryOnY = onY.ready(Duration.ofSeconds(5));
            await(a.run(tx -> commands.lockInstance(x, tx)), Duration.ofSeconds(5));

            // Then the try on X is refused at once, without leaving a waiting lock behind
            assertFalse(tryOnX, "try mode must report a busy instance");
            assertEquals(0L, sessions.ungrantedAdvisoryLocks(), "try mode must never wait for a lock");

            // And the try on Y succeeds and holds Y's two-integer lock
            assertTrue(tryOnY, "try mode must take the lock of an idle instance");
            assertEquals(1, sessions.twoIntegerLocksHeldBy(onY.pid()).size());

            // And the definition lock and X's lock were both granted, and A's re-acquisition added nothing
            assertEquals(1L, sessions.bigintLocksHeldBy(d.pid()), "the definition lock must be granted");
            assertEquals(1, sessions.twoIntegerLocksHeldBy(a.pid()).size(), "re-acquiring must be a no-op");
        }
    }

    @Test
    @DisplayName("a lock timeout fails retryably and restores the caller's timeout")
    void lockTimeoutFailsRetryablyAndRestoresTheCallerTimeout() {
        // Given a lock timeout of 200 ms, X held by session A, and a waiting instance
        WorkflowInstanceId x = impatient.startTick();
        TransactionalWorkflowOperations<SqlClient> operations =
                impatient.component().transactionalOperations();
        UUID marker = UUID.randomUUID();
        try (TwoSessions sessions = sessionsOf(impatient)) {
            Held<Void> a = sessions.holdOpen(tx -> impatient.commands().lockInstance(x, tx));
            a.ready();

            // When B, whose transaction first inserts a marker row, signals X
            Held<Void> b =
                    sessions.holdOpen(tx -> tx.preparedQuery("INSERT INTO execution_lock_marker (id) VALUES ($1)")
                            .execute(Tuple.of(marker))
                            .compose(inserted -> operations.signal(x, "tick", "from-B", "dedup-B", tx)));

            // Then B fails with the retryable persistence error and its marker row does not survive
            b.workFailure();
            Throwable failure = ExecutionLockTestSupport.failureOf(b.outcome());
            WorkflowPersistenceException persistence = assertInstanceOf(WorkflowPersistenceException.class, failure);
            assertTrue(persistence.retryable(), "a lock timeout must be reported as retryable");
            assertEquals(0L, markerRows(marker), "the failed transaction must leave no marker row");

            // And with no holder, a caller whose own timeout is 7 s signals X and still reads 7 s afterwards
            a.commit();
            String afterwards =
                    await(impatient.runner().inTransaction(null, tx -> tx.query("SET LOCAL lock_timeout = '7s'")
                            .execute()
                            .compose(set -> operations.signal(x, "tick", "from-C", "dedup-C", tx))
                            .compose(signalled -> tx.query("SHOW lock_timeout").execute())
                            .map(rows -> rows.iterator().next().getString(0))));
            assertEquals("7s", afterwards, "acquiring the lock must restore the caller's lock timeout");
        }
    }

    @Test
    @DisplayName("opposite-order callers deadlock and one rolls back completely")
    void oppositeOrderCallersDeadlockAndOneRollsBackCompletely() {
        // Given waiting instances X and Y, and transactions T1 and T2 that hold the locks of X and Y respectively
        WorkflowInstanceId x = patient.startTick();
        WorkflowInstanceId y = patient.startTick();
        TransactionalWorkflowOperations<SqlClient> operations =
                patient.component().transactionalOperations();
        try (TwoSessions sessions = sessionsOf(patient)) {
            Held<Void> t1 = sessions.holdOpen(tx -> operations.signal(x, "tick", "from-T1", "dedup-T1", tx));
            Held<Void> t2 = sessions.holdOpen(tx -> operations.signal(y, "tick", "from-T2", "dedup-T2", tx));
            t1.ready();
            t2.ready();
            assertEquals(1, sessions.twoIntegerLocksHeldBy(t1.pid()).size(), "T1 must hold X's execution lock");
            assertEquals(1, sessions.twoIntegerLocksHeldBy(t2.pid()).size(), "T2 must hold Y's execution lock");

            // When T1 cancels Y and T2 cancels X
            Future<Void> t1Cancels = t1.run(tx -> operations.cancel(y, "by T1", tx));
            sessions.awaitBlocked(t1.pid(), t1Cancels);
            Future<Void> t2Cancels = t2.run(tx -> operations.cancel(x, "by T2", tx));
            sessions.awaitAnyComplete(t1Cancels, t2Cancels);

            // Then exactly one fails with the retryable persistence error
            boolean t1Lost = t1Cancels.failed();
            assertTrue(t1Lost ^ t2Cancels.failed(), "exactly one of the two transactions must be the deadlock victim");
            Held<Void> loser = t1Lost ? t1 : t2;
            Held<Void> winner = t1Lost ? t2 : t1;
            Future<Void> winnerCancels = t1Lost ? t2Cancels : t1Cancels;
            Throwable raw = t1Lost ? t1Cancels.cause() : t2Cancels.cause();
            WorkflowPersistenceException reported =
                    assertInstanceOf(WorkflowPersistenceException.class, loser.rollbackBecauseOf(raw));
            assertTrue(reported.retryable(), "a deadlock must be reported as retryable");

            // And the other commits both its writes
            await(winnerCancels);
            winner.commit();
            WorkflowInstanceId winnerSignalled = t1Lost ? y : x;
            WorkflowInstanceId winnerCancelled = t1Lost ? x : y;
            String winnerKey = t1Lost ? "dedup-T2" : "dedup-T1";
            String loserKey = t1Lost ? "dedup-T1" : "dedup-T2";
            assertEquals(1L, signalHistoryCount(winnerSignalled, winnerKey), "the winner's signal must survive");
            assertEquals("WAITING", status(winnerSignalled), "the loser's cancel must leave no trace");
            assertEquals("CANCELLED", status(winnerCancelled), "the winner's cancel must survive");

            // And none of the loser's history entries or status changes survive
            assertEquals(0L, signalHistoryCount(winnerCancelled, loserKey), "the loser's signal must be rolled back");
        }
    }

    // --- Observation ---

    private static long historySequence(WorkflowInstanceId id, String dedupKey) {
        Row row = await(pool.preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1"
                                + " AND entry_type = 'SIGNAL_RECEIVED' AND payload_json::text LIKE $2")
                        .execute(Tuple.of(id.value(), "%" + dedupKey + "%")))
                .iterator()
                .next();
        return row.getLong(0);
    }

    private static List<Long> historySequences(WorkflowInstanceId id) {
        List<Long> sequences = new java.util.ArrayList<>();
        await(pool.preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                        .execute(Tuple.of(id.value())))
                .forEach(row -> sequences.add(row.getLong(0)));
        return sequences;
    }

    private static long signalHistoryCount(WorkflowInstanceId id, String dedupKey) {
        return await(pool.preparedQuery("SELECT count(*) FROM workflow_history WHERE workflow_id = $1"
                                + " AND entry_type = 'SIGNAL_RECEIVED' AND payload_json::text LIKE $2")
                        .execute(Tuple.of(id.value(), "%" + dedupKey + "%")))
                .iterator()
                .next()
                .getLong(0);
    }

    private static String status(WorkflowInstanceId id) {
        return await(pool.preparedQuery("SELECT status FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getString(0);
    }

    private static long queueRows(WorkflowInstanceId id) {
        return await(pool.preparedQuery("SELECT count(*) FROM workflow_command_queues WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
    }

    private static long nextSequence(WorkflowInstanceId id) {
        return await(pool.preparedQuery("SELECT next_seq FROM workflow_command_queues WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
    }

    private static long markerRows(UUID marker) {
        return await(pool.preparedQuery("SELECT count(*) FROM execution_lock_marker WHERE id = $1")
                        .execute(Tuple.of(marker)))
                .iterator()
                .next()
                .getLong(0);
    }
}
