// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TaskReminderFiredHistoryPayload} enforces non-null constraints on its
 * required fields.
 */
class TaskReminderFiredHistoryPayloadTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID TIMER_ID = UUID.randomUUID();
    private static final Instant SCHEDULED_AT = Instant.parse("2026-05-08T12:00:00Z");

    @Test
    @DisplayName("null stepId throws NullPointerException")
    void nullStepId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReminderFiredHistoryPayload(null, TASK_ID, TIMER_ID, 0, SCHEDULED_AT))
                .withMessageContaining("stepId");
    }

    @Test
    @DisplayName("null taskId throws NullPointerException")
    void nullTaskId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReminderFiredHistoryPayload("step-1", null, TIMER_ID, 0, SCHEDULED_AT))
                .withMessageContaining("taskId");
    }

    @Test
    @DisplayName("null timerId throws NullPointerException")
    void nullTimerId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReminderFiredHistoryPayload("step-1", TASK_ID, null, 0, SCHEDULED_AT))
                .withMessageContaining("timerId");
    }

    @Test
    @DisplayName("null scheduledAt throws NullPointerException")
    void nullScheduledAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReminderFiredHistoryPayload("step-1", TASK_ID, TIMER_ID, 0, null))
                .withMessageContaining("scheduledAt");
    }

    @Test
    @DisplayName("valid construction succeeds and fields are accessible")
    void validConstruction() {
        TaskReminderFiredHistoryPayload payload =
                new TaskReminderFiredHistoryPayload("step-1", TASK_ID, TIMER_ID, 2, SCHEDULED_AT);

        org.assertj.core.api.Assertions.assertThat(payload.stepId()).isEqualTo("step-1");
        org.assertj.core.api.Assertions.assertThat(payload.taskId()).isEqualTo(TASK_ID);
        org.assertj.core.api.Assertions.assertThat(payload.timerId()).isEqualTo(TIMER_ID);
        org.assertj.core.api.Assertions.assertThat(payload.reminderIndex()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(payload.scheduledAt()).isEqualTo(SCHEDULED_AT);
    }
}
