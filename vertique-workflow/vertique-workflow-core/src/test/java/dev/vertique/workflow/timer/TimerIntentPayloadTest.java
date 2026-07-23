// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TimerIntentPayload}: non-null validation for {@code fireAt} and {@code purpose},
 * and the taskId-purpose pairing rules.
 */
class TimerIntentPayloadTest {

    private static final Instant FIRE_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final UUID TASK_ID = UUID.randomUUID();

    // --- Non-null validation ---

    @Nested
    @DisplayName("non-null validation")
    class NullValidation {

        @Test
        @DisplayName("null fireAt throws NullPointerException")
        void nullFireAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerIntentPayload(null, TimerPurpose.STANDALONE, null))
                    .withMessageContaining("fireAt");
        }

        @Test
        @DisplayName("null purpose throws NullPointerException")
        void nullPurpose() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerIntentPayload(FIRE_AT, null, null))
                    .withMessageContaining("purpose");
        }
    }

    // --- Purpose–taskId pairing rules ---

    @Nested
    @DisplayName("taskId pairing rules")
    class TaskIdPairingRules {

        @Test
        @DisplayName("TASK_DUE with null taskId throws IllegalArgumentException")
        void taskDueWithNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TimerIntentPayload(FIRE_AT, TimerPurpose.TASK_DUE, null))
                    .withMessageContaining("TASK_DUE");
        }

        @Test
        @DisplayName("TASK_REMINDER with null taskId throws IllegalArgumentException")
        void taskReminderWithNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TimerIntentPayload(FIRE_AT, TimerPurpose.TASK_REMINDER, null))
                    .withMessageContaining("TASK_REMINDER");
        }

        @Test
        @DisplayName("STANDALONE with non-null taskId throws IllegalArgumentException")
        void standaloneWithNonNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TimerIntentPayload(FIRE_AT, TimerPurpose.STANDALONE, TASK_ID))
                    .withMessageContaining("STANDALONE");
        }

        @Test
        @DisplayName("SIGNAL_TIMEOUT with non-null taskId throws IllegalArgumentException")
        void signalTimeoutWithNonNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TimerIntentPayload(FIRE_AT, TimerPurpose.SIGNAL_TIMEOUT, TASK_ID))
                    .withMessageContaining("SIGNAL_TIMEOUT");
        }

        @Test
        @DisplayName("STANDALONE with null taskId is valid")
        void standaloneWithNullTaskId() {
            TimerIntentPayload payload = new TimerIntentPayload(FIRE_AT, TimerPurpose.STANDALONE, null);
            assertThat(payload.purpose()).isEqualTo(TimerPurpose.STANDALONE);
            assertThat(payload.taskId()).isNull();
        }

        @Test
        @DisplayName("SIGNAL_TIMEOUT with null taskId is valid")
        void signalTimeoutWithNullTaskId() {
            TimerIntentPayload payload = new TimerIntentPayload(FIRE_AT, TimerPurpose.SIGNAL_TIMEOUT, null);
            assertThat(payload.purpose()).isEqualTo(TimerPurpose.SIGNAL_TIMEOUT);
            assertThat(payload.taskId()).isNull();
        }

        @Test
        @DisplayName("TASK_DUE with non-null taskId is valid")
        void taskDueWithNonNullTaskId() {
            TimerIntentPayload payload = new TimerIntentPayload(FIRE_AT, TimerPurpose.TASK_DUE, TASK_ID);
            assertThat(payload.purpose()).isEqualTo(TimerPurpose.TASK_DUE);
            assertThat(payload.taskId()).isEqualTo(TASK_ID);
        }

        @Test
        @DisplayName("TASK_REMINDER with non-null taskId is valid")
        void taskReminderWithNonNullTaskId() {
            TimerIntentPayload payload = new TimerIntentPayload(FIRE_AT, TimerPurpose.TASK_REMINDER, TASK_ID);
            assertThat(payload.purpose()).isEqualTo(TimerPurpose.TASK_REMINDER);
            assertThat(payload.taskId()).isEqualTo(TASK_ID);
        }
    }
}
