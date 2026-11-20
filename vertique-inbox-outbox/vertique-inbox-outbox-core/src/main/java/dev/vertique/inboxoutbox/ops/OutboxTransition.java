// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.OutboxEntryState;

/**
 * The outcome of an operator command.
 *
 * @param entryId  identifier of the entry; positive
 * @param previous the state the entry was in when the command ran
 * @param current  the state the entry is in now
 * @param changed  whether the command changed the entry; {@code false} for a discard of an entry that was already
 *                 discarded
 */
public record OutboxTransition(long entryId, OutboxEntryState previous, OutboxEntryState current, boolean changed) {

    /**
     * Creates a transition.
     *
     * @throws ValidationException if the id is not positive, a state is absent, or {@code changed} disagrees with
     *                             the two states
     */
    public OutboxTransition {
        if (entryId <= 0) {
            throw new ValidationException("entryId must be positive");
        }
        if (previous == null || current == null) {
            throw new ValidationException("previous and current must not be null");
        }
        if (changed == (previous == current)) {
            throw new ValidationException("changed does not match the states");
        }
    }
}
