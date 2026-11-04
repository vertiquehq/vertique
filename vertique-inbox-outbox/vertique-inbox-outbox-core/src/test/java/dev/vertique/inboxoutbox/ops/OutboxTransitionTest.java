// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.OutboxEntryState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OutboxTransition}. */
@DisplayName("OutboxTransition")
class OutboxTransitionTest {

    @Test
    @DisplayName("a changed transition keeps its components")
    void changed() {
        OutboxTransition transition =
                new OutboxTransition(5, OutboxEntryState.DEAD_LETTER, OutboxEntryState.PENDING, true);

        assertEquals(5, transition.entryId());
        assertEquals(OutboxEntryState.DEAD_LETTER, transition.previous());
        assertEquals(OutboxEntryState.PENDING, transition.current());
        assertTrue(transition.changed());
    }

    @Test
    @DisplayName("an unchanged transition has the same state on both sides")
    void unchanged() {
        OutboxTransition transition =
                new OutboxTransition(5, OutboxEntryState.DISCARDED, OutboxEntryState.DISCARDED, false);

        assertFalse(transition.changed());
    }

    @Test
    @DisplayName("changed must agree with the states for every pair of states")
    void changedMustMatchStates() {
        for (OutboxEntryState previous : OutboxEntryState.values()) {
            for (OutboxEntryState current : OutboxEntryState.values()) {
                boolean differs = previous != current;

                OutboxTransition consistent = new OutboxTransition(1, previous, current, differs);
                ValidationException inconsistent = assertThrows(
                        ValidationException.class, () -> new OutboxTransition(1, previous, current, !differs));

                assertEquals(differs, consistent.changed());
                assertEquals("changed does not match the states", inconsistent.getMessage());
            }
        }
    }

    @Test
    @DisplayName("a non-positive id and absent states are rejected")
    void invalid() {
        assertEquals(
                "entryId must be positive",
                assertThrows(
                                ValidationException.class,
                                () -> new OutboxTransition(
                                        0, OutboxEntryState.PENDING, OutboxEntryState.DISCARDED, true))
                        .getMessage());
        assertEquals(
                "entryId must be positive",
                assertThrows(
                                ValidationException.class,
                                () -> new OutboxTransition(
                                        -3, OutboxEntryState.PENDING, OutboxEntryState.DISCARDED, true))
                        .getMessage());
        assertEquals(
                "previous and current must not be null",
                assertThrows(
                                ValidationException.class,
                                () -> new OutboxTransition(1, null, OutboxEntryState.PENDING, true))
                        .getMessage());
        assertEquals(
                "previous and current must not be null",
                assertThrows(
                                ValidationException.class,
                                () -> new OutboxTransition(1, OutboxEntryState.PENDING, null, true))
                        .getMessage());
    }
}
