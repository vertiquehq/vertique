// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sealed sum type describing the reminder schedule for a
 * {@link dev.vertique.workflow.plan.HumanTaskNode}.
 *
 * <p>Two permitted variants:
 * <ul>
 *   <li>{@link OneShotOffsets} — fire reminder timers at a fixed set of offsets measured from the
 *       task creation instant.</li>
 *   <li>{@link RecurringInterval} — fire reminder timers at a regular interval, optionally capped
 *       by a maximum fire count.</li>
 * </ul>
 *
 * <p>Both variants enforce positive durations at construction time. Consumers can exhaustively
 * switch over this interface using Java's sealed-type pattern matching without a default branch.
 */
public sealed interface ReminderSpec permits ReminderSpec.OneShotOffsets, ReminderSpec.RecurringInterval {

    // --- Permitted variants ---

    /**
     * Fires reminder timers at the given offsets measured from the task creation instant.
     *
     * <p>All offsets must be strictly positive. Duplicates are rejected. The list is canonicalized
     * to ascending order at construction time so that the {@code reminderIndex} ordinal observed
     * at fire time corresponds to the offset at {@code offsetsFromTaskCreation.get(reminderIndex - 1)}
     * — timers fire in chronological order of their {@code fire_at} instants, and the engine relies
     * on this canonicalization to attach the correct {@code offsetFromCreation} attribute to the
     * emitted {@code TASK_REMINDER} event. At least one offset must be provided.
     *
     * @param offsetsFromTaskCreation list of positive, distinct durations measured from task
     *     creation; never null, never empty, all entries strictly positive; canonicalized to
     *     ascending order at construction time
     */
    record OneShotOffsets(List<Duration> offsetsFromTaskCreation) implements ReminderSpec {

        /**
         * Validates the offsets list, rejects duplicates, and canonicalizes to ascending order so
         * the engine's {@code reminderIndex} ordinal indexes the correct offset at fire time.
         *
         * @throws NullPointerException     if {@code offsetsFromTaskCreation} is null
         * @throws IllegalArgumentException if the list is empty, contains duplicates, or any
         *     duration is non-positive
         */
        public OneShotOffsets {
            Objects.requireNonNull(offsetsFromTaskCreation, "offsetsFromTaskCreation");
            if (offsetsFromTaskCreation.isEmpty()) {
                throw new IllegalArgumentException("at least one offset required");
            }
            List<Duration> sorted = new ArrayList<>(offsetsFromTaskCreation);
            for (Duration d : sorted) {
                if (d == null) {
                    throw new NullPointerException("offset must not be null");
                }
                if (d.isZero() || d.isNegative()) {
                    throw new IllegalArgumentException("offsets must be positive: " + d);
                }
            }
            sorted.sort(Duration::compareTo);
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).equals(sorted.get(i - 1))) {
                    throw new IllegalArgumentException("duplicate offset rejected: " + sorted.get(i));
                }
            }
            offsetsFromTaskCreation = List.copyOf(sorted);
        }
    }

    /**
     * Fires reminder timers at a regular interval, optionally capped at a maximum number of fires.
     *
     * <p>The interval must be strictly positive. {@code maxFires} may be null (unlimited) or
     * a positive integer. A value of {@code 0} or negative is rejected.
     *
     * @param interval  the repeat interval; never null, strictly positive
     * @param maxFires  maximum number of reminder fires; null means unlimited; must be {@code >= 1}
     *     when non-null
     */
    record RecurringInterval(Duration interval, @Nullable Integer maxFires) implements ReminderSpec {

        /**
         * Validates the interval and maxFires.
         *
         * @throws NullPointerException     if {@code interval} is null
         * @throws IllegalArgumentException if {@code interval} is non-positive, or if
         *     {@code maxFires} is non-null and less than 1
         */
        public RecurringInterval {
            Objects.requireNonNull(interval, "interval");
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("interval must be positive");
            }
            if (maxFires != null && maxFires < 1) {
                throw new IllegalArgumentException("maxFires must be >= 1 or null, got: " + maxFires);
            }
        }
    }
}
