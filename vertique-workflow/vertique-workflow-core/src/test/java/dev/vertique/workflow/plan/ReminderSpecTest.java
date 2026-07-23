// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two {@link ReminderSpec} permitted variants: {@link ReminderSpec.OneShotOffsets}
 * and {@link ReminderSpec.RecurringInterval}. Tests include exhaustive pattern matching, null
 * validation, and invalid-value rejection.
 */
class ReminderSpecTest {

    // --- Sealed hierarchy / pattern matching ---

    @Nested
    @DisplayName("sealed hierarchy")
    class SealedHierarchy {

        @Test
        @DisplayName("OneShotOffsets and RecurringInterval are exhaustively pattern-matchable")
        void patternMatchingIsExhaustive() {
            ReminderSpec spec1 = new ReminderSpec.OneShotOffsets(List.of(Duration.ofHours(1)));
            ReminderSpec spec2 = new ReminderSpec.RecurringInterval(Duration.ofHours(2), null);

            String label1 =
                    switch (spec1) {
                        case ReminderSpec.OneShotOffsets o -> "one-shot";
                        case ReminderSpec.RecurringInterval r -> "recurring";
                    };
            String label2 =
                    switch (spec2) {
                        case ReminderSpec.OneShotOffsets o -> "one-shot";
                        case ReminderSpec.RecurringInterval r -> "recurring";
                    };

            assertThat(label1).isEqualTo("one-shot");
            assertThat(label2).isEqualTo("recurring");
        }
    }

    // --- OneShotOffsets ---

    @Nested
    @DisplayName("OneShotOffsets")
    class OneShotOffsetsTests {

        @Test
        @DisplayName("null offsetsFromTaskCreation throws NullPointerException")
        void nullListThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReminderSpec.OneShotOffsets(null))
                    .withMessageContaining("offsetsFromTaskCreation");
        }

        @Test
        @DisplayName("empty list throws IllegalArgumentException")
        void emptyListThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.OneShotOffsets(List.of()))
                    .withMessageContaining("at least one offset");
        }

        @Test
        @DisplayName("zero duration in offsets throws IllegalArgumentException")
        void zeroDurationThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.OneShotOffsets(List.of(Duration.ZERO)))
                    .withMessageContaining("positive");
        }

        @Test
        @DisplayName("negative duration in offsets throws IllegalArgumentException")
        void negativeDurationThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.OneShotOffsets(List.of(Duration.ofHours(-1))))
                    .withMessageContaining("positive");
        }

        @Test
        @DisplayName("valid positive offsets are accepted and list is defensively copied")
        void validOffsetsAndDefensiveCopy() {
            java.util.List<Duration> mutable = new java.util.ArrayList<>(List.of(Duration.ofHours(1)));
            ReminderSpec.OneShotOffsets spec = new ReminderSpec.OneShotOffsets(mutable);

            mutable.add(Duration.ofHours(99));

            assertThat(spec.offsetsFromTaskCreation()).hasSize(1).containsExactly(Duration.ofHours(1));
        }

        @Test
        @DisplayName("list is unmodifiable after construction")
        void listIsUnmodifiable() {
            ReminderSpec.OneShotOffsets spec = new ReminderSpec.OneShotOffsets(List.of(Duration.ofMinutes(30)));

            org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> spec.offsetsFromTaskCreation().add(Duration.ofHours(1)));
        }
    }

    // --- RecurringInterval ---

    @Nested
    @DisplayName("RecurringInterval")
    class RecurringIntervalTests {

        @Test
        @DisplayName("null interval throws NullPointerException")
        void nullIntervalThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReminderSpec.RecurringInterval(null, null))
                    .withMessageContaining("interval");
        }

        @Test
        @DisplayName("zero interval throws IllegalArgumentException")
        void zeroIntervalThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.RecurringInterval(Duration.ZERO, null))
                    .withMessageContaining("positive");
        }

        @Test
        @DisplayName("negative interval throws IllegalArgumentException")
        void negativeIntervalThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.RecurringInterval(Duration.ofHours(-1), null))
                    .withMessageContaining("positive");
        }

        @Test
        @DisplayName("maxFires=0 throws IllegalArgumentException")
        void maxFiresZeroThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.RecurringInterval(Duration.ofHours(1), 0))
                    .withMessageContaining("maxFires");
        }

        @Test
        @DisplayName("maxFires=-1 throws IllegalArgumentException")
        void maxFiresNegativeThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.RecurringInterval(Duration.ofHours(1), -1))
                    .withMessageContaining("maxFires");
        }

        @Test
        @DisplayName("null maxFires is accepted (unlimited)")
        void nullMaxFiresAccepted() {
            ReminderSpec.RecurringInterval spec = new ReminderSpec.RecurringInterval(Duration.ofHours(4), null);

            assertThat(spec.interval()).isEqualTo(Duration.ofHours(4));
            assertThat(spec.maxFires()).isNull();
        }

        @Test
        @DisplayName("maxFires=1 is accepted (minimum bounded)")
        void maxFiresOneIsAccepted() {
            ReminderSpec.RecurringInterval spec = new ReminderSpec.RecurringInterval(Duration.ofMinutes(30), 1);

            assertThat(spec.maxFires()).isEqualTo(1);
        }

        @Test
        @DisplayName("positive interval with positive maxFires is fully valid")
        void validIntervalAndMaxFires() {
            ReminderSpec.RecurringInterval spec = new ReminderSpec.RecurringInterval(Duration.ofHours(24), 5);

            assertThat(spec.interval()).isEqualTo(Duration.ofHours(24));
            assertThat(spec.maxFires()).isEqualTo(5);
        }
    }

    // --- OneShotOffsets canonicalization (cycle-4 review fix) ---

    @Nested
    @DisplayName("OneShotOffsets canonicalization")
    class OneShotOffsetsCanonicalization {

        @Test
        @DisplayName("offsets are sorted ascending so reminderIndex-1 indexes the correct chronological offset")
        void offsetsAreCanonicallyAscending() {
            // The engine relies on this canonicalization: timers fire in chronological order, and
            // taskReminderFired indexes osu.offsetsFromTaskCreation().get(reminderIndex - 1) to
            // attach the offset to the TASK_REMINDER event. With unsorted input the wire-contract
            // attribute would be wrong.
            ReminderSpec.OneShotOffsets spec = new ReminderSpec.OneShotOffsets(
                    List.of(Duration.ofHours(2), Duration.ofMinutes(30), Duration.ofHours(1)));

            assertThat(spec.offsetsFromTaskCreation())
                    .containsExactly(Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2));
        }

        @Test
        @DisplayName("duplicate offsets are rejected with IllegalArgumentException")
        void duplicateOffsetsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReminderSpec.OneShotOffsets(
                            List.of(Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofHours(1))))
                    .withMessageContaining("duplicate");
        }

        @Test
        @DisplayName("already-sorted offsets are preserved without reordering")
        void sortedOffsetsPreserved() {
            List<Duration> input = List.of(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4));
            ReminderSpec.OneShotOffsets spec = new ReminderSpec.OneShotOffsets(input);

            assertThat(spec.offsetsFromTaskCreation()).containsExactlyElementsOf(input);
        }
    }
}
