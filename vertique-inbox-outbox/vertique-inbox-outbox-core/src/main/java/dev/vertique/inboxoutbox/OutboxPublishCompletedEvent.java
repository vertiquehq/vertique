// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The fact that one outbox publish attempt has completed, as
 * {@link OutboxPublishObserver#onPublishCompleted} delivers it.
 *
 * <p>The event carries framework facts only: no payload, no headers, no failure message and no
 * cause, because a failure message can contain application data. It can be logged or handed to a
 * metrics observer as is. The entry's payload, headers and metadata are reachable through the
 * {@link OutboxEnvelope} passed alongside it.
 *
 * <p><b>Record evolution.</b> The framework is the only constructor of this record, and components
 * may be added in later releases. Observers read the accessors; code that constructs the record or
 * deconstructs it with a record pattern is not covered by the compatibility promise.
 *
 * <p><b>Attempt numbering.</b> {@code attempt} equals {@link OutboxEnvelope#attempt()}: the number of
 * attempts made before this one, {@code 0} for the first. A deferral and a reclaim of a stale claim
 * both leave the stored attempt count unchanged, so the same number repeats across them. Neither
 * {@code attempt} nor the pair of {@code entryId} and {@code attempt} is a unique key for a
 * notification; {@code completedAt} tells notifications for the same entry apart.
 *
 * @param entryId             the outbox entry id, as a string; never {@code null}
 * @param destinationType     the entry's destination type; never {@code null}
 * @param destination         the entry's destination, the same value as
 *                            {@link OutboxEnvelope#destination()}; never {@code null}
 * @param attempt             the number of attempts made before this one, {@code 0} for the first;
 *                            equal to {@link OutboxEnvelope#attempt()}; repeats across deferrals and
 *                            reclaims of a stale claim and is not a unique key
 * @param maxAttempts         the entry's attempt limit, as stored with the entry
 * @param outcome             how the attempt ended; never {@code null}
 * @param disposition         what the relay did with the entry; never {@code null}
 * @param dispositionRecorded {@code true} only when the repository confirmed the state change;
 *                            {@code false} when the repository call failed, threw, or reported that
 *                            this relay no longer owned the entry
 * @param nextAttemptAt       the earliest time of the next attempt for
 *                            {@link OutboxEntryDisposition#RETRY_SCHEDULED} and
 *                            {@link OutboxEntryDisposition#DEFERRED}; {@code null} for the other
 *                            dispositions, and when the relay could not compute the time. For
 *                            {@code DEFERRED} it is approximate: the relay computes it from its own
 *                            clock, while the stored entry is deferred from the database clock
 * @param errorType           the class name of the failure's cause, or {@code null} when the
 *                            attempt did not fail or the failure has no cause; never message text
 * @param elapsed             the time from the call to
 *                            {@link OutboxDestinationHandler#publish(OutboxEnvelope)} until its
 *                            result; zero when no handler is registered for the destination type;
 *                            never {@code null}
 * @param completedAt         the time the relay notified the observers, after the repository call
 *                            for the attempt settled; never {@code null}
 */
public record OutboxPublishCompletedEvent(
        String entryId,
        DestinationType destinationType,
        String destination,
        int attempt,
        int maxAttempts,
        OutboxPublishOutcome outcome,
        OutboxEntryDisposition disposition,
        boolean dispositionRecorded,
        @Nullable Instant nextAttemptAt,
        @Nullable String errorType,
        Duration elapsed,
        Instant completedAt) {

    /** Validates required components. */
    public OutboxPublishCompletedEvent {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(destinationType, "destinationType");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
