// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.ops.OutboxCommandFailure;
import dev.vertique.inboxoutbox.ops.OutboxEntryChange;
import dev.vertique.inboxoutbox.ops.OutboxOperationListener;
import dev.vertique.inboxoutbox.ops.OutboxOperationType;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.sqlclient.Pool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for how {@link PgOutboxOperations} reports a command that is refused before its transaction starts,
 * and how it isolates the listeners. The reports of committed and failed transactions are proved against a real
 * database in {@link PgOutboxOperationListenerIT}.
 */
@DisplayName("PgOutboxOperations listener reports")
class PgOutboxOperationListenerTest {

    private static final Instant AT = Instant.parse("2026-02-03T04:05:06Z");
    private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
    private static final PgDbExceptionMapper MAPPER = new PgDbExceptionMapper();

    /** Keeps every report it receives. */
    private static final class Recorder implements OutboxOperationListener {

        final List<OutboxEntryChange> changes = new ArrayList<>();
        final List<OutboxCommandFailure> failures = new ArrayList<>();

        @Override
        public void onOutboxEntryChanged(OutboxEntryChange change) {
            changes.add(change);
        }

        @Override
        public void onOutboxCommandFailed(OutboxCommandFailure failure) {
            failures.add(failure);
        }
    }

    private static SecurityContext securityContext() {
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, "operator-1", Map.of());
        return SecurityContexts.assemble(
                new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty()),
                new AuthenticationState(
                        DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                AuthorizationClaims.empty(),
                Optional.empty());
    }

    private static PgOutboxOperations operations(Pool pool, ContextHolder holder, OutboxOperationListener... all) {
        return new PgOutboxOperations(
                pool, MAPPER, DbSchemas.empty(), holder, new LinkedHashSet<>(List.of(all)), CLOCK);
    }

    @Test
    @DisplayName("a refused retry is reported as failed with the same exception the caller gets, and never as changed")
    void refusedRetryIsReported() {
        // Given a listener and a pool that must not be used
        Pool pool = mock(Pool.class);
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(pool, null, recorder);

        // When the retry is refused as invalid
        Throwable failure = operations.retry(0, OutboxEntryState.DEAD_LETTER).cause();

        // Then the caller gets the validation failure and the listener is told once
        assertInstanceOf(ValidationException.class, failure);
        assertTrue(recorder.changes.isEmpty());
        assertEquals(1, recorder.failures.size());
        OutboxCommandFailure reported = recorder.failures.get(0);
        assertEquals(OutboxOperationType.RETRY, reported.operation());
        assertEquals(0, reported.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, reported.expectedState());
        assertEquals("ValidationException", reported.errorType());
        assertEquals(AT, reported.occurredAt());
        assertNull(reported.security());
        verifyNoInteractions(pool);
    }

    @Test
    @DisplayName("a refused discard with a null or illegal expected state is reported, the null as a null state")
    void refusedDiscardIsReported() {
        Pool pool = mock(Pool.class);
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(pool, null, recorder);

        Throwable nullState = operations.discard(5, null).cause();
        Throwable illegalState =
                operations.discard(5, OutboxEntryState.PROCESSING).cause();
        Throwable badId = operations.discard(-3, OutboxEntryState.PENDING).cause();

        for (Throwable failure : List.of(nullState, illegalState, badId)) {
            assertInstanceOf(ValidationException.class, failure);
        }
        assertEquals(3, recorder.failures.size());
        assertNull(recorder.failures.get(0).expectedState());
        assertEquals(OutboxEntryState.PROCESSING, recorder.failures.get(1).expectedState());
        assertEquals(-3, recorder.failures.get(2).entryId());
        for (OutboxCommandFailure reported : recorder.failures) {
            assertEquals(OutboxOperationType.DISCARD, reported.operation());
            assertEquals("ValidationException", reported.errorType());
        }
        assertEquals(
                3,
                recorder.failures.stream()
                        .map(OutboxCommandFailure::callId)
                        .distinct()
                        .count(),
                "every call has its own id");
        verifyNoInteractions(pool);
    }

    @Test
    @DisplayName("the snapshot is taken from the holder when the call is made and carried unchanged")
    void snapshotIsTakenAtCallTime() {
        // Given a holder with a bound security context
        Pool pool = mock(Pool.class);
        ContextHolder holder = mock(ContextHolder.class);
        SecurityContext first = securityContext();
        when(holder.current(SecurityContext.class)).thenReturn(Optional.of(first));
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(pool, holder, recorder);

        // When a call is made, and the holder is rebound afterwards
        operations.retry(0, OutboxEntryState.DEAD_LETTER);
        when(holder.current(SecurityContext.class)).thenReturn(Optional.empty());
        operations.retry(0, OutboxEntryState.DEAD_LETTER);

        // Then the first report holds the snapshot of the first context and the second none
        assertEquals(2, recorder.failures.size());
        assertSame(first.identity(), recorder.failures.get(0).security().identity());
        assertSame(first.authentication(), recorder.failures.get(0).security().authentication());
        assertNull(recorder.failures.get(1).security());
    }

    @Test
    @DisplayName("without a holder the snapshot is null")
    void noHolderMeansNoSnapshot() {
        Recorder recorder = new Recorder();

        operations(mock(Pool.class), null, recorder).discard(0, OutboxEntryState.PENDING);

        assertNull(recorder.failures.get(0).security());
    }

    @Test
    @DisplayName("without a listener nothing is captured and the caller's result is the same")
    void noListenerNoCapture() {
        ContextHolder holder = mock(ContextHolder.class);
        PgOutboxOperations operations = operations(mock(Pool.class), holder);

        Throwable failure = operations.retry(0, OutboxEntryState.DEAD_LETTER).cause();

        assertInstanceOf(ValidationException.class, failure);
        assertEquals("entryId must be positive", failure.getMessage());
        verifyNoInteractions(holder);
    }

    @Test
    @DisplayName("a listener that throws does not change the result or stop the listeners after it")
    void throwingListenersAreIsolated() {
        // Given listeners that throw an exception and each kind of contained error, then one that records
        Recorder recorder = new Recorder();
        OutboxOperationListener[] throwing = {
            thrower(new IllegalStateException("secret-listener-message")),
            thrower(new AssertionError("assertion")),
            thrower(new StackOverflowError()),
            thrower(new NoClassDefFoundError("missing"))
        };
        List<OutboxOperationListener> all = new ArrayList<>(List.of(throwing));
        all.add(recorder);
        PgOutboxOperations operations = operations(mock(Pool.class), null, all.toArray(new OutboxOperationListener[0]));

        // When a refused command is reported
        Throwable failure = operations.retry(0, OutboxEntryState.DEAD_LETTER).cause();

        // Then the caller still gets the validation failure and the last listener still ran
        assertInstanceOf(ValidationException.class, failure);
        assertEquals("entryId must be positive", failure.getMessage());
        assertEquals(1, recorder.failures.size());
    }

    @Test
    @DisplayName("the call id differs between calls and the instant comes from the injected clock")
    void callIdsDiffer() {
        Recorder recorder = new Recorder();
        PgOutboxOperations operations = operations(mock(Pool.class), null, recorder);

        operations.retry(0, OutboxEntryState.DEAD_LETTER);
        operations.retry(0, OutboxEntryState.DEAD_LETTER);

        assertNotEquals(
                recorder.failures.get(0).callId(), recorder.failures.get(1).callId());
        assertEquals(AT, recorder.failures.get(0).occurredAt());
    }

    @Test
    @DisplayName("a listener that does not override the failure callback is not harmed by it")
    void defaultFailureCallback() {
        List<OutboxEntryChange> changes = new ArrayList<>();
        OutboxOperationListener onlyChanges = changes::add;

        Throwable failure = operations(mock(Pool.class), null, onlyChanges)
                .retry(0, OutboxEntryState.DEAD_LETTER)
                .cause();

        assertInstanceOf(ValidationException.class, failure);
        assertTrue(changes.isEmpty());
    }

    @Test
    @DisplayName("the service requires its listener set and its clock")
    void requiresCollaborators() {
        Pool pool = mock(Pool.class);
        Set<OutboxOperationListener> none = Set.of();

        assertEquals(
                "listeners",
                org.junit.jupiter.api.Assertions.assertThrows(
                                NullPointerException.class,
                                () -> new PgOutboxOperations(pool, MAPPER, DbSchemas.empty(), null, null, CLOCK))
                        .getMessage());
        assertEquals(
                "clock",
                org.junit.jupiter.api.Assertions.assertThrows(
                                NullPointerException.class,
                                () -> new PgOutboxOperations(pool, MAPPER, DbSchemas.empty(), null, none, null))
                        .getMessage());
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
}
