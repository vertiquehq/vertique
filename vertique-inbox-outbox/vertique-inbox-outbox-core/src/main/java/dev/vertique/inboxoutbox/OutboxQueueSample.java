// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.time.Duration;
import java.util.Objects;

/**
 * The size of the outbox queue at one moment, as {@link OutboxQueueProbe#sample()} returns it.
 *
 * <p>An entry is <em>due</em> when it is {@code PENDING} and neither its scheduled time nor its
 * retry delay lies in the future. Entries waiting for a scheduled time or a retry delay are not due
 * and are not counted. Due is a property of the entry alone: an entry is counted whether or not a
 * relay can claim it, for example when no handler serves its destination type or an earlier entry
 * of the same aggregate is still ahead of it.
 *
 * <p><b>Record evolution.</b> The framework is the only constructor of this record, and components
 * may be added in later releases. Code that reads the accessors is covered by the compatibility
 * promise; code that constructs the record or deconstructs it with a record pattern is not.
 *
 * @param due               the number of due entries; never negative
 * @param oldestDueAge      how long the oldest due entry has been due; {@link Duration#ZERO} when no
 *                          entry is due; never {@code null}
 * @param deadLetter        the number of entries in the dead-letter state; never negative
 */
public record OutboxQueueSample(long due, Duration oldestDueAge, long deadLetter) {

    /** Validates the components. */
    public OutboxQueueSample {
        Objects.requireNonNull(oldestDueAge, "oldestDueAge");
        if (due < 0) {
            throw new IllegalArgumentException("due must not be negative");
        }
        if (deadLetter < 0) {
            throw new IllegalArgumentException("deadLetter must not be negative");
        }
        if (oldestDueAge.isNegative()) {
            oldestDueAge = Duration.ZERO;
        }
    }
}
