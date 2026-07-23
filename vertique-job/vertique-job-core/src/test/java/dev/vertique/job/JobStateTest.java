// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobState} lifecycle transition rules.
 */
@DisplayName("JobState")
class JobStateTest {

    @Nested
    @DisplayName("ENQUEUED transitions")
    class EnqueuedTransitions {

        @Test
        @DisplayName("can transition to PROCESSING")
        void canTransitionToProcessing() {
            assertTrue(JobState.ENQUEUED.canTransitionTo(JobState.PROCESSING));
        }

        @Test
        @DisplayName("cannot transition to ENQUEUED")
        void cannotTransitionToEnqueued() {
            assertFalse(JobState.ENQUEUED.canTransitionTo(JobState.ENQUEUED));
        }

        @Test
        @DisplayName("cannot transition to SUCCEEDED")
        void cannotTransitionToSucceeded() {
            assertFalse(JobState.ENQUEUED.canTransitionTo(JobState.SUCCEEDED));
        }
    }

    @Nested
    @DisplayName("PROCESSING transitions")
    class ProcessingTransitions {

        @Test
        @DisplayName("can transition to SUCCEEDED")
        void canTransitionToSucceeded() {
            assertTrue(JobState.PROCESSING.canTransitionTo(JobState.SUCCEEDED));
        }

        @Test
        @DisplayName("can transition to FAILED")
        void canTransitionToFailed() {
            assertTrue(JobState.PROCESSING.canTransitionTo(JobState.FAILED));
        }

        @Test
        @DisplayName("can transition to CANCELLED")
        void canTransitionToCancelled() {
            assertTrue(JobState.PROCESSING.canTransitionTo(JobState.CANCELLED));
        }

        @Test
        @DisplayName("can transition to ABANDONED")
        void canTransitionToAbandoned() {
            assertTrue(JobState.PROCESSING.canTransitionTo(JobState.ABANDONED));
        }

        @Test
        @DisplayName("cannot transition to ENQUEUED")
        void cannotTransitionToEnqueued() {
            assertFalse(JobState.PROCESSING.canTransitionTo(JobState.ENQUEUED));
        }

        @Test
        @DisplayName("can transition to DEAD_LETTER (exhausted failure / timeout)")
        void canTransitionToDeadLetter() {
            assertTrue(JobState.PROCESSING.canTransitionTo(JobState.DEAD_LETTER));
        }
    }

    @Nested
    @DisplayName("SUCCEEDED transitions")
    class SucceededTransitions {

        @Test
        @DisplayName("cannot transition to any state (terminal)")
        void isTerminal() {
            for (JobState target : JobState.values()) {
                assertFalse(
                        JobState.SUCCEEDED.canTransitionTo(target),
                        "SUCCEEDED should not be able to transition to " + target);
            }
        }
    }

    @Nested
    @DisplayName("FAILED transitions")
    class FailedTransitions {

        @Test
        @DisplayName("can transition to ENQUEUED (retry)")
        void canTransitionToEnqueued() {
            assertTrue(JobState.FAILED.canTransitionTo(JobState.ENQUEUED));
        }

        @Test
        @DisplayName("can transition to DEAD_LETTER")
        void canTransitionToDeadLetter() {
            assertTrue(JobState.FAILED.canTransitionTo(JobState.DEAD_LETTER));
        }

        @Test
        @DisplayName("cannot transition to SUCCEEDED")
        void cannotTransitionToSucceeded() {
            assertFalse(JobState.FAILED.canTransitionTo(JobState.SUCCEEDED));
        }

        @Test
        @DisplayName("cannot transition to PROCESSING")
        void cannotTransitionToProcessing() {
            assertFalse(JobState.FAILED.canTransitionTo(JobState.PROCESSING));
        }
    }

    @Nested
    @DisplayName("CANCELLED transitions")
    class CancelledTransitions {

        @Test
        @DisplayName("is terminal — cannot transition to any state")
        void isTerminal() {
            for (JobState target : JobState.values()) {
                assertFalse(
                        JobState.CANCELLED.canTransitionTo(target),
                        "CANCELLED should not be able to transition to " + target);
            }
        }
    }

    @Nested
    @DisplayName("ABANDONED transitions")
    class AbandonedTransitions {

        @Test
        @DisplayName("can transition to ENQUEUED (retry)")
        void canTransitionToEnqueued() {
            assertTrue(JobState.ABANDONED.canTransitionTo(JobState.ENQUEUED));
        }

        @Test
        @DisplayName("can transition to DEAD_LETTER")
        void canTransitionToDeadLetter() {
            assertTrue(JobState.ABANDONED.canTransitionTo(JobState.DEAD_LETTER));
        }

        @Test
        @DisplayName("can transition to SUCCEEDED (late handler completion race fix)")
        void canTransitionToSucceeded() {
            assertTrue(JobState.ABANDONED.canTransitionTo(JobState.SUCCEEDED));
        }

        @Test
        @DisplayName("can transition to FAILED (late handler completion race fix)")
        void canTransitionToFailed() {
            assertTrue(JobState.ABANDONED.canTransitionTo(JobState.FAILED));
        }

        @Test
        @DisplayName("cannot transition to PROCESSING")
        void cannotTransitionToProcessing() {
            assertFalse(JobState.ABANDONED.canTransitionTo(JobState.PROCESSING));
        }

        @Test
        @DisplayName("cannot transition to CANCELLED")
        void cannotTransitionToCancelled() {
            assertFalse(JobState.ABANDONED.canTransitionTo(JobState.CANCELLED));
        }
    }

    @Nested
    @DisplayName("DEAD_LETTER transitions")
    class DeadLetterTransitions {

        @Test
        @DisplayName("is terminal — cannot transition to any state")
        void isTerminal() {
            for (JobState target : JobState.values()) {
                assertFalse(
                        JobState.DEAD_LETTER.canTransitionTo(target),
                        "DEAD_LETTER should not be able to transition to " + target);
            }
        }
    }
}
