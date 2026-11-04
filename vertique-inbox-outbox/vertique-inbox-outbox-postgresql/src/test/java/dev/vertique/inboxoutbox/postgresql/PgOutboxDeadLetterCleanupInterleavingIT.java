// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.BASE;
import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.await;
import static dev.vertique.inboxoutbox.postgresql.DeadLetterFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import dev.vertique.inboxoutbox.admin.OutboxAdminActor;
import dev.vertique.inboxoutbox.admin.OutboxAdminCommand;
import dev.vertique.inboxoutbox.admin.OutboxAdminReceipt;
import dev.vertique.inboxoutbox.admin.OutboxDeadLetterNotFoundException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Interleavings of the dead-letter cleanup and the administration commands, in both orders, each pinned by an
 * explicit barrier.
 *
 * <p>Two barriers are used. A plain JDBC transaction holds a row lock. A test-only trigger parks a statement of the
 * code under test on a session advisory lock the test holds, which stops the statement at a known point while it
 * still holds the row locks it has taken: a {@code BEFORE UPDATE} trigger on the receipt table pauses a command after
 * it changed the entry and before it completes its receipt, and a {@code BEFORE DELETE} trigger on the outbox table
 * pauses the cleanup after it locked and before it removed its candidates. Neither changes the code under test.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgOutboxDeadLetterCleanupInterleavingIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_dead_letter_interleaving")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final long COMMAND_BARRIER = 4201;
    private static final long CLEANUP_BARRIER = 4202;
    private static final OutboxAdminActor ACTOR = new OutboxAdminActor("user", "alice");
    private static final Instant LONG_AGO = BASE.minusSeconds(86_400L * 400);

    static Vertx vertx;
    static DeadLetterFixture fixture;
    static PgOutboxDeadLetterService service;
    static PgInboxOutboxRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        fixture = new DeadLetterFixture(db, vertx);
        service = new PgOutboxDeadLetterService(
                fixture.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                InboxOutboxAdminConfig.builder().build());
        repository = new PgInboxOutboxRepository(fixture.pool(), new PgDbExceptionMapper());
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
        fixture.exec("DROP TRIGGER IF EXISTS zz_command_barrier ON outbox_admin_operations");
        fixture.exec("DROP TRIGGER IF EXISTS zz_cleanup_barrier ON outbox");
    }

    private static OutboxAdminCommand command(long entryId, long revision) {
        return new OutboxAdminCommand(UUID.randomUUID(), entryId, revision, "reason", ACTOR);
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

    private static long expiredDeadLetter() throws SQLException {
        return fixture.seed().updated(LONG_AGO).insert();
    }

    @Test
    @DisplayName("cleanup skips a candidate another transaction has locked, without waiting, and takes it later")
    void cleanupSkipsLockedCandidate() throws Exception {
        long locked = expiredDeadLetter();
        long free = expiredDeadLetter();
        try (Connection holder = fixture.begin()) {
            fixture.exec(holder, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", locked);

            int removed = await(repository.cleanupDeadLetter(30, 100));

            assertEquals(1, removed);
            assertTrue(fixture.outboxExists(locked));
            assertFalse(fixture.outboxExists(free));
            holder.commit();
        }

        assertEquals(1, await(repository.cleanupDeadLetter(30, 100)));
        assertFalse(fixture.outboxExists(locked));
    }

    @Test
    @DisplayName("cleanup only removes dead letters older than the cutoff, in id order, within the batch")
    void cleanupBoundsAndAges() throws Exception {
        long first = expiredDeadLetter();
        long second = expiredDeadLetter();
        long third = expiredDeadLetter();
        long fresh = fixture.seed().updated(Instant.now()).insert();
        long oldPending = fixture.seed().state("PENDING").updated(LONG_AGO).insert();

        assertEquals(2, await(repository.cleanupDeadLetter(30, 2)));

        assertFalse(fixture.outboxExists(first));
        assertFalse(fixture.outboxExists(second));
        assertTrue(fixture.outboxExists(third));
        assertTrue(fixture.outboxExists(fresh));
        assertTrue(fixture.outboxExists(oldPending));
    }

    @Test
    @DisplayName("a requeued entry is never deleted by cleanup, even once it is old")
    void requeuedEntryIsNeverCleanedUp() throws Exception {
        long id = expiredDeadLetter();
        await(service.retry(command(id, 0)));

        assertEquals(0, await(repository.cleanupDeadLetter(30, 100)));
        fixture.exec("UPDATE outbox SET updated_at = ?, available_at = ? WHERE id = ?", LONG_AGO, LONG_AGO, id);

        assertEquals(0, await(repository.cleanupDeadLetter(30, 100)));
        assertTrue(fixture.outboxExists(id));
        assertEquals("PENDING", fixture.stateOf(id));
    }

    @Test
    @DisplayName("command first: cleanup that runs while a retry holds the entry skips it and the retry wins")
    void commandFirstThenCleanup() throws Exception {
        long id = expiredDeadLetter();
        fixture.exec("CREATE TRIGGER zz_command_barrier BEFORE UPDATE ON outbox_admin_operations"
                + " FOR EACH ROW EXECUTE FUNCTION zz_barrier('" + COMMAND_BARRIER + "')");
        Connection barrier = holdAdvisoryLock(COMMAND_BARRIER);
        Future<OutboxAdminReceipt> retry;
        try {
            retry = service.retry(command(id, 0));
            // the retry changed the entry and now waits to complete its receipt, still holding the entry lock
            fixture.awaitLockWaiters(1);

            assertEquals(0, await(repository.cleanupDeadLetter(30, 100)), "the locked, requeued entry is skipped");
        } finally {
            releaseAdvisoryLock(barrier, COMMAND_BARRIER);
        }

        OutboxAdminReceipt receipt = await(retry);

        assertEquals(1, receipt.resultRevision().getAsLong());
        assertEquals("PENDING", fixture.stateOf(id));
        assertEquals(0, await(repository.cleanupDeadLetter(30, 100)));
        assertTrue(fixture.outboxExists(id));
    }

    @Test
    @DisplayName("cleanup first: a retry waiting behind the cleanup's delete re-evaluates and reports not found")
    void cleanupFirstThenRetry() throws Exception {
        assertCommandBehindCleanup(id -> service.retry(command(id, 0)));
    }

    @Test
    @DisplayName("cleanup first: a discard waiting behind the cleanup's delete re-evaluates and reports not found")
    void cleanupFirstThenDiscard() throws Exception {
        assertCommandBehindCleanup(id -> service.discard(command(id, 0)));
    }

    private void assertCommandBehindCleanup(java.util.function.LongFunction<Future<OutboxAdminReceipt>> issue)
            throws Exception {
        long id = expiredDeadLetter();
        fixture.exec("CREATE TRIGGER zz_cleanup_barrier BEFORE DELETE ON outbox"
                + " FOR EACH ROW EXECUTE FUNCTION zz_barrier('" + CLEANUP_BARRIER + "')");
        Connection barrier = holdAdvisoryLock(CLEANUP_BARRIER);
        Future<Integer> cleanup;
        Future<OutboxAdminReceipt> command;
        try {
            cleanup = repository.cleanupDeadLetter(30, 100);
            // the cleanup locked its candidate and is parked just before removing it
            fixture.awaitLockWaiters(1);
            command = issue.apply(id);
            // the command is now queued behind the cleanup's row lock
            fixture.awaitLockWaiters(2);
        } finally {
            releaseAdvisoryLock(barrier, CLEANUP_BARRIER);
        }

        assertEquals(1, await(cleanup));
        Throwable failure = failureOf(command);

        assertEquals(
                id,
                assertInstanceOf(OutboxDeadLetterNotFoundException.class, failure)
                        .entryId());
        assertFalse(fixture.outboxExists(id));
        assertEquals(0, fixture.receipts(), "the rejected command leaves no receipt");
    }

    @Test
    @DisplayName("relay writes never touch the administrative revision")
    void relayWritesPreserveRevision() throws Exception {
        long id = fixture.seed()
                .state("PENDING")
                .destinationType("SERVICE")
                .destination("relay.target")
                .revision(5)
                .insert();
        RelayCapabilities capabilities = new RelayCapabilities(
                Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> java.util.Set.of("relay.target"))));
        String node = repository.nodeId();

        List<OutboxRecord> claimed = await(repository.claimBatch(10, node, capabilities));
        assertEquals(1, claimed.size());
        assertEquals(5, fixture.revisionOf(id));

        assertTrue(await(repository.markRetry(id, node, 1, Instant.now().minusSeconds(1), "e", "E")));
        assertEquals(5, fixture.revisionOf(id));

        assertEquals(1, await(repository.claimBatch(10, node, capabilities)).size());
        fixture.exec("UPDATE outbox SET claimed_at = ? WHERE id = ?", LONG_AGO, id);
        await(repository.reclaimStale(Duration.ofMillis(1)));
        assertEquals("PENDING", fixture.stateOf(id));
        assertEquals(5, fixture.revisionOf(id));

        assertEquals(1, await(repository.claimBatch(10, node, capabilities)).size());
        assertTrue(await(repository.markDeadLetter(id, node, "final", "E")));
        assertEquals("DEAD_LETTER", fixture.stateOf(id));
        assertEquals(5, fixture.revisionOf(id));

        // the administrative requeue then continues from the preserved revision
        OutboxAdminReceipt receipt = await(service.retry(command(id, 5)));
        assertEquals(6, receipt.resultRevision().getAsLong());
        assertEquals(1, await(repository.claimBatch(10, node, capabilities)).size());
        assertEquals(6, fixture.revisionOf(id));
    }
}
