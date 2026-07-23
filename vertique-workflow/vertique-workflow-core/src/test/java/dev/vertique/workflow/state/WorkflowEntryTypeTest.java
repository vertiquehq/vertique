// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;

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
}
