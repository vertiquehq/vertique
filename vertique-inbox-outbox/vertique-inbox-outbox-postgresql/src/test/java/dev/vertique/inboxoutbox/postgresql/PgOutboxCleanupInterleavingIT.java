// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.BASE;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.await;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.ops.OutboxEntryNotFoundException;
import dev.vertique.inboxoutbox.ops.OutboxTransition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Interleavings of the dead-letter and discarded cleanups with the operator commands, in both orders, each pinned
 * by an explicit barrier, plus the retention and batch behaviour of both cleanups.
 *
 * <p>A plain JDBC transaction holds a row lock, and test-only triggers park a statement of the code under test on a
 * session advisory lock the test holds: a {@code BEFORE UPDATE} trigger pauses a command after it changed the entry,
 * and a {@code BEFORE DELETE} trigger pauses a cleanup after it locked and before it removed its candidates.
 * Neither changes the code under test.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PgOutboxCleanupInterleavingIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_cleanup_interleaving")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final long COMMAND_BARRIER = 4401;
    private static final long CLEANUP_BARRIER = 4402;
    private static final Instant LONG_AGO = BASE.minusSeconds(86_400L * 400);

    /** One of the two cleanups: the state it removes, how it runs, and the retention the tests pass it. */
    private record Cleanup(
            OutboxEntryState state, int retentionDays, BiFunction<Integer, Integer, Future<Integer>> run) {

        Future<Integer> run(int batchSize) {
            return run.apply(retentionDays, batchSize);
        }
    }

    static Vertx vertx;
    static OutboxFixture fixture;
    static PgOutboxOperations operations;
    static PgInboxOutboxRepository repository;
    static Cleanup deadLetter;
    static Cleanup discarded;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        fixture = new OutboxFixture(db, vertx);
        operations = new PgOutboxOperations(fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty());
        repository = new PgInboxOutboxRepository(fixture.pool(), new PgDbExceptionMapper());
        deadLetter = new Cleanup(OutboxEntryState.DEAD_LETTER, 30, repository::cleanupDeadLetter);
        discarded = new Cleanup(OutboxEntryState.DISCARDED, 7, repository::cleanupDiscarded);
        fixture.exec("CREATE FUNCTION zz_barrier() RETURNS trigger AS $$ BEGIN"
                + " PERFORM pg_advisory_lock(TG_ARGV[0]::bigint); PERFORM pg_advisory_unlock(TG_ARGV[0]::bigint);"
                + " IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
        await(vertx.close());
    }

    @BeforeEach
    void truncate() throws SQLException {
        fixture.reset();
    }

    @AfterEach
    void dropBarriers() throws SQLException {
        fixture.exec("DROP TRIGGER IF EXISTS zz_command_barrier ON outbox");
        fixture.exec("DROP TRIGGER IF EXISTS zz_cleanup_barrier ON outbox");
    }

    private static long expired(Cleanup cleanup) throws SQLException {
        return fixture.seed().state(cleanup.state().name()).updated(LONG_AGO).insert();
    }

    private static boolean exists(long id) {
        try {
            return fixture.outboxExists(id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection holdAdvisoryLock(long key) throws SQLException {
        Connection connection = fixture.connect();
        fixture.exec(connection, "SELECT pg_advisory_lock(" + key + ")");
        return connection;
    }

    private static void releaseAdvisoryLock(Connection connection, long key) throws SQLException {
        try (connection) {
            fixture.exec(connection, "SELECT pg_advisory_unlock(" + key + ")");
        }
    }

    // --- Cleanup behaviour ---

    @Test
    @DisplayName("each cleanup skips a candidate another transaction has locked, without waiting, and takes it later")
    void cleanupSkipsLockedCandidate() throws Exception {
        for (Cleanup cleanup : List.of(deadLetter, discarded)) {
            fixture.reset();
            long locked = expired(cleanup);
            long free = expired(cleanup);
            try (Connection holder = fixture.begin()) {
                fixture.exec(holder, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", locked);

                int removed = await(cleanup.run(100));

                assertEquals(1, removed, cleanup.state().name());
                assertTrue(exists(locked));
                assertFalse(exists(free));
                holder.commit();
            }

            assertEquals(1, await(cleanup.run(100)), cleanup.state().name());
            assertFalse(exists(locked));
        }
    }

    @Test
    @DisplayName("each cleanup removes only expired entries of its own state, within the batch")
    void cleanupBoundsAndAges() throws Exception {
        for (Cleanup cleanup : List.of(deadLetter, discarded)) {
            fixture.reset();
            Cleanup other = cleanup == deadLetter ? discarded : deadLetter;
            List<Long> expiredIds = List.of(expired(cleanup), expired(cleanup), expired(cleanup));
            long fresh = fixture.seed()
                    .state(cleanup.state().name())
                    .updated(Instant.now())
                    .insert();
            long otherState = expired(other);
            long oldPending = fixture.seed().state("PENDING").updated(LONG_AGO).insert();
            long oldProcessing =
                    fixture.seed().state("PROCESSING").updated(LONG_AGO).insert();

            assertEquals(2, await(cleanup.run(2)), cleanup.state().name());

            // the batch is unordered, so which two go is not specified; exactly two do
            assertEquals(
                    1,
                    expiredIds.stream()
                            .filter(PgOutboxCleanupInterleavingIT::exists)
                            .count());
            assertTrue(exists(fresh));
            assertTrue(exists(otherState), "the other cleanup's state is untouched");
            assertTrue(exists(oldPending));
            assertTrue(exists(oldProcessing));
        }
    }

    @Test
    @DisplayName("the retention is counted in whole days from the last change: just inside is kept, just outside goes")
    void retentionBoundary() throws Exception {
        for (Cleanup cleanup : List.of(deadLetter, discarded)) {
            fixture.reset();
            int days = cleanup.retentionDays();
            long inside = fixture.seed().state(cleanup.state().name()).insert();
            long outside = fixture.seed().state(cleanup.state().name()).insert();
            fixture.exec(
                    "UPDATE outbox SET updated_at = NOW() - ((? - 1) * interval '1 day') - interval '1 hour'"
                            + " WHERE id = ?",
                    days,
                    inside);
            fixture.exec(
                    "UPDATE outbox SET updated_at = NOW() - (? * interval '1 day') - interval '1 hour'"
                            + " WHERE id = ?",
                    days,
                    outside);

            assertEquals(1, await(cleanup.run(100)), cleanup.state().name());

            assertTrue(exists(inside));
            assertFalse(exists(outside));
        }
    }

    @Test
    @DisplayName("a backlog drains in batches until nothing is left")
    void drainsInBatches() throws Exception {
        for (int i = 0; i < 5; i++) {
            expired(discarded);
        }

        assertEquals(2, await(repository.cleanupDiscarded(7, 2)));
        assertEquals(2, await(repository.cleanupDiscarded(7, 2)));
        assertEquals(1, await(repository.cleanupDiscarded(7, 2)));
        assertEquals(0, await(repository.cleanupDiscarded(7, 2)));
        assertEquals(0, fixture.longValue("SELECT count(*) FROM outbox"));
    }

    @Test
    @DisplayName("the discarded retention runs from the moment of the discard, not from the original failure")
    void retentionRunsFromTheDiscard() throws Exception {
        long id = expired(deadLetter);

        await(operations.discard(id, OutboxEntryState.DEAD_LETTER));

        assertEquals(0, await(repository.cleanupDiscarded(7, 100)));
        assertTrue(exists(id));
        fixture.exec("UPDATE outbox SET updated_at = ? WHERE id = ?", LONG_AGO, id);
        assertEquals(1, await(repository.cleanupDiscarded(7, 100)));
        assertFalse(exists(id));
    }

    /**
     * Pins the outcome, not the mechanism: this passes whether or not the cleanup skips locked rows, because the
     * entry is already {@code PENDING} when the cleanup runs. The locking proof is the barrier tests below, where
     * the cleanup runs while a command or a held lock owns the entry.
     */
    @Test
    @DisplayName("a requeued entry is never deleted by either cleanup, even once it is old")
    void requeuedEntryIsNeverCleanedUp() throws Exception {
        long fromDeadLetter = expired(deadLetter);
        long fromDiscarded = expired(discarded);
        await(operations.retry(fromDeadLetter, OutboxEntryState.DEAD_LETTER));
        await(operations.retry(fromDiscarded, OutboxEntryState.DISCARDED));
        fixture.exec("UPDATE outbox SET updated_at = ?, available_at = ?", LONG_AGO, LONG_AGO);

        assertEquals(0, await(repository.cleanupDeadLetter(30, 100)));
        assertEquals(0, await(repository.cleanupDiscarded(7, 100)));

        assertTrue(exists(fromDeadLetter));
        assertTrue(exists(fromDiscarded));
        assertEquals("PENDING", fixture.stateOf(fromDeadLetter));
        assertEquals("PENDING", fixture.stateOf(fromDiscarded));
    }

    @Test
    @DisplayName("a failing discarded cleanup reports a data access failure for its operation")
    void discardedCleanupFailure() {
        PgInboxOutboxRepository broken = new PgInboxOutboxRepository(
                fixture.pool(), new PgDbExceptionMapper(), new DbSchemas(Map.of("inbox-outbox", "no_such_schema")));

        Throwable failure = failureOf(broken.cleanupDiscarded(7, 100));

        assertTrue(assertInstanceOf(DataAccessException.class, failure)
                .getMessage()
                .contains("cleanupDiscarded"));
    }

    // --- Interleavings with the commands ---

    @Test
    @DisplayName("command first: a cleanup that runs while a retry holds the entry skips it and the retry wins")
    void retryFirstThenCleanup() throws Exception {
        for (Cleanup cleanup : List.of(deadLetter, discarded)) {
            fixture.reset();
            long id = expired(cleanup);
            fixture.exec("CREATE TRIGGER zz_command_barrier BEFORE UPDATE ON outbox"
                    + " FOR EACH ROW EXECUTE FUNCTION zz_barrier('" + COMMAND_BARRIER + "')");
            Connection barrier = holdAdvisoryLock(COMMAND_BARRIER);
            Future<OutboxTransition> retry;
            try {
                retry = operations.retry(id, cleanup.state());
                // the retry changed the entry and waits to commit, still holding the entry lock
                fixture.awaitLockWaiters(1);

                assertEquals(0, await(cleanup.run(100)), "the locked, requeued entry is skipped");
            } finally {
                releaseAdvisoryLock(barrier, COMMAND_BARRIER);
                fixture.exec("DROP TRIGGER IF EXISTS zz_command_barrier ON outbox");
            }

            assertEquals(OutboxEntryState.PENDING, await(retry).current());
            assertEquals("PENDING", fixture.stateOf(id));
            assertEquals(0, await(cleanup.run(100)));
            assertTrue(exists(id));
        }
    }

    @Test
    @DisplayName("command first: a cleanup that runs while a discard holds a dead letter skips it")
    void discardFirstThenDeadLetterCleanup() throws Exception {
        long id = expired(deadLetter);
        fixture.exec("CREATE TRIGGER zz_command_barrier BEFORE UPDATE ON outbox"
                + " FOR EACH ROW EXECUTE FUNCTION zz_barrier('" + COMMAND_BARRIER + "')");
        Connection barrier = holdAdvisoryLock(COMMAND_BARRIER);
        Future<OutboxTransition> discard;
        try {
            discard = operations.discard(id, OutboxEntryState.DEAD_LETTER);
            fixture.awaitLockWaiters(1);

            assertEquals(0, await(deadLetter.run(100)), "the locked entry is skipped, not waited for");
        } finally {
            releaseAdvisoryLock(barrier, COMMAND_BARRIER);
        }

        assertEquals(OutboxEntryState.DISCARDED, await(discard).current());
        assertTrue(exists(id), "the discarded entry waits out its own retention");
        assertEquals(0, await(deadLetter.run(100)));
        assertEquals(0, await(discarded.run(100)), "its retention starts now");
    }

    @Test
    @DisplayName("cleanup first: a retry waiting behind the cleanup's delete re-evaluates and reports not found")
    void cleanupFirstThenRetry() throws Exception {
        assertCommandBehindCleanup(deadLetter, id -> operations.retry(id, OutboxEntryState.DEAD_LETTER));
        assertCommandBehindCleanup(discarded, id -> operations.retry(id, OutboxEntryState.DISCARDED));
    }

    @Test
    @DisplayName("cleanup first: a discard waiting behind the cleanup's delete re-evaluates and reports not found")
    void cleanupFirstThenDiscard() throws Exception {
        assertCommandBehindCleanup(deadLetter, id -> operations.discard(id, OutboxEntryState.DEAD_LETTER));
    }

    private void assertCommandBehindCleanup(Cleanup cleanup, LongFunction<Future<OutboxTransition>> issue)
            throws Exception {
        fixture.reset();
        long id = expired(cleanup);
        fixture.exec("CREATE TRIGGER zz_cleanup_barrier BEFORE DELETE ON outbox"
                + " FOR EACH ROW EXECUTE FUNCTION zz_barrier('" + CLEANUP_BARRIER + "')");
        Connection barrier = holdAdvisoryLock(CLEANUP_BARRIER);
        Future<Integer> running;
        Future<OutboxTransition> command;
        try {
            running = cleanup.run(100);
            // the cleanup locked its candidate and is parked just before removing it
            fixture.awaitLockWaiters(1);
            command = issue.apply(id);
            // the command is now queued behind the cleanup's row lock
            fixture.awaitLockWaiters(2);
            assertFalse(command.isComplete());
        } finally {
            releaseAdvisoryLock(barrier, CLEANUP_BARRIER);
            fixture.exec("DROP TRIGGER IF EXISTS zz_cleanup_barrier ON outbox");
        }

        assertEquals(1, await(running));
        Throwable failure = failureOf(command);

        assertEquals(
                id,
                assertInstanceOf(OutboxEntryNotFoundException.class, failure).entryId());
        assertFalse(exists(id));
    }

    @Test
    @DisplayName("a discarded entry is outside the dead-letter and published cleanups")
    void cleanupsOnlyRemoveTheirOwnState() throws Exception {
        long id = fixture.seed()
                .state("DISCARDED")
                .updated(Instant.now().minus(Duration.ofDays(2)))
                .insert();

        assertEquals(0, await(repository.cleanupDeadLetter(0, 100)), "a discarded entry is not a dead letter");
        assertEquals(0, await(repository.cleanupPublished(0, 100)));
        assertTrue(exists(id));
        assertEquals(1, await(repository.cleanupDiscarded(1, 100)));
    }
}
