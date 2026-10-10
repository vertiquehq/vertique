// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * How one outbox publish attempt ended, as the relay classified it.
 *
 * <p>This is the result of the attempt itself. What the relay then did with the entry is the
 * {@link OutboxEntryDisposition}: a {@link #RETRYABLE_FAILURE} on the last allowed attempt, for
 * example, ends in {@link OutboxEntryDisposition#DEAD_LETTERED}.
 *
 * @see OutboxPublishCompletedEvent#outcome()
 */
public enum OutboxPublishOutcome {

    /** The destination handler reported that the entry was delivered. */
    SUCCESS,

    /**
     * The attempt failed in a way that may succeed later. This is also the outcome when the handler
     * throws, returns a failed future, returns {@code null} instead of a future, or completes its
     * future with {@code null}.
     */
    RETRYABLE_FAILURE,

    /** The attempt failed in a way that will not succeed on a later attempt. */
    PERMANENT_FAILURE,

    /**
     * The entry could not be delivered from this node now: the handler reported it as unresolvable,
     * or no handler is registered for the entry's destination type.
     */
    UNRESOLVABLE
}
