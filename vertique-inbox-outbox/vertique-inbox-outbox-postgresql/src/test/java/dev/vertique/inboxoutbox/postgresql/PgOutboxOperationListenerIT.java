// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.BASE;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.await;
import static dev.vertique.inboxoutbox.postgresql.OutboxFixture.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import dev.vertique.inboxoutbox.ops.OutboxCommandFailure;
import dev.vertique.inboxoutbox.ops.OutboxEntryChange;
import dev.vertique.inboxoutbox.ops.OutboxEntryStateException;
import dev.vertique.inboxoutbox.ops.OutboxOperationListener;
import dev.vertique.inboxoutbox.ops.OutboxOperationType;
import dev.vertique.inboxoutbox.ops.OutboxTransition;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the reports {@link PgOutboxOperations} makes to {@link OutboxOperationListener}s against a
 * real PostgreSQL instance: a committed command is reported only once the row change is visible from another
 * connection, a failed or rolled-back command is reported as failed and never as committed, the security snapshot is
 * the one bound when the call was made, and a listener that throws changes nothing for the caller.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PgOutboxOperationListenerIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_operation_listener")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final long BARRIER = 4302;
    private static final Clock CLOCK = Clock.fixed(BASE, ZoneOffset.UTC);

    static Vertx vertx;
    static OutboxFixture fixture;
    static final ContextHolder holder = new DefaultContextHolder();

    /** Keeps every report, and what the database showed at the moment the report arrived. */
    private static final class Recorder implements OutboxOperationListener {

        final List<OutboxEntryChange> changes = new CopyOnWriteArrayList<>();
        final List<String> stateSeenAtChange = new CopyOnWriteArrayList<>();
        final List<OutboxCommandFailure> failures = new CopyOnWriteArrayList<>();

        @Override
        public void onOutboxEntryChanged(OutboxEntryChange change) {
            try {
                // A different connection than the command's: the row change is committed or it is not visible.
                stateSeenAtChange.add(fixture.stateOf(change.entryId()));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            changes.add(change);
        }

        @Override
        public void onOutboxCommandFailed(OutboxCommandFailure failure) {
            failures.add(failure);
        }

        /** Waits until the report count settles at the expectation, then checks nothing more arrives. */
        void awaitReports(int expectedChanges, int expectedFailures) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((changes.size() < expectedChanges || failures.size() < expectedFailures)
                    && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            TimeUnit.MILLISECONDS.sleep(150);
            assertEquals(expectedChanges, changes.size(), "committed reports");
            assertEquals(expectedFailures, failures.size(), "failure reports");
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        fixture = new OutboxFixture(db, vertx);
        fixture.exec("CREATE FUNCTION zz_barrier() RETURNS trigger AS $$ BEGIN"
                + " PERFORM pg_advisory_lock(TG_ARGV[0]::bigint); PERFORM pg_advisory_unlock(TG_ARGV[0]::bigint);"
                + " RETURN NEW; END; $$ LANGUAGE plpgsql");
        fixture.exec("CREATE FUNCTION zz_fail_on_commit() RETURNS trigger AS $$ BEGIN"
                + " RAISE EXCEPTION 'secret-commit-failure'; END; $$ LANGUAGE plpgsql");
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
    void dropTestTriggers() throws SQLException {
        fixture.exec("DROP TRIGGER IF EXISTS zz_barrier ON outbox");
        fixture.exec("DROP TRIGGER IF EXISTS zz_commit_failure ON outbox");
    }

    // --- Helpers ---

    private static PgOutboxOperations operations(OutboxOperationListener... listeners) {
        return new PgOutboxOperations(
                fixture.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                holder,
                new LinkedHashSet<>(List.of(listeners)),
                CLOCK);
    }

    private static PgOutboxOperations impatient(OutboxOperationListener... listeners) {
        return new PgOutboxOperations(
                fixture.pool(),
                new PgDbExceptionMapper(),
                DbSchemas.empty(),
                holder,
                new LinkedHashSet<>(List.of(listeners)),
                CLOCK,
                Duration.ofMillis(300));
    }

    private static SecurityContext securityContext(String principalId) {
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, principalId, Map.of());
        return SecurityContexts.assemble(
                new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty()),
                new AuthenticationState(
                        DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                AuthorizationClaims.empty(),
                Optional.empty());
    }

    /** Makes the call on a fresh duplicated context with the security context bound, the way a request would. */
    private static <T> Future<T> callWith(SecurityContext security, Supplier<Future<T>> call) throws Exception {
        CompletableFuture<Future<T>> made = new CompletableFuture<>();
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        context.runOnContext(v -> {
            try (ContextHolder.Scope scope = holder.bind(SecurityContext.class, security)) {
                made.complete(call.get());
            } catch (Throwable t) {
                made.completeExceptionally(t);
            }
        });
        return made.get(10, TimeUnit.SECONDS);
    }

    private static void installBarrier() throws SQLException {
        fixture.exec("CREATE TRIGGER zz_barrier BEFORE UPDATE ON outbox FOR EACH ROW EXECUTE FUNCTION zz_barrier('"
                + BARRIER + "')");
    }

    private static void installCommitFailure() throws SQLException {
        fixture.exec("CREATE CONSTRAINT TRIGGER zz_commit_failure AFTER UPDATE ON outbox DEFERRABLE INITIALLY"
                + " DEFERRED FOR EACH ROW EXECUTE FUNCTION zz_fail_on_commit()");
    }

    private static OutboxOperationListener thrower(Throwable toThrow) {
        return new OutboxOperationListener() {
            @Override
            public void onOutboxEntryChanged(OutboxEntryChange change) {
                sneaky(toThrow);
            }

            @Override
            public void onOutboxCommandFailed(OutboxCommandFailure failure) {
                sneaky(toThrow);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable throwable) throws T {
        throw (T) throwable;
    }

    // --- Committed commands ---

    @Test
    @DisplayName("a committed retry is reported once, after the row change is visible, with the call's snapshot")
    void committedRetry() throws Exception {
        // Given a dead letter, a listener and a security context bound when the call is made
        long id = fixture.seed().insert();
        Recorder recorder = new Recorder();
        SecurityContext security = securityContext("operator-retry");

        // When the retry is called and completes
        OutboxTransition transition =
                await(callWith(security, () -> operations(recorder).retry(id, OutboxEntryState.DEAD_LETTER)));

        // Then the listener is told exactly once, after the commit, and the caller's result is unchanged
        assertEquals(
                new OutboxTransition(id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING, true), transition);
        recorder.awaitReports(1, 0);
        OutboxEntryChange change = recorder.changes.get(0);
        assertEquals(OutboxOperationType.RETRY, change.operation());
        assertEquals(id, change.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, change.previous());
        assertEquals(OutboxEntryState.PENDING, change.current());
        assertTrue(change.changed());
        assertEquals(BASE, change.occurredAt());
        assertNotNull(change.callId());
        assertSame(security.identity(), change.security().identity());
        assertSame(security.authentication(), change.security().authentication());
        assertEquals(List.of("PENDING"), recorder.stateSeenAtChange, "the change was visible to another connection");
    }

    @Test
    @DisplayName("a committed discard is reported once, after the row change is visible, with the call's snapshot")
    void committedDiscard() throws Exception {
        long id = fixture.seed().insert();
        Recorder recorder = new Recorder();
        SecurityContext security = securityContext("operator-discard");

        OutboxTransition transition =
                await(callWith(security, () -> operations(recorder).discard(id, OutboxEntryState.DEAD_LETTER)));

        assertEquals(
                new OutboxTransition(id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.DISCARDED, true), transition);
        recorder.awaitReports(1, 0);
        OutboxEntryChange change = recorder.changes.get(0);
        assertEquals(OutboxOperationType.DISCARD, change.operation());
        assertEquals(id, change.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, change.previous());
        assertEquals(OutboxEntryState.DISCARDED, change.current());
        assertTrue(change.changed());
        assertEquals(BASE, change.occurredAt());
        assertSame(security.identity(), change.security().identity());
        assertEquals(List.of("DISCARDED"), recorder.stateSeenAtChange);
    }

    @Test
    @DisplayName("a repeated discard of a discarded entry is still reported, with changed false")
    void repeatedDiscard() throws Exception {
        long id = fixture.seed().state("DISCARDED").insert();
        Recorder recorder = new Recorder();

        await(operations(recorder).discard(id, OutboxEntryState.DEAD_LETTER));

        recorder.awaitReports(1, 0);
        OutboxEntryChange change = recorder.changes.get(0);
        assertEquals(OutboxEntryState.DISCARDED, change.previous());
        assertEquals(OutboxEntryState.DISCARDED, change.current());
        assertEquals(false, change.changed());
    }

    @Test
    @DisplayName("a command with no security context bound behaves as before and both reports carry a null snapshot")
    void noSecurityContext() throws Exception {
        // Given a system caller, such as a job, on a Vert.x context with no SecurityContext bound
        long id = fixture.seed().insert();
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(recorder);
        CompletableFuture<List<Future<OutboxTransition>>> made = new CompletableFuture<>();
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        // When it retries an entry and discards an unknown one
        context.runOnContext(v -> made.complete(List.of(
                operations.retry(id, OutboxEntryState.DEAD_LETTER),
                operations.discard(999, OutboxEntryState.PENDING))));
        List<Future<OutboxTransition>> results = made.get(10, TimeUnit.SECONDS);

        // Then the results are what they are without listeners, and both reports have no snapshot
        assertEquals(OutboxEntryState.PENDING, await(results.get(0)).current());
        assertEquals(
                "OutboxEntryNotFoundException",
                failureOf(results.get(1)).getClass().getSimpleName());
        recorder.awaitReports(1, 1);
        assertNull(recorder.changes.get(0).security());
        assertNull(recorder.failures.get(0).security());
        assertEquals("PENDING", fixture.stateOf(id));
    }

    @Test
    @DisplayName(
            "the snapshot is the one bound when the call was made, even if the holder is rebound before it completes")
    void snapshotIsTakenAtCallTime() throws Exception {
        // Given a retry that is parked inside its transaction
        long id = fixture.seed().insert();
        Recorder recorder = new Recorder();
        SecurityContext atCall = securityContext("bound-at-call");
        SecurityContext later = securityContext("bound-later");
        installBarrier();
        CompletableFuture<Future<OutboxTransition>> made = new CompletableFuture<>();
        Connection lock = fixture.connect();
        try {
            fixture.exec(lock, "SELECT pg_advisory_lock(" + BARRIER + ")");
            ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

            // When the call is made with one context and the holder is rebound to another before completion
            context.runOnContext(v -> {
                ContextHolder.Scope first = holder.bind(SecurityContext.class, atCall);
                made.complete(operations(recorder).retry(id, OutboxEntryState.DEAD_LETTER));
                first.close();
                holder.bind(SecurityContext.class, later);
            });
            Future<OutboxTransition> result = made.get(10, TimeUnit.SECONDS);
            fixture.awaitLockWaiters(1);
            fixture.exec(lock, "SELECT pg_advisory_unlock(" + BARRIER + ")");

            // Then the report holds the snapshot of the context bound at the call
            await(result);
        } finally {
            lock.close();
        }
        recorder.awaitReports(1, 0);
        assertSame(atCall.identity(), recorder.changes.get(0).security().identity());
        assertNotEquals(later.identity(), recorder.changes.get(0).security().identity());
        assertEquals(atCall.snapshot(), recorder.changes.get(0).security());
    }

    // --- Failed commands ---

    @Test
    @DisplayName("an unknown entry is reported as failed with the type of the failure the caller gets")
    void notFound() throws Exception {
        Recorder recorder = new Recorder();
        SecurityContext security = securityContext("operator-missing");

        Throwable failure =
                failureOf(callWith(security, () -> operations(recorder).retry(999, OutboxEntryState.DEAD_LETTER)));

        assertEquals("OutboxEntryNotFoundException", failure.getClass().getSimpleName());
        recorder.awaitReports(0, 1);
        OutboxCommandFailure reported = recorder.failures.get(0);
        assertEquals(OutboxOperationType.RETRY, reported.operation());
        assertEquals(999, reported.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, reported.expectedState());
        assertEquals("OutboxEntryNotFoundException", reported.errorType());
        assertEquals(BASE, reported.occurredAt());
        assertSame(security.identity(), reported.security().identity());
    }

    @Test
    @DisplayName("a state conflict is reported as failed, and each call has its own id")
    void stateConflict() throws Exception {
        long id = fixture.seed().state("PENDING").insert();
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(recorder);

        Throwable retry = failureOf(operations.retry(id, OutboxEntryState.DEAD_LETTER));
        Throwable discard = failureOf(operations.discard(id, OutboxEntryState.DEAD_LETTER));

        assertInstanceOf(OutboxEntryStateException.class, retry);
        assertInstanceOf(OutboxEntryStateException.class, discard);
        recorder.awaitReports(0, 2);
        OutboxCommandFailure first = recorder.failures.get(0);
        OutboxCommandFailure second = recorder.failures.get(1);
        assertEquals(OutboxOperationType.RETRY, first.operation());
        assertEquals(OutboxOperationType.DISCARD, second.operation());
        assertEquals(OutboxEntryState.DEAD_LETTER, first.expectedState());
        assertEquals("OutboxEntryStateException", first.errorType());
        assertEquals("OutboxEntryStateException", second.errorType());
        assertNotEquals(first.callId(), second.callId());
        assertEquals("PENDING", fixture.stateOf(id));
    }

    @Test
    @DisplayName("an invalid id, a null state and an illegal state are reported as failed, the null as a null state")
    void validation() throws Exception {
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(recorder);

        failureOf(operations.retry(0, OutboxEntryState.DEAD_LETTER));
        failureOf(operations.discard(3, null));
        failureOf(operations.retry(3, OutboxEntryState.PENDING));

        recorder.awaitReports(0, 3);
        assertEquals(0, recorder.failures.get(0).entryId());
        assertNull(recorder.failures.get(1).expectedState());
        assertEquals(OutboxEntryState.PENDING, recorder.failures.get(2).expectedState());
        for (OutboxCommandFailure reported : recorder.failures) {
            assertEquals("ValidationException", reported.errorType());
        }
    }

    @Test
    @DisplayName("a lock timeout is reported as a persistence failure and not as a change")
    void lockTimeout() throws Exception {
        long id = fixture.seed().insert();
        Recorder recorder = new Recorder();
        try (Connection holder = fixture.begin()) {
            fixture.exec(holder, "SELECT id FROM outbox WHERE id = ? FOR UPDATE", id);

            Throwable failure = failureOf(impatient(recorder).retry(id, OutboxEntryState.DEAD_LETTER));

            assertInstanceOf(InboxOutboxPersistenceException.class, failure);
            holder.commit();
        }

        recorder.awaitReports(0, 1);
        assertEquals("InboxOutboxPersistenceException", recorder.failures.get(0).errorType());
        assertEquals("DEAD_LETTER", fixture.stateOf(id));
    }

    @Test
    @DisplayName("a transaction that rolls back at commit is reported as failed, never as changed, and changes nothing")
    void rollbackAtCommit() throws Exception {
        long id = fixture.seed().insert();
        Map<String, Object> before = fixture.outboxRow(id);
        installCommitFailure();
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(recorder);

        Throwable retry = failureOf(operations.retry(id, OutboxEntryState.DEAD_LETTER));
        Throwable discard = failureOf(operations.discard(id, OutboxEntryState.DEAD_LETTER));

        assertInstanceOf(InboxOutboxPersistenceException.class, retry);
        assertInstanceOf(InboxOutboxPersistenceException.class, discard);
        recorder.awaitReports(0, 2);
        for (OutboxCommandFailure reported : recorder.failures) {
            assertEquals("InboxOutboxPersistenceException", reported.errorType());
        }
        assertEquals(before, fixture.outboxRow(id));
    }

    // --- Isolation ---

    @Test
    @DisplayName("listeners that throw do not change the result of a committed command, and later listeners still run")
    void throwingListenersOnCommit() throws Exception {
        long id = fixture.seed().insert();
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(
                thrower(new IllegalStateException("secret-listener-message")),
                thrower(new AssertionError("assertion")),
                thrower(new StackOverflowError()),
                recorder);

        OutboxTransition transition = await(operations.retry(id, OutboxEntryState.DEAD_LETTER));

        assertEquals(
                new OutboxTransition(id, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING, true), transition);
        recorder.awaitReports(1, 0);
        assertEquals("PENDING", fixture.stateOf(id));
    }

    @Test
    @DisplayName("listeners that throw do not change the failure a caller gets, and later listeners still run")
    void throwingListenersOnFailure() throws Exception {
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(
                thrower(new IllegalStateException("secret-listener-message")), thrower(new AssertionError()), recorder);

        Throwable failure = failureOf(operations.discard(999, OutboxEntryState.PENDING));

        assertEquals("OutboxEntryNotFoundException", failure.getClass().getSimpleName());
        recorder.awaitReports(0, 1);
    }

    @Test
    @DisplayName("an empty listener set leaves a command as it is without listeners")
    void noListeners() throws Exception {
        long id = fixture.seed().insert();
        Set<OutboxOperationListener> none = Set.of();

        OutboxTransition transition = await(new PgOutboxOperations(
                        fixture.pool(), new PgDbExceptionMapper(), DbSchemas.empty(), holder, none, CLOCK)
                .retry(id, OutboxEntryState.DEAD_LETTER));

        assertEquals(OutboxEntryState.PENDING, transition.current());
    }
}
