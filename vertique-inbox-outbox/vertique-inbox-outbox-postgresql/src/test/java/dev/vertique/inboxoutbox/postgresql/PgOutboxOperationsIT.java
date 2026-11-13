// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.BASE;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.await;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.ops.OutboxEntryNotFoundException;
import dev.vertique.inboxoutbox.ops.OutboxEntryStateException;
import dev.vertique.inboxoutbox.ops.OutboxTransition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgOutboxOperations} against a real PostgreSQL instance: the retry and discard
 * state changes column by column, the wake-up that is sent only on commit, the interplay with the relay's claim,
 * and the lock handling, each interleaving pinned by an explicit barrier.
 *
 * <p>Two barriers are used. A plain JDBC transaction holds a row lock. A test-only {@code BEFORE UPDATE} trigger
 * parks a statement of the code under test on a session advisory lock the test holds, which stops the statement
 * at a known point while it still holds the row lock it has taken. Neither changes the code under test.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PgOutboxOperationsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_operations")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final String CHANNEL = "transactional_outbox_channel";
    private static final long BARRIER = 4301;

    private static final Set<String> RETRY_CHANGES = Set.of(
            "state", "attempt", "claimed_at", "claimed_by", "last_error", "error_type", "available_at", "updated_at");
    private static final Set<String> DISCARD_CHANGES = Set.of("state", "claimed_at", "claimed_by", "updated_at");

    static Vertx vertx;
    static OutboxFixture fixture;
    static PgOutboxOperations operations;
    static PgOutboxOperations impatient;
    static PgInboxOutboxRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        fixture = new OutboxFixture(db, vertx);
        operations = new PgOutboxOperations(fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty());
        impatient = new PgOutboxOperations(
                fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty(), Duration.ofMillis(300));
        repository = new PgInboxOutboxRepository(fixture.pool(), new PgDbExceptionMapper());
        fixture.exec("CREATE FUNCTION zz_barrier() RETURNS trigger AS $$ BEGIN"
                + " PERFORM pg_advisory_lock(TG_ARGV[0]::bigint); PERFORM pg_advisory_unlock(TG_ARGV[0]::bigint);"
                + " RETURN NEW; END; $$ LANGUAGE plpgsql");
        fixture.exec("CREATE FUNCTION zz_fail_on_commit() RETURNS trigger AS $$ BEGIN"
                + " RAISE EXCEPTION 'secret-commit-failure'; END; $$ LANGUAGE plpgsql");
        fixture.exec("CREATE TABLE zz_observed (lock_timeout TEXT NOT NULL, isolation TEXT NOT NULL)");
        fixture.exec("CREATE FUNCTION zz_observe() RETURNS trigger AS $$ BEGIN"
                + " INSERT INTO zz_observed VALUES (current_setting('lock_timeout'),"
                + " current_setting('transaction_isolation')); RETURN NEW; END; $$ LANGUAGE plpgsql");
    }

    @AfterAll
    static void tearDown() throws Exception {
        fixture.close();
        await(vertx.close());
    }

    @BeforeEach
    void truncate() throws SQLException {
        fixture.reset();
        fixture.exec("TRUNCATE TABLE zz_observed");
    }

    @AfterEach
    void dropTestTriggers() throws SQLException {
        fixture.exec("DROP TRIGGER IF EXISTS zz_barrier ON outbox");
        fixture.exec("DROP TRIGGER IF EXISTS zz_commit_failure ON outbox");
        fixture.exec("DROP TRIGGER IF EXISTS zz_observe ON outbox");
    }

    // --- Helpers ---

    private static Connection holdAdvisoryLock() throws SQLException {
        Connection connection = fixture.connect();
        fixture.exec(connection, "SELECT pg_advisory_lock(" + BARRIER + ")");
        return connection;
    }

    private static void releaseAdvisoryLock(Connection connection) throws SQLException {
        try (connection) {
            fixture.exec(connection, "SELECT pg_advisory_unlock(" + BARRIER + ")");
        }
    }

    private static void installBarrier() throws SQLException {
        fixture.exec("CREATE TRIGGER zz_barrier BEFORE UPDATE ON outbox" + " FOR EACH ROW EXECUTE FUNCTION zz_barrier('"
                + BARRIER + "')");
    }

    private static RelayCapabilities everything() {
        return new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.all()));
    }

    private static void assertOnlyChanged(Map<String, Object> before, Map<String, Object> after, Set<String> changed) {
        assertEquals(before.keySet(), after.keySet());
        for (String column : before.keySet()) {
            if (changed.contains(column)) {
                assertNotEquals(before.get(column), after.get(column), column + " should have changed");
            } else {
                assertEquals(before.get(column), after.get(column), column + " should be untouched");
            }
        }
    }

    private static void assertState(Throwable failure, long id, OutboxEntryState expected, OutboxEntryState current) {
        OutboxEntryStateException state = assertInstanceOf(OutboxEntryStateException.class, failure);
        assertEquals(id, state.entryId());
        assertEquals(expected, state.expected());
        assertEquals(current, state.current());
    }

    // --- Retry ---

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("a dead letter returns to PENDING with its relay state reset and nothing else touched")
        void deadLetterBecomesPending() throws Exception {
            long id = fixture.seed().insert();
            Map<String, Object> before = fixture.outboxRow(id);

            OutboxTransition transition = await(operations.retry(id, OutboxEntryState.DEAD_LETTER));

            assertEquals(
                    new OutboxTransition(id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING, true), transition);
            Map<String, Object> after = fixture.outboxRow(id);
            assertOnlyChanged(before, after, RETRY_CHANGES);
            assertEquals("PENDING", after.get("state"));
            assertEquals(0, after.get("attempt"));
            assertNull(after.get("claimed_at"));
            assertNull(after.get("claimed_by"));
            assertNull(after.get("last_error"));
            assertNull(after.get("error_type"));
            assertEquals(7, after.get("max_attempts"), "the attempt budget is kept");
        }

        @Test
        @DisplayName("a discarded entry returns to PENDING the same way")
        void discardedBecomesPending() throws Exception {
            long id = fixture.seed().state("DISCARDED").insert();
            Map<String, Object> before = fixture.outboxRow(id);

            OutboxTransition transition = await(operations.retry(id, OutboxEntryState.DISCARDED));

            assertEquals(
                    new OutboxTransition(id, OutboxEntryState.DISCARDED, OutboxEntryState.PENDING, true), transition);
            assertOnlyChanged(before, fixture.outboxRow(id), RETRY_CHANGES);
            assertEquals("PENDING", fixture.stateOf(id));
        }

        @Test
        @DisplayName("an entry without a schedule becomes available now and the relay can claim it")
        void unscheduledIsClaimableAtOnce() throws Exception {
            long id = fixture.seed().scheduledNull().insert();

            await(operations.retry(id, OutboxEntryState.DEAD_LETTER));

            assertEquals(
                    1,
                    fixture.longValue(
                            "SELECT count(*) FROM outbox WHERE id = ? AND available_at = updated_at"
                                    + " AND available_at <= NOW() AND updated_at > ?",
                            id,
                            BASE.plusSeconds(100)));
            List<OutboxRecord> claimed = await(repository.claimBatch(10, repository.nodeId(), everything()));
            assertEquals(List.of(id), claimed.stream().map(OutboxRecord::id).toList());
        }

        @Test
        @DisplayName("an entry whose schedule has passed becomes available now")
        void pastScheduleIsClaimableAtOnce() throws Exception {
            long id = fixture.seed().scheduled(BASE.plusSeconds(5)).insert();

            await(operations.retry(id, OutboxEntryState.DEAD_LETTER));

            assertEquals(
                    1,
                    fixture.longValue(
                            "SELECT count(*) FROM outbox WHERE id = ? AND available_at = updated_at"
                                    + " AND available_at <= NOW()",
                            id));
            assertEquals(
                    List.of(id),
                    await(repository.claimBatch(10, repository.nodeId(), everything())).stream()
                            .map(OutboxRecord::id)
                            .toList());
        }

        @Test
        @DisplayName("an entry scheduled for the future keeps waiting for its time and is not claimable")
        void futureScheduleIsNotClaimable() throws Exception {
            Instant scheduled = Instant.now().plus(Duration.ofHours(2));
            long dead = fixture.seed().scheduled(scheduled).insert();
            long discarded =
                    fixture.seed().state("DISCARDED").scheduled(scheduled).insert();

            await(operations.retry(dead, OutboxEntryState.DEAD_LETTER));
            await(operations.retry(discarded, OutboxEntryState.DISCARDED));

            for (long id : List.of(dead, discarded)) {
                Map<String, Object> row = fixture.outboxRow(id);
                assertEquals("PENDING", row.get("state"));
                assertEquals(row.get("scheduled_at"), row.get("available_at"), "available at the scheduled time");
                assertEquals(
                        1, fixture.longValue("SELECT count(*) FROM outbox WHERE id = ? AND available_at > NOW()", id));
                assertEquals(
                        1, fixture.longValue("SELECT count(*) FROM outbox WHERE id = ? AND updated_at <= NOW()", id));
            }
            assertTrue(await(repository.claimBatch(10, repository.nodeId(), everything()))
                    .isEmpty());
        }

        @Test
        @DisplayName("the relay is woken after commit, with the entry id as the payload")
        void notifiesAfterCommit() throws Exception {
            long id = fixture.seed().insert();
            try (OutboxFixture.Listener listener = fixture.listen(CHANNEL)) {
                await(operations.retry(id, OutboxEntryState.DEAD_LETTER));

                List<String> received = listener.poll(5000);

                assertEquals(List.of(String.valueOf(id)), received);
                // a notification is delivered only once its transaction has committed
                assertEquals("PENDING", fixture.stateOf(id));
            }
        }

        @Test
        @DisplayName("a retry that rolls back at commit sends no wake-up and changes nothing")
        void noNotificationWhenCommitFails() throws Exception {
            long id = fixture.seed().insert();
            Map<String, Object> before = fixture.outboxRow(id);
            fixture.exec("CREATE CONSTRAINT TRIGGER zz_commit_failure AFTER UPDATE ON outbox DEFERRABLE INITIALLY"
                    + " DEFERRED FOR EACH ROW EXECUTE FUNCTION zz_fail_on_commit()");
            try (OutboxFixture.Listener listener = fixture.listen(CHANNEL)) {
                Throwable failure = failureOf(operations.retry(id, OutboxEntryState.DEAD_LETTER));

                InboxOutboxPersistenceException persistence =
                        assertInstanceOf(InboxOutboxPersistenceException.class, failure);
                assertFalse(persistence.getMessage().contains("secret-commit-failure"), persistence.getMessage());
                assertTrue(listener.poll(700).isEmpty(), "no notification for a transaction that rolled back");
            }

            assertEquals(before, fixture.outboxRow(id));
        }

        @Test
        @DisplayName("a retry that is refused sends no wake-up")
        void noNotificationWhenRefused() throws Exception {
            long pending = fixture.seed().state("PENDING").insert();
            try (OutboxFixture.Listener listener = fixture.listen(CHANNEL)) {
                failureOf(operations.retry(pending, OutboxEntryState.DEAD_LETTER));
                failureOf(operations.retry(999_999, OutboxEntryState.DEAD_LETTER));
                failureOf(operations.retry(pending, OutboxEntryState.PENDING));

                assertTrue(listener.poll(500).isEmpty());
            }
        }

        @Test
        @DisplayName("every other current state is refused with the state it is in, and the row is untouched")
        void wrongStateIsRefused() throws Exception {
            for (OutboxEntryState expected : List.of(OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED)) {
                for (OutboxEntryState current : OutboxEntryState.values()) {
                    if (current == expected) {
                        continue;
                    }
                    long id = fixture.seed().state(current.name()).insert();
                    Map<String, Object> before = fixture.outboxRow(id);

                    Throwable failure = failureOf(operations.retry(id, expected));

                    assertState(failure, id, expected, current);
                    assertEquals("The outbox entry is not in the expected state", failure.getMessage());
                    assertEquals(before, fixture.outboxRow(id), expected + " vs " + current);
                }
            }
        }

        @Test
        @DisplayName("an expected state that cannot be retried is a validation failure, whatever the entry's state")
        void invalidExpectedState() throws Exception {
            for (OutboxEntryState expected :
                    List.of(OutboxEntryState.PENDING, OutboxEntryState.PROCESSING, OutboxEntryState.PUBLISHED)) {
                long id = fixture.seed().state(expected.name()).insert();
                Map<String, Object> before = fixture.outboxRow(id);

                Throwable failure = failureOf(operations.retry(id, expected));

                assertEquals(
                        "an entry in state " + expected + " cannot be retried",
                        assertInstanceOf(ValidationException.class, failure).getMessage());
                assertEquals(before, fixture.outboxRow(id));
            }
        }

        @Test
        @DisplayName("a second retry after the first succeeded finds the entry PENDING and is refused")
        void secondRetryIsRefused() throws Exception {
            long id = fixture.seed().insert();

            await(operations.retry(id, OutboxEntryState.DEAD_LETTER));
            Throwable second = failureOf(operations.retry(id, OutboxEntryState.DEAD_LETTER));

            assertState(second, id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING);
        }

        @Test
        @DisplayName("an unknown entry is reported as not found")
        void unknownEntry() {
            Throwable failure = failureOf(operations.retry(424_242, OutboxEntryState.DEAD_LETTER));

            OutboxEntryNotFoundException notFound = assertInstanceOf(OutboxEntryNotFoundException.class, failure);
            assertEquals(424_242, notFound.entryId());
        }

        @Test
        @DisplayName("an invalid id or an absent expected state is a validation failure that wins over a missing entry")
        void invalidArguments() {
            Throwable zero = failureOf(operations.retry(0, OutboxEntryState.DEAD_LETTER));
            Throwable negative = failureOf(operations.retry(-1, OutboxEntryState.DEAD_LETTER));
            Throwable noState = failureOf(operations.retry(999_999, null));
            Throwable badState = failureOf(operations.retry(999_999, OutboxEntryState.PUBLISHED));

            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(ValidationException.class, zero).getMessage());
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(ValidationException.class, negative).getMessage());
            assertEquals(
                    "expectedState must not be null",
                    assertInstanceOf(ValidationException.class, noState).getMessage());
            assertInstanceOf(ValidationException.class, badState);
        }
    }

    // --- Discard ---

    @Nested
    @DisplayName("discard")
    class Discard {

        @Test
        @DisplayName("a dead letter becomes DISCARDED, keeping its attempts and failure and clearing its lease")
        void deadLetterBecomesDiscarded() throws Exception {
            long id = fixture.seed().insert();
            Map<String, Object> before = fixture.outboxRow(id);

            OutboxTransition transition = await(operations.discard(id, OutboxEntryState.DEAD_LETTER));

            assertEquals(
                    new OutboxTransition(id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED, true),
                    transition);
            Map<String, Object> after = fixture.outboxRow(id);
            assertOnlyChanged(before, after, DISCARD_CHANGES);
            assertEquals("DISCARDED", after.get("state"));
            assertEquals(5, after.get("attempt"));
            assertEquals("boom: gateway refused", after.get("last_error"));
            assertEquals("java.io.IOException", after.get("error_type"));
            assertNull(after.get("claimed_at"));
            assertNull(after.get("claimed_by"));
            assertEquals(
                    1,
                    fixture.longValue(
                            "SELECT count(*) FROM outbox WHERE id = ? AND updated_at > ?", id, BASE.plusSeconds(100)));
        }

        @Test
        @DisplayName("a pending entry becomes DISCARDED")
        void pendingBecomesDiscarded() throws Exception {
            long id = fixture.seed().state("PENDING").insert();
            Map<String, Object> before = fixture.outboxRow(id);

            OutboxTransition transition = await(operations.discard(id, OutboxEntryState.PENDING));

            assertEquals(
                    new OutboxTransition(id, OutboxEntryState.PENDING, OutboxEntryState.DISCARDED, true), transition);
            assertOnlyChanged(before, fixture.outboxRow(id), DISCARD_CHANGES);
        }

        @Test
        @DisplayName("an already discarded entry succeeds unchanged, whatever state was expected")
        void alreadyDiscardedIsIdempotent() throws Exception {
            for (OutboxEntryState expected :
                    List.of(OutboxEntryState.PENDING, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED)) {
                long id = fixture.seed().state("DISCARDED").insert();
                Map<String, Object> before = fixture.outboxRow(id);

                OutboxTransition transition = await(operations.discard(id, expected));

                assertEquals(
                        new OutboxTransition(id, OutboxEntryState.DISCARDED, OutboxEntryState.DISCARDED, false),
                        transition,
                        expected.name());
                assertEquals(before, fixture.outboxRow(id), "nothing is written, updated_at included");
            }
        }

        @Test
        @DisplayName("an entry being published or already published is refused with its current state")
        void processingAndPublishedAreRefused() throws Exception {
            for (OutboxEntryState current : List.of(OutboxEntryState.PROCESSING, OutboxEntryState.PUBLISHED)) {
                for (OutboxEntryState expected : List.of(OutboxEntryState.PENDING, OutboxEntryState.DEAD_LETTER)) {
                    long id = fixture.seed().state(current.name()).insert();
                    Map<String, Object> before = fixture.outboxRow(id);

                    Throwable failure = failureOf(operations.discard(id, expected));

                    assertState(failure, id, expected, current);
                    assertEquals(before, fixture.outboxRow(id), expected + " vs " + current);
                }
            }
        }

        @Test
        @DisplayName("a pending entry cannot be discarded as a dead letter, nor a dead letter as pending")
        void mismatchedPendingAndDeadLetter() throws Exception {
            long pending = fixture.seed().state("PENDING").insert();
            long dead = fixture.seed().insert();
            Map<String, Object> pendingBefore = fixture.outboxRow(pending);
            Map<String, Object> deadBefore = fixture.outboxRow(dead);

            assertState(
                    failureOf(operations.discard(pending, OutboxEntryState.DEAD_LETTER)),
                    pending,
                    OutboxEntryState.DEAD_LETTER,
                    OutboxEntryState.PENDING);
            assertState(
                    failureOf(operations.discard(dead, OutboxEntryState.PENDING)),
                    dead,
                    OutboxEntryState.PENDING,
                    OutboxEntryState.DEAD_LETTER);

            assertEquals(pendingBefore, fixture.outboxRow(pending));
            assertEquals(deadBefore, fixture.outboxRow(dead));
        }

        @Test
        @DisplayName("an expected state of PROCESSING or PUBLISHED is a validation failure")
        void invalidExpectedState() throws Exception {
            for (OutboxEntryState expected : List.of(OutboxEntryState.PROCESSING, OutboxEntryState.PUBLISHED)) {
                long id = fixture.seed().state(expected.name()).insert();
                Map<String, Object> before = fixture.outboxRow(id);

                Throwable failure = failureOf(operations.discard(id, expected));

                assertEquals(
                        "an entry in state " + expected + " cannot be discarded",
                        assertInstanceOf(ValidationException.class, failure).getMessage());
                assertEquals(before, fixture.outboxRow(id));
            }
        }

        @Test
        @DisplayName("an unknown entry is reported as not found")
        void unknownEntry() {
            Throwable failure = failureOf(operations.discard(424_242, OutboxEntryState.PENDING));

            assertEquals(
                    424_242,
                    assertInstanceOf(OutboxEntryNotFoundException.class, failure)
                            .entryId());
        }

        @Test
        @DisplayName("an invalid id or an absent expected state is a validation failure")
        void invalidArguments() {
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(
                                    ValidationException.class,
                                    failureOf(operations.discard(0, OutboxEntryState.PENDING)))
                            .getMessage());
            assertEquals(
                    "entryId must be positive",
                    assertInstanceOf(
                                    ValidationException.class,
                                    failureOf(operations.discard(-9, OutboxEntryState.PENDING)))
                            .getMessage());
            assertEquals(
                    "expectedState must not be null",
                    assertInstanceOf(ValidationException.class, failureOf(operations.discard(1, null)))
                            .getMessage());
        }

        @Test
        @DisplayName("a discard sends no wake-up")
        void noNotification() throws Exception {
            long id = fixture.seed().insert();
            try (OutboxFixture.Listener listener = fixture.listen(CHANNEL)) {
                await(operations.discard(id, OutboxEntryState.DEAD_LETTER));

                assertTrue(listener.poll(500).isEmpty());
            }
        }

        @Test
        @DisplayName("a discard that rolls back at commit leaves the entry as it was")
        void rollsBackAtCommit() throws Exception {
            long id = fixture.seed().insert();
            Map<String, Object> before = fixture.outboxRow(id);
            fixture.exec("CREATE CONSTRAINT TRIGGER zz_commit_failure AFTER UPDATE ON outbox DEFERRABLE INITIALLY"
                    + " DEFERRED FOR EACH ROW EXECUTE FUNCTION zz_fail_on_commit()");

            Throwable failure = failureOf(operations.discard(id, OutboxEntryState.DEAD_LETTER));

            assertInstanceOf(InboxOutboxPersistenceException.class, failure);
            assertEquals(before, fixture.outboxRow(id));
        }
    }

    // --- Effect on the relay ---

    @Nested
    @DisplayName("effect on the relay")
    class RelayEffect {

        private long pendingOf(String aggregateId) throws SQLException {
            return fixture.seed()
                    .state("PENDING")
                    .aggregate("Order", aggregateId)
                    .scheduledNull()
                    .unclaimed()
                    .attempts(0, 20)
                    .lastError(null)
                    .errorType(null)
                    .available(BASE)
                    .insert();
        }

        @Test
        @DisplayName("discarding the oldest pending entry of an aggregate lets the next one be claimed")
        void discardingTheHeadUnblocksTheNextSibling() throws Exception {
            long headA = pendingOf("agg-a");
            long nextA = pendingOf("agg-a");
            long headB = pendingOf("agg-b");
            long nextB = pendingOf("agg-b");

            await(operations.discard(headA, OutboxEntryState.PENDING));
            List<OutboxRecord> claimed = await(repository.claimBatch(10, repository.nodeId(), everything()));

            assertEquals(
                    Set.of(nextA, headB),
                    claimed.stream().map(OutboxRecord::id).collect(java.util.stream.Collectors.toSet()));
            assertEquals("DISCARDED", fixture.stateOf(headA));
            assertEquals("PENDING", fixture.stateOf(nextB), "an aggregate whose head was not discarded stays blocked");
        }

        @Test
        @DisplayName("a discarded entry is never claimed, however old")
        void discardedIsNeverClaimed() throws Exception {
            long id = pendingOf("agg-x");
            await(operations.discard(id, OutboxEntryState.PENDING));

            assertTrue(await(repository.claimBatch(10, repository.nodeId(), everything()))
                    .isEmpty());
            assertEquals("DISCARDED", fixture.stateOf(id));
        }

        @Test
        @DisplayName("a retried entry is claimable again and is claimed with its attempt count reset")
        void retriedEntryIsClaimed() throws Exception {
            long id = fixture.seed().scheduledNull().aggregate(null, null).insert();
            await(operations.retry(id, OutboxEntryState.DEAD_LETTER));

            List<OutboxRecord> claimed = await(repository.claimBatch(10, repository.nodeId(), everything()));

            assertEquals(1, claimed.size());
            assertEquals(id, claimed.get(0).id());
            assertEquals(0, claimed.get(0).attempt());
            assertEquals("PROCESSING", fixture.stateOf(id));
        }

        @Test
        @DisplayName("a command waiting behind a claim re-reads the entry as PROCESSING and is refused")
        void claimFirstThenDiscard() throws Exception {
            long id = fixture.seed()
                    .state("PENDING")
                    .scheduledNull()
                    .unclaimed()
                    .aggregate(null, null)
                    .available(BASE)
                    .insert();
            installBarrier();
            Connection barrier = holdAdvisoryLock();
            Future<List<OutboxRecord>> claim;
            Future<OutboxTransition> discard;
            try {
                claim = repository.claimBatch(10, repository.nodeId(), everything());
                // the claim has updated the row and is parked before it commits
                fixture.awaitLockWaiters(1);
                discard = operations.discard(id, OutboxEntryState.PENDING);
                // the discard is queued behind the claim's row lock
                fixture.awaitLockWaiters(2);
                assertFalse(discard.isComplete());
            } finally {
                releaseAdvisoryLock(barrier);
            }

            assertEquals(1, await(claim).size());
            assertState(failureOf(discard), id, OutboxEntryState.PENDING, OutboxEntryState.PROCESSING);
            assertEquals("PROCESSING", fixture.stateOf(id));
            assertEquals(repository.nodeId(), fixture.string("SELECT claimed_by FROM outbox WHERE id = ?", id));
        }

        @Test
        @DisplayName("a claim that meets a discard in progress skips the entry and it ends DISCARDED")
        void discardFirstThenClaim() throws Exception {
            long id = fixture.seed()
                    .state("PENDING")
                    .scheduledNull()
                    .unclaimed()
                    .aggregate(null, null)
                    .available(BASE)
                    .insert();
            installBarrier();
            Connection barrier = holdAdvisoryLock();
            Future<OutboxTransition> discard;
            List<OutboxRecord> claimedWhileLocked;
            try {
                discard = operations.discard(id, OutboxEntryState.PENDING);
                // the discard has locked the row and updated it, and is parked before it commits
                fixture.awaitLockWaiters(1);

                claimedWhileLocked = await(repository.claimBatch(10, repository.nodeId(), everything()));
            } finally {
                releaseAdvisoryLock(barrier);
            }

            assertTrue(claimedWhileLocked.isEmpty(), "the locked entry is skipped, not waited for");
            assertEquals(OutboxEntryState.DISCARDED, await(discard).current());
            assertEquals("DISCARDED", fixture.stateOf(id));
            assertNull(fixture.string("SELECT claimed_by FROM outbox WHERE id = ?", id));
            assertTrue(await(repository.claimBatch(10, repository.nodeId(), everything()))
                    .isEmpty());
        }
    }

    // --- Concurrent commands ---

    @Nested
    @DisplayName("concurrent commands")
    class ConcurrentCommands {

        @Test
        @DisplayName(
                "two retries of one dead letter: one succeeds, the other finds it PENDING, and one wake-up is sent")
        void retryRacingRetry() throws Exception {
            long id = fixture.seed().insert();
            installBarrier();
            Connection barrier = holdAdvisoryLock();
            Future<OutboxTransition> first;
            Future<OutboxTransition> second;
            try (OutboxFixture.Listener listener = fixture.listen(CHANNEL)) {
                try {
                    first = operations.retry(id, OutboxEntryState.DEAD_LETTER);
                    fixture.awaitLockWaiters(1);
                    second = operations.retry(id, OutboxEntryState.DEAD_LETTER);
                    fixture.awaitLockWaiters(2);
                } finally {
                    releaseAdvisoryLock(barrier);
                }

                assertTrue(await(first).changed());
                assertState(failureOf(second), id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING);
                assertEquals(List.of(String.valueOf(id)), listener.poll(3000));
            }
            assertEquals("PENDING", fixture.stateOf(id));
        }

        @Test
        @DisplayName("a discard waiting behind a retry finds the entry PENDING and is refused")
        void discardBehindRetry() throws Exception {
            long id = fixture.seed().insert();
            installBarrier();
            Connection barrier = holdAdvisoryLock();
            Future<OutboxTransition> retry;
            Future<OutboxTransition> discard;
            try {
                retry = operations.retry(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(1);
                discard = operations.discard(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(2);
            } finally {
                releaseAdvisoryLock(barrier);
            }

            assertTrue(await(retry).changed());
            assertState(failureOf(discard), id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING);
            assertEquals("PENDING", fixture.stateOf(id));
        }

        @Test
        @DisplayName("a retry waiting behind a discard finds the entry DISCARDED and is refused")
        void retryBehindDiscard() throws Exception {
            long id = fixture.seed().insert();
            installBarrier();
            Connection barrier = holdAdvisoryLock();
            Future<OutboxTransition> discard;
            Future<OutboxTransition> retry;
            try {
                discard = operations.discard(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(1);
                retry = operations.retry(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(2);
            } finally {
                releaseAdvisoryLock(barrier);
            }

            assertTrue(await(discard).changed());
            assertState(failureOf(retry), id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED);
            assertEquals("DISCARDED", fixture.stateOf(id));
            assertEquals(
                    OutboxEntryState.PENDING,
                    await(operations.retry(id, OutboxEntryState.DISCARDED)).current());
        }

        @Test
        @DisplayName("two discards of one dead letter both succeed and only the first changes it")
        void discardRacingDiscard() throws Exception {
            long id = fixture.seed().insert();
            installBarrier();
            Connection barrier = holdAdvisoryLock();
            Future<OutboxTransition> first;
            Future<OutboxTransition> second;
            try {
                first = operations.discard(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(1);
                second = operations.discard(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(2);
            } finally {
                releaseAdvisoryLock(barrier);
            }

            assertEquals(
                    new OutboxTransition(id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED, true),
                    await(first));
            assertEquals(
                    new OutboxTransition(id, OutboxEntryState.DISCARDED, OutboxEntryState.DISCARDED, false),
                    await(second));
        }
    }

    // --- Locks ---

    @Nested
    @DisplayName("locks")
    class Locks {

        @Test
        @DisplayName("a retry waits for a lock another transaction holds and acts on the state it finds afterwards")
        void waitsForTheLock() throws Exception {
            long id = fixture.seed().insert();
            Future<OutboxTransition> retry;
            try (Connection holder = fixture.begin()) {
                fixture.exec(holder, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);

                retry = operations.retry(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(1);

                assertFalse(retry.isComplete(), "the command waits instead of failing");
                holder.commit();
            }

            assertEquals(OutboxEntryState.PENDING, await(retry).current());
        }

        @Test
        @DisplayName("a command waiting for a lock re-reads the state the holder committed")
        void rereadsAfterTheLock() throws Exception {
            long id = fixture.seed().insert();
            Future<OutboxTransition> discard;
            try (Connection holder = fixture.begin()) {
                fixture.exec(holder, "UPDATE outbox SET state = 'PUBLISHED' WHERE id = ?", id);

                discard = operations.discard(id, OutboxEntryState.DEAD_LETTER);
                fixture.awaitLockWaiters(1);
                holder.commit();
            }

            assertState(failureOf(discard), id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PUBLISHED);
            assertEquals("PUBLISHED", fixture.stateOf(id));
        }

        @Test
        @DisplayName("a retry that waits longer than the lock timeout fails with a retryable persistence exception")
        void retryTimesOut() throws Exception {
            long id = fixture.seed().insert();
            Map<String, Object> before = fixture.outboxRow(id);
            try (Connection holder = fixture.begin()) {
                fixture.exec(holder, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);

                Throwable failure = failureOf(impatient.retry(id, OutboxEntryState.DEAD_LETTER));

                InboxOutboxPersistenceException persistence =
                        assertInstanceOf(InboxOutboxPersistenceException.class, failure);
                assertTrue(persistence.retryable());
                assertEquals(
                        "Lock acquisition failure during inbox/outbox operation 'outbox entry retry'"
                                + " [PessimisticLockingFailureException, sqlState=55P03]",
                        persistence.getMessage());
                holder.commit();
            }

            assertEquals(before, fixture.outboxRow(id));
            assertEquals(
                    OutboxEntryState.PENDING,
                    await(impatient.retry(id, OutboxEntryState.DEAD_LETTER)).current());
        }

        @Test
        @DisplayName("a discard that waits longer than the lock timeout fails with a retryable persistence exception")
        void discardTimesOut() throws Exception {
            long id = fixture.seed().insert();
            Map<String, Object> before = fixture.outboxRow(id);
            try (Connection holder = fixture.begin()) {
                fixture.exec(holder, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);

                Throwable failure = failureOf(impatient.discard(id, OutboxEntryState.DEAD_LETTER));

                InboxOutboxPersistenceException persistence =
                        assertInstanceOf(InboxOutboxPersistenceException.class, failure);
                assertTrue(persistence.retryable());
                assertEquals(
                        "Lock acquisition failure during inbox/outbox operation 'outbox entry discard'"
                                + " [PessimisticLockingFailureException, sqlState=55P03]",
                        persistence.getMessage());
                holder.commit();
            }

            assertEquals(before, fixture.outboxRow(id));
        }

        @Test
        @DisplayName("the lock timeout and isolation level apply to the command's transaction only")
        void settingsDoNotLeakIntoThePool() throws Exception {
            long id = fixture.seed().insert();
            await(operations.retry(id, OutboxEntryState.DEAD_LETTER));
            failureOf(impatient.retry(id, OutboxEntryState.DEAD_LETTER));

            for (int i = 0; i < 3; i++) {
                String lockTimeout = await(
                                fixture.pool().query("SHOW lock_timeout").execute())
                        .iterator()
                        .next()
                        .getString(0);
                assertEquals("0", lockTimeout);
            }
        }

        @Test
        @DisplayName(
                "a command sets its own lock timeout and READ COMMITTED even on a server that defaults to SERIALIZABLE")
        void transactionSettings() throws Exception {
            long dead = fixture.seed().insert();
            long other = fixture.seed().insert();
            PgConnectOptions options = fixture.connectOptions();
            options.addProperty("default_transaction_isolation", "serializable");
            Pool serializable = PgBuilder.pool()
                    .with(new PoolOptions().setMaxSize(2))
                    .connectingTo(options)
                    .using(vertx)
                    .build();
            try {
                assertEquals(
                        "serializable",
                        await(serializable
                                        .query("SHOW default_transaction_isolation")
                                        .execute())
                                .iterator()
                                .next()
                                .getString(0));
                fixture.exec("CREATE TRIGGER zz_observe BEFORE UPDATE ON outbox"
                        + " FOR EACH ROW EXECUTE FUNCTION zz_observe()");
                PgOutboxOperations defaults =
                        new PgOutboxOperations(serializable, new PgDbExceptionMapper(), DbSchemas.empty());
                PgOutboxOperations custom = new PgOutboxOperations(
                        serializable, new PgDbExceptionMapper(), DbSchemas.empty(), Duration.ofMillis(750));

                await(defaults.retry(dead, OutboxEntryState.DEAD_LETTER));
                await(custom.discard(other, OutboxEntryState.DEAD_LETTER));
            } finally {
                await(serializable.close());
            }

            assertEquals(
                    "30s|read committed,750ms|read committed",
                    fixture.string("SELECT string_agg(lock_timeout || '|' || isolation, ',' ORDER BY lock_timeout)"
                            + " FROM zz_observed"));
            assertEquals(2, fixture.longValue("SELECT count(*) FROM zz_observed"));
        }
    }

    // --- Failures ---

    @Nested
    @DisplayName("database failures")
    class Failures {

        @Test
        @DisplayName("an update that changes no row after the entry was locked fails closed and sends no wake-up")
        void lostRowFailsClosed() throws Exception {
            long dead = fixture.seed().insert();
            long other = fixture.seed().insert();
            Map<String, Object> deadBefore = fixture.outboxRow(dead);
            Map<String, Object> otherBefore = fixture.outboxRow(other);
            fixture.exec("CREATE FUNCTION zz_skip_update() RETURNS trigger AS $$ BEGIN RETURN NULL; END; $$"
                    + " LANGUAGE plpgsql");
            fixture.exec(
                    "CREATE TRIGGER zz_barrier BEFORE UPDATE ON outbox FOR EACH ROW EXECUTE FUNCTION zz_skip_update()");
            try (OutboxFixture.Listener listener = fixture.listen(CHANNEL)) {
                Throwable retry = failureOf(operations.retry(dead, OutboxEntryState.DEAD_LETTER));
                Throwable discard = failureOf(operations.discard(other, OutboxEntryState.DEAD_LETTER));

                for (Throwable failure : List.of(retry, discard)) {
                    InboxOutboxPersistenceException persistence =
                            assertInstanceOf(InboxOutboxPersistenceException.class, failure);
                    assertFalse(persistence.retryable());
                    assertEquals(
                            "Persistence failure during inbox/outbox operation 'outbox entry command'",
                            persistence.getMessage());
                }
                assertTrue(listener.poll(500).isEmpty());
            } finally {
                fixture.exec("DROP TRIGGER IF EXISTS zz_barrier ON outbox");
                fixture.exec("DROP FUNCTION zz_skip_update()");
            }

            assertEquals(deadBefore, fixture.outboxRow(dead));
            assertEquals(otherBefore, fixture.outboxRow(other));
        }

        @Test
        @DisplayName("a missing table is a non-retryable persistence exception without SQL or driver text")
        void persistenceFailure() {
            PgOutboxOperations broken = new PgOutboxOperations(
                    fixture.pool(), new PgDbExceptionMapper(), new DbSchemas(Map.of("inbox-outbox", "no_such_schema")));

            for (Future<OutboxTransition> command : List.of(
                    broken.retry(1, OutboxEntryState.DEAD_LETTER), broken.discard(1, OutboxEntryState.PENDING))) {
                InboxOutboxPersistenceException persistence =
                        assertInstanceOf(InboxOutboxPersistenceException.class, failureOf(command));
                assertFalse(persistence.retryable());
                String message = persistence.getMessage();
                for (String leaked : List.of("SELECT", "FOR UPDATE", "no_such_schema", "does not exist", "$1")) {
                    assertFalse(message.contains(leaked), "message leaks '" + leaked + "': " + message);
                }
                assertTrue(message.contains("'outbox entry "), message);
            }
        }
    }
}
