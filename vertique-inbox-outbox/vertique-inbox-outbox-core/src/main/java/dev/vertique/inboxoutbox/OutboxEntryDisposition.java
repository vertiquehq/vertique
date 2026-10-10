// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * What the relay did with an outbox entry after one publish attempt.
 *
 * <p>The disposition is the state change the relay asked the repository for. Whether the repository
 * confirmed it is reported separately, by
 * {@link OutboxPublishCompletedEvent#dispositionRecorded()}.
 *
 * @see OutboxPublishCompletedEvent#disposition()
 */
public enum OutboxEntryDisposition {

    /** The entry was marked as published; it will not be attempted again. */
    PUBLISHED,

    /**
     * The entry was returned to pending with its attempt count incremented, to be attempted again
     * no earlier than {@link OutboxPublishCompletedEvent#nextAttemptAt()}.
     */
    RETRY_SCHEDULED,

    /**
     * The entry was moved to the dead-letter state: the attempt failed permanently, or it failed
     * and no attempts are left.
     */
    DEAD_LETTERED,

    /**
     * The entry was returned to pending with its attempt count unchanged, to be attempted again no
     * earlier than {@link OutboxPublishCompletedEvent#nextAttemptAt()}.
     */
    DEFERRED
}
