// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.tasks.TaskStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that all expected {@link WorkflowEntryType} enum constants are present, including the
 * cycle-4 {@link WorkflowEntryType#TASK_REMINDER_FIRED} value.
 */
class WorkflowEntryTypeTest {

    @Test
    @DisplayName("TASK_REMINDER_FIRED is present")
    void taskReminderFiredPresent() {
        assertThat(WorkflowEntryType.valueOf("TASK_REMINDER_FIRED")).isEqualTo(WorkflowEntryType.TASK_REMINDER_FIRED);
    }

    @Test
    @DisplayName("all cycle-1/2/3/4 TASK_* constants are present")
    void taskConstantsPresent() {
        assertThat(WorkflowEntryType.values())
                .extracting(WorkflowEntryType::name)
                .contains(
                        "TASK_CREATED",
                        "TASK_COMPLETED",
                        "TASK_CANCELLED",
                        "TASK_EXPIRED",
                        "TASK_REASSIGNED",
                        "TASK_REMINDER_FIRED");
    }

    @Test
    @DisplayName("appends the recorded-execution kinds after the existing constants")
    void appendsRecordedExecutionKindsAfterExistingConstants() {
        // Given the 48 existing constants in their released order and the 13 recorded-execution kinds
        List<String> existing = List.of(
                "START",
                "SIGNAL_RECEIVED",
                "CANCELLED",
                "RETRIED",
                "SIDE_EFFECT_RECORDED",
                "COMPENSATING_START",
                "COMPENSATING_STEP",
                "COMPLETED",
                "FAILED",
                "COMPENSATED",
                "TIMER_SCHEDULED",
                "TIMER_FIRED",
                "TIMEOUT",
                "TIMER_CANCELLED",
                "TIMER_FAILED",
                "TASK_CREATED",
                "TASK_COMPLETED",
                "TASK_CANCELLED",
                "TASK_EXPIRED",
                "TASK_REASSIGNED",
                "TASK_REMINDER_FIRED",
                "FORK_DISPATCHED",
                "BRANCH_CREATED",
                "BRANCH_WAIT",
                "BRANCH_RESUMED",
                "BRANCH_COMPLETED",
                "BRANCH_FAILED",
                "BRANCH_RETRY_SCHEDULED",
                "BRANCH_SUPERSEDED",
                "BRANCH_EXPIRED",
                "BRANCH_CANCELLED",
                "BRANCH_COMPENSATING_START",
                "BRANCH_COMPENSATING_STEP",
                "BRANCH_COMPENSATED",
                "BRANCH_LATE_RESULT_IGNORED",
                "FAN_IN_EVALUATED",
                "FAN_IN_COMPLETED",
                "FAN_IN_FAILED",
                "BRANCH_TASK_CREATED",
                "BRANCH_TASK_COMPLETED",
                "BRANCH_TASK_DUE_EXPIRED",
                "BRANCH_TASK_REMINDER_FIRED",
                "BRANCH_TIMER_FIRED",
                "BRANCH_TIMER_SCHEDULED",
                "BRANCH_TIMER_TIMEOUT_FIRED",
                "BRANCH_SIGNAL_TIMEOUT_SCHEDULED",
                "BRANCH_LATE_CALLBACK_IGNORED",
                "WORKFLOW_MIGRATED");
        List<String> appended = List.of(
                "COMMAND_RECORDED",
                "COMMAND_RECEIVED",
                "COMMAND_COMPLETED",
                "COMMAND_REJECTED",
                "COMMAND_STALE",
                "COMMAND_DEFERRED",
                "COMMAND_DEAD_LETTERED",
                "COMMAND_CANCELLED",
                "COMMAND_SUPERSEDED",
                "SIGNAL_PARKED",
                "SIGNAL_APPLIED",
                "DECISION",
                "STATE_BASELINE");

        // When the constants are read in declaration order
        List<String> names = Arrays.stream(WorkflowEntryType.values())
                .map(WorkflowEntryType::name)
                .toList();

        // Then the existing constants are unchanged in order and the new kinds follow in order
        assertThat(names).hasSize(existing.size() + appended.size());
        assertThat(names.subList(0, existing.size())).containsExactlyElementsOf(existing);
        assertThat(names.subList(existing.size(), names.size())).containsExactlyElementsOf(appended);
    }

    @Test
    @DisplayName("PENDING is the last workflow status and COMPLETING the last task status")
    void pendingAndCompletingAreAppendedLast() {
        // Given the workflow and task status enums
        // When their constants are read in declaration order
        WorkflowStatus[] workflowStatuses = WorkflowStatus.values();
        TaskStatus[] taskStatuses = TaskStatus.values();

        // Then the new constants are last
        assertThat(workflowStatuses[workflowStatuses.length - 1].name()).isEqualTo("PENDING");
        assertThat(taskStatuses[taskStatuses.length - 1].name()).isEqualTo("COMPLETING");
    }
}
