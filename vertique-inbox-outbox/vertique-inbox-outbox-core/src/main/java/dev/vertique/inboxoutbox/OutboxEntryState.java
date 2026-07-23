// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * Lifecycle states for an outbox entry as it progresses through the relay pipeline.
 *
 * <p>The normal progression is {@link #PENDING} → {@link #PROCESSING} → {@link #PUBLISHED}.
 * Entries that exhaust their retry budget transition to {@link #DEAD_LETTER}.
 */
public enum OutboxEntryState {

    /**
     * The entry has been written to the outbox table and is waiting to be claimed by a relay
     * worker. This is the initial state for all new outbox entries.
     */
    PENDING,

    /**
     * A relay worker has claimed the entry and is actively attempting to publish it to the
     * configured destination. Entries remain in this state until the publish attempt completes
     * or the worker's lease expires.
     */
    PROCESSING,

    /**
     * The entry has been successfully delivered to its destination. Terminal state — the entry
     * will eventually be removed by the cleanup job after the configured retention period.
     */
    PUBLISHED,

    /**
     * The entry has exhausted its maximum retry attempts without a successful publish.
     * Terminal error state — the entry is retained for inspection and manual intervention.
     * The cleanup job removes dead-letter entries after the configured dead-letter retention period.
     */
    DEAD_LETTER
}
