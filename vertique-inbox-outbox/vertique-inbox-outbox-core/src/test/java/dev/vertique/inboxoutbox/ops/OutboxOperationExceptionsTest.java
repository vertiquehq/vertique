// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.OutboxEntryState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the exceptions of the operator commands. */
@DisplayName("outbox operation exceptions")
class OutboxOperationExceptionsTest {

    @Test
    @DisplayName("OutboxEntryNotFoundException is a not-found failure that names only the entry id")
    void notFound() {
        OutboxEntryNotFoundException exception = new OutboxEntryNotFoundException(77);

        assertInstanceOf(NotFoundException.class, exception);
        assertEquals(77, exception.entryId());
        assertEquals("Outbox entry 77 was not found", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("OutboxEntryStateException is a conflict that carries the states and a fixed message")
    void stateConflict() {
        OutboxEntryStateException exception =
                new OutboxEntryStateException(12, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PROCESSING);

        assertInstanceOf(ConflictException.class, exception);
        assertEquals(12, exception.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, exception.expected());
        assertEquals(OutboxEntryState.PROCESSING, exception.current());
        assertEquals("The outbox entry is not in the expected state", exception.getMessage());
        assertFalse(exception.getMessage().contains("12"), "the message names neither the entry nor the states");
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("OutboxEntryStateException rejects an absent state")
    void stateConflictRejectsNull() {
        assertEquals(
                "expected and current must not be null",
                assertThrows(
                                ValidationException.class,
                                () -> new OutboxEntryStateException(1, null, OutboxEntryState.PENDING))
                        .getMessage());
        assertEquals(
                "expected and current must not be null",
                assertThrows(
                                ValidationException.class,
                                () -> new OutboxEntryStateException(1, OutboxEntryState.PENDING, null))
                        .getMessage());
    }
}
