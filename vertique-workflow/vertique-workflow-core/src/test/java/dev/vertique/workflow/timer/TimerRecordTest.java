// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the new {@code purpose} and {@code taskId} fields on {@link TimerRecord}, focusing on
 * the compact-constructor pairing validation.
 */
class TimerRecordTest {

    private static final UUID TIMER_ID = UUID.randomUUID();
    private static final WorkflowInstanceId WORKFLOW_ID = new WorkflowInstanceId(UUID.randomUUID());
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final Instant FIRE_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant SCHEDULED_AT = Instant.parse("2026-05-08T12:00:00Z");

    // --- Valid combinations ---

    @Nested
    @DisplayName("valid purpose–taskId combinations")
    class ValidCombinations {

        @Test
        @DisplayName("STANDALONE + null taskId is accepted")
        void standaloneNullTaskId() {
            TimerRecord r = buildRecord(TimerPurpose.STANDALONE, null);
            assertThat(r.purpose()).isEqualTo(TimerPurpose.STANDALONE);
            assertThat(r.taskId()).isNull();
        }

        @Test
        @DisplayName("SIGNAL_TIMEOUT + null taskId is accepted")
        void signalTimeoutNullTaskId() {
            TimerRecord r = buildRecord(TimerPurpose.SIGNAL_TIMEOUT, null);
            assertThat(r.purpose()).isEqualTo(TimerPurpose.SIGNAL_TIMEOUT);
            assertThat(r.taskId()).isNull();
        }

        @Test
        @DisplayName("TASK_DUE + non-null taskId is accepted")
        void taskDueNonNullTaskId() {
            TimerRecord r = buildRecord(TimerPurpose.TASK_DUE, TASK_ID);
            assertThat(r.purpose()).isEqualTo(TimerPurpose.TASK_DUE);
            assertThat(r.taskId()).isEqualTo(TASK_ID);
        }

        @Test
        @DisplayName("TASK_REMINDER + non-null taskId is accepted")
        void taskReminderNonNullTaskId() {
            TimerRecord r = buildRecord(TimerPurpose.TASK_REMINDER, TASK_ID);
            assertThat(r.purpose()).isEqualTo(TimerPurpose.TASK_REMINDER);
            assertThat(r.taskId()).isEqualTo(TASK_ID);
        }
    }

    // --- Invalid combinations ---

    @Nested
    @DisplayName("invalid purpose–taskId combinations")
    class InvalidCombinations {

        @Test
        @DisplayName("TASK_REMINDER + null taskId throws IllegalArgumentException")
        void taskReminderNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildRecord(TimerPurpose.TASK_REMINDER, null))
                    .withMessageContaining("TASK_REMINDER");
        }

        @Test
        @DisplayName("TASK_DUE + null taskId throws IllegalArgumentException")
        void taskDueNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildRecord(TimerPurpose.TASK_DUE, null))
                    .withMessageContaining("TASK_DUE");
        }

        @Test
        @DisplayName("STANDALONE + non-null taskId throws IllegalArgumentException")
        void standaloneNonNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildRecord(TimerPurpose.STANDALONE, TASK_ID))
                    .withMessageContaining("STANDALONE");
        }

        @Test
        @DisplayName("SIGNAL_TIMEOUT + non-null taskId throws IllegalArgumentException")
        void signalTimeoutNonNullTaskId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildRecord(TimerPurpose.SIGNAL_TIMEOUT, TASK_ID))
                    .withMessageContaining("SIGNAL_TIMEOUT");
        }
    }

    // --- Helpers ---

    /**
     * Builds a {@link TimerRecord} with a fixed set of base fields and the supplied purpose + taskId.
     *
     * @param purpose the timer purpose
     * @param taskId  the task id (may be null)
     * @return a constructed timer record
     */
    private static TimerRecord buildRecord(TimerPurpose purpose, UUID taskId) {
        return new TimerRecord(
                TIMER_ID,
                WORKFLOW_ID,
                "step-1",
                FIRE_AT,
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                SCHEDULED_AT,
                null,
                null,
                null,
                null,
                purpose,
                taskId,
                null, // branchTokenId
                null, // forkStepId
                null,
                DurableMetadata.empty()); // branchId
    }
}
