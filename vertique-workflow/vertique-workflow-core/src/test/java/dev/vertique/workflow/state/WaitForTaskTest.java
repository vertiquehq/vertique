// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link WaitReason.WaitForTask} record shape: required fields ({@code stepId},
 * {@code taskId}) and nullable fields ({@code dueDateTimerId}, {@code dueAt}) when no due-date is
 * configured.
 */
class WaitForTaskTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TIMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant DUE_AT = Instant.parse("2026-06-01T12:00:00Z");

    // --- Full population (with due-date) ---

    @Nested
    @DisplayName("with due-date")
    class WithDueDate {

        @Test
        @DisplayName("all four fields round-trip through getters")
        void allFieldsRoundTrip() {
            WaitReason.WaitForTask wait = new WaitReason.WaitForTask("review", TASK_ID, TIMER_ID, DUE_AT);

            assertThat(wait.stepId()).isEqualTo("review");
            assertThat(wait.taskId()).isEqualTo(TASK_ID);
            assertThat(wait.dueDateTimerId()).isEqualTo(TIMER_ID);
            assertThat(wait.dueAt()).isEqualTo(DUE_AT);
        }
    }

    // --- No due-date ---

    @Nested
    @DisplayName("without due-date")
    class WithoutDueDate {

        @Test
        @DisplayName("dueDateTimerId and dueAt may be null")
        void nullableDueDateFields() {
            WaitReason.WaitForTask wait = new WaitReason.WaitForTask("review", TASK_ID, null, null);

            assertThat(wait.stepId()).isEqualTo("review");
            assertThat(wait.taskId()).isEqualTo(TASK_ID);
            assertThat(wait.dueDateTimerId()).isNull();
            assertThat(wait.dueAt()).isNull();
        }
    }

    // --- Sealed-sum exhaustiveness ---

    @Nested
    @DisplayName("WaitReason sealed-sum exhaustiveness")
    class SealedSumExhaustiveness {

        @Test
        @DisplayName("switch expression covers WaitForSignal, WaitForTimer, and WaitForTask without default")
        void switchCoversAllPermits() {
            WaitReason waitTask = new WaitReason.WaitForTask("review", TASK_ID, null, null);
            WaitReason waitSignal = new WaitReason.WaitForSignal("order.shipped", null, null, null);
            WaitReason waitTimer = new WaitReason.WaitForTimer("wait-step", TIMER_ID, DUE_AT);

            assertThat(kind(waitTask)).isEqualTo("task");
            assertThat(kind(waitSignal)).isEqualTo("signal");
            assertThat(kind(waitTimer)).isEqualTo("timer");
        }

        private static String kind(WaitReason reason) {
            return switch (reason) {
                case WaitReason.WaitForTask ignored -> "task";
                case WaitReason.WaitForSignal ignored -> "signal";
                case WaitReason.WaitForTimer ignored -> "timer";
            };
        }
    }
}
