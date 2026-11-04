// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the events and the listener contract of the outbox operations. */
@DisplayName("outbox operation events")
class OutboxOperationEventsTest {

    private static final UUID CALL_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static SecurityContextSnapshot snapshot() {
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, "secret-principal-id", Map.of());
        return SecurityContexts.assemble(
                        new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty()),
                        new AuthenticationState(
                                DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                        AuthorizationClaims.empty(),
                        Optional.empty())
                .snapshot();
    }

    @Test
    @DisplayName("the operation type has retry and discard")
    void operationTypes() {
        assertEquals(
                List.of(OutboxOperationType.RETRY, OutboxOperationType.DISCARD), List.of(OutboxOperationType.values()));
    }

    @Test
    @DisplayName("a change keeps its components, including the snapshot")
    void changeKeepsComponents() {
        // Given a snapshot
        SecurityContextSnapshot security = snapshot();

        // When a change is built
        OutboxEntryChange change = new OutboxEntryChange(
                CALL_ID,
                OutboxOperationType.RETRY,
                7,
                OutboxEntryState.DEAD_LETTER,
                OutboxEntryState.PENDING,
                true,
                security,
                AT);

        // Then every component is readable
        assertEquals(CALL_ID, change.callId());
        assertEquals(OutboxOperationType.RETRY, change.operation());
        assertEquals(7, change.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, change.previous());
        assertEquals(OutboxEntryState.PENDING, change.current());
        assertTrue(change.changed());
        assertSame(security, change.security());
        assertEquals(AT, change.occurredAt());
    }

    @Test
    @DisplayName("a change without a security context carries a null snapshot")
    void changeWithoutSecurity() {
        OutboxEntryChange change = new OutboxEntryChange(
                CALL_ID,
                OutboxOperationType.DISCARD,
                7,
                OutboxEntryState.DISCARDED,
                OutboxEntryState.DISCARDED,
                false,
                null,
                AT);

        assertNull(change.security());
        assertFalse(change.changed());
    }

    @Test
    @DisplayName("a change refuses a missing required component")
    void changeRequiresComponents() {
        assertEquals(
                "callId",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxEntryChange(
                                        null,
                                        OutboxOperationType.RETRY,
                                        1,
                                        OutboxEntryState.DEAD_LETTER,
                                        OutboxEntryState.PENDING,
                                        true,
                                        null,
                                        AT))
                        .getMessage());
        assertEquals(
                "operation",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxEntryChange(
                                        CALL_ID,
                                        null,
                                        1,
                                        OutboxEntryState.DEAD_LETTER,
                                        OutboxEntryState.PENDING,
                                        true,
                                        null,
                                        AT))
                        .getMessage());
        assertEquals(
                "previous",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxEntryChange(
                                        CALL_ID,
                                        OutboxOperationType.RETRY,
                                        1,
                                        null,
                                        OutboxEntryState.PENDING,
                                        true,
                                        null,
                                        AT))
                        .getMessage());
        assertEquals(
                "current",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxEntryChange(
                                        CALL_ID,
                                        OutboxOperationType.RETRY,
                                        1,
                                        OutboxEntryState.DEAD_LETTER,
                                        null,
                                        true,
                                        null,
                                        AT))
                        .getMessage());
        assertEquals(
                "occurredAt",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxEntryChange(
                                        CALL_ID,
                                        OutboxOperationType.RETRY,
                                        1,
                                        OutboxEntryState.DEAD_LETTER,
                                        OutboxEntryState.PENDING,
                                        true,
                                        null,
                                        null))
                        .getMessage());
    }

    @Test
    @DisplayName("a change prints its call, operation, entry and states and never the snapshot")
    void changeToString() {
        OutboxEntryChange change = new OutboxEntryChange(
                CALL_ID,
                OutboxOperationType.RETRY,
                7,
                OutboxEntryState.DEAD_LETTER,
                OutboxEntryState.PENDING,
                true,
                snapshot(),
                AT);

        String text = change.toString();

        assertTrue(text.contains(CALL_ID.toString()), text);
        assertTrue(text.contains("RETRY"), text);
        assertTrue(text.contains("entryId=7"), text);
        assertTrue(text.contains("DEAD_LETTER"), text);
        assertTrue(text.contains("PENDING"), text);
        assertFalse(text.contains("secret-principal-id"), text);
        assertFalse(text.contains("security"), text);
    }

    @Test
    @DisplayName("a failure keeps its components and accepts a null expected state, error type and snapshot")
    void failureKeepsComponents() {
        SecurityContextSnapshot security = snapshot();

        OutboxCommandFailure failure = new OutboxCommandFailure(
                CALL_ID, OutboxOperationType.DISCARD, -1, null, "ValidationException", security, AT);
        OutboxCommandFailure bare =
                new OutboxCommandFailure(CALL_ID, OutboxOperationType.RETRY, 3, null, null, null, AT);

        assertEquals(CALL_ID, failure.callId());
        assertEquals(OutboxOperationType.DISCARD, failure.operation());
        assertEquals(-1, failure.entryId());
        assertNull(failure.expectedState());
        assertEquals("ValidationException", failure.errorType());
        assertSame(security, failure.security());
        assertEquals(AT, failure.occurredAt());
        assertNull(bare.errorType());
        assertNull(bare.security());
    }

    @Test
    @DisplayName("a failure refuses a missing call id, operation or instant")
    void failureRequiresComponents() {
        assertEquals(
                "callId",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxCommandFailure(
                                        null,
                                        OutboxOperationType.RETRY,
                                        1,
                                        OutboxEntryState.DEAD_LETTER,
                                        null,
                                        null,
                                        AT))
                        .getMessage());
        assertEquals(
                "operation",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxCommandFailure(
                                        CALL_ID, null, 1, OutboxEntryState.DEAD_LETTER, null, null, AT))
                        .getMessage());
        assertEquals(
                "occurredAt",
                assertThrows(
                                NullPointerException.class,
                                () -> new OutboxCommandFailure(
                                        CALL_ID,
                                        OutboxOperationType.RETRY,
                                        1,
                                        OutboxEntryState.DEAD_LETTER,
                                        null,
                                        null,
                                        null))
                        .getMessage());
    }

    @Test
    @DisplayName("a failure prints its call, operation, entry, expected state and error type and never the snapshot")
    void failureToString() {
        OutboxCommandFailure failure = new OutboxCommandFailure(
                CALL_ID,
                OutboxOperationType.RETRY,
                7,
                OutboxEntryState.DEAD_LETTER,
                "OutboxEntryStateException",
                snapshot(),
                AT);

        String text = failure.toString();

        assertTrue(text.contains(CALL_ID.toString()), text);
        assertTrue(text.contains("RETRY"), text);
        assertTrue(text.contains("entryId=7"), text);
        assertTrue(text.contains("DEAD_LETTER"), text);
        assertTrue(text.contains("OutboxEntryStateException"), text);
        assertFalse(text.contains("secret-principal-id"), text);
        assertFalse(text.contains("security"), text);
    }

    @Test
    @DisplayName("a listener may ignore failures: that callback has an empty default")
    void failureCallbackDefaultsToNothing() {
        AtomicReference<OutboxEntryChange> seen = new AtomicReference<>();
        OutboxOperationListener listener = seen::set;

        listener.onOutboxCommandFailed(
                new OutboxCommandFailure(CALL_ID, OutboxOperationType.RETRY, 1, null, null, null, AT));

        assertNull(seen.get());
    }
}
