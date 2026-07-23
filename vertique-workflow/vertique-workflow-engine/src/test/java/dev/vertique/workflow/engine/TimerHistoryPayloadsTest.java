// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.workflow.timer.TimerPurpose;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that each timer-related history payload record constructs correctly and exposes its
 * fields via the generated accessor methods.
 *
 * <p>Also verifies the cycle-4 migration: {@link TimerScheduledHistoryPayload} now uses
 * {@link TimerPurpose} (canonical workflow-core enum) instead of the deleted
 * {@code TimerScheduledKind} local enum. The backward-compat deserializer maps the legacy
 * {@code "WAIT_TIMEOUT"} JSON string to {@link TimerPurpose#SIGNAL_TIMEOUT}.
 */
class TimerHistoryPayloadsTest {

    private static final UUID TIMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T = Instant.ofEpochSecond(1_700_000_000L);

    @Nested
    @DisplayName("TimerScheduledHistoryPayload")
    class Scheduled {

        @Test
        @DisplayName("constructs with STANDALONE purpose and exposes all fields")
        void standalonePurposeRoundTrip() {
            TimerScheduledHistoryPayload p =
                    new TimerScheduledHistoryPayload("timer-step", TIMER_ID, T, TimerPurpose.STANDALONE);

            assertEquals("timer-step", p.stepId());
            assertEquals(TIMER_ID, p.timerId());
            assertEquals(T, p.fireAt());
            assertEquals(TimerPurpose.STANDALONE, p.kind());
        }

        @Test
        @DisplayName("constructs with SIGNAL_TIMEOUT purpose (cycle-4 replacement for WAIT_TIMEOUT)")
        void signalTimeoutPurposeRoundTrip() {
            TimerScheduledHistoryPayload p =
                    new TimerScheduledHistoryPayload("wait-step", TIMER_ID, T, TimerPurpose.SIGNAL_TIMEOUT);
            assertEquals(TimerPurpose.SIGNAL_TIMEOUT, p.kind());
        }

        @Test
        @DisplayName("constructs with TASK_DUE purpose")
        void taskDuePurposeRoundTrip() {
            TimerScheduledHistoryPayload p =
                    new TimerScheduledHistoryPayload("task-step", TIMER_ID, T, TimerPurpose.TASK_DUE);
            assertEquals(TimerPurpose.TASK_DUE, p.kind());
        }

        @Test
        @DisplayName("constructs with TASK_REMINDER purpose (cycle-4 new purpose)")
        void taskReminderPurposeRoundTrip() {
            TimerScheduledHistoryPayload p =
                    new TimerScheduledHistoryPayload("task-step", TIMER_ID, T, TimerPurpose.TASK_REMINDER);
            assertEquals(TimerPurpose.TASK_REMINDER, p.kind());
        }
    }

    @Nested
    @DisplayName("TimerFiredHistoryPayload")
    class Fired {

        @Test
        @DisplayName("constructs and exposes all fields correctly")
        void fieldsRoundTrip() {
            TimerFiredHistoryPayload p = new TimerFiredHistoryPayload("timer-step", TIMER_ID, T, "next-step");

            assertEquals("timer-step", p.stepId());
            assertEquals(TIMER_ID, p.timerId());
            assertEquals(T, p.firedAt());
            assertEquals("next-step", p.nextStepId());
        }
    }

    @Nested
    @DisplayName("TimeoutHistoryPayload")
    class Timeout {

        @Test
        @DisplayName("constructs and exposes all fields correctly")
        void fieldsRoundTrip() {
            TimeoutHistoryPayload p = new TimeoutHistoryPayload("wait-step", TIMER_ID, T, "timeout-step");

            assertEquals("wait-step", p.stepId());
            assertEquals(TIMER_ID, p.timerId());
            assertEquals(T, p.firedAt());
            assertEquals("timeout-step", p.timeoutNextStepId());
        }
    }

    @Nested
    @DisplayName("TimerCancelledHistoryPayload")
    class Cancelled {

        @Test
        @DisplayName("constructs and exposes all fields correctly — SIGNAL_ARRIVED cause")
        void fieldsRoundTrip() {
            TimerCancelledHistoryPayload p =
                    new TimerCancelledHistoryPayload("wait-step", TIMER_ID, T, TimerCancelledCause.SIGNAL_ARRIVED);

            assertEquals("wait-step", p.stepId());
            assertEquals(TIMER_ID, p.timerId());
            assertEquals(T, p.cancelledAt());
            assertEquals(TimerCancelledCause.SIGNAL_ARRIVED, p.cause());
        }

        @Test
        @DisplayName("WORKFLOW_CANCELLED cause is preserved")
        void workflowCancelledCausePreserved() {
            TimerCancelledHistoryPayload p =
                    new TimerCancelledHistoryPayload("wait-step", TIMER_ID, T, TimerCancelledCause.WORKFLOW_CANCELLED);
            assertEquals(TimerCancelledCause.WORKFLOW_CANCELLED, p.cause());
        }
    }

    @Nested
    @DisplayName("TimerFailedHistoryPayload")
    class Failed {

        @Test
        @DisplayName("constructs and exposes all fields correctly")
        void fieldsRoundTrip() {
            TimerFailedHistoryPayload p =
                    new TimerFailedHistoryPayload("timer-step", TIMER_ID, T, "TIMEOUT", "job exhausted retries");

            assertEquals("timer-step", p.stepId());
            assertEquals(TIMER_ID, p.timerId());
            assertEquals(T, p.failedAt());
            assertEquals("TIMEOUT", p.errorType());
            assertEquals("job exhausted retries", p.errorMessage());
        }
    }

    @Nested
    @DisplayName("TimerPurpose enum (cycle-4: replaces deleted TimerScheduledKind)")
    class ScheduledKind {

        @Test
        @DisplayName("has exactly STANDALONE, SIGNAL_TIMEOUT, TASK_DUE, and TASK_REMINDER constants")
        void enumConstants() {
            TimerPurpose[] values = TimerPurpose.values();
            assertEquals(4, values.length);
            assertInstanceOf(TimerPurpose.class, TimerPurpose.STANDALONE);
            assertInstanceOf(TimerPurpose.class, TimerPurpose.SIGNAL_TIMEOUT);
            assertInstanceOf(TimerPurpose.class, TimerPurpose.TASK_DUE);
            assertInstanceOf(TimerPurpose.class, TimerPurpose.TASK_REMINDER);
        }
    }

    @Nested
    @DisplayName("TimerCancelledCause enum")
    class CancelledCause {

        @Test
        @DisplayName("has exactly SIGNAL_ARRIVED, WORKFLOW_CANCELLED, TASK_COMPLETED, and TASK_CLOSED constants")
        void enumConstants() {
            TimerCancelledCause[] values = TimerCancelledCause.values();
            assertEquals(4, values.length);
            assertInstanceOf(TimerCancelledCause.class, TimerCancelledCause.SIGNAL_ARRIVED);
            assertInstanceOf(TimerCancelledCause.class, TimerCancelledCause.WORKFLOW_CANCELLED);
            assertInstanceOf(TimerCancelledCause.class, TimerCancelledCause.TASK_COMPLETED);
            assertInstanceOf(TimerCancelledCause.class, TimerCancelledCause.TASK_CLOSED);
        }

        @Test
        @DisplayName("TASK_COMPLETED cause is preserved on the payload")
        void taskCompletedCause() {
            TimerCancelledHistoryPayload p =
                    new TimerCancelledHistoryPayload("task-step", TIMER_ID, T, TimerCancelledCause.TASK_COMPLETED);
            assertEquals(TimerCancelledCause.TASK_COMPLETED, p.cause());
        }
    }
}
