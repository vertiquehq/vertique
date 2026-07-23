// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that all documented {@link WorkflowEventType} enum constants are present and that
 * {@link Enum#name()} round-trips correctly for each value.
 */
class WorkflowEventTypeTest {

    @Test
    @DisplayName("all 13 documented constants are present")
    void allConstantsPresent() {
        assertThat(WorkflowEventType.values())
                .extracting(WorkflowEventType::name)
                .containsExactlyInAnyOrder(
                        "WORKFLOW_STARTED",
                        "WORKFLOW_COMPLETED",
                        "WORKFLOW_FAILED",
                        "WORKFLOW_CANCELLED",
                        "WORKFLOW_EXPIRED",
                        "WORKFLOW_COMPENSATING",
                        "WORKFLOW_COMPENSATED",
                        "TASK_CREATED",
                        "TASK_COMPLETED",
                        "TASK_CANCELLED",
                        "TASK_EXPIRED",
                        "TASK_REASSIGNED",
                        "TASK_REMINDER");
    }

    @Test
    @DisplayName("name() round-trips via valueOf for every constant")
    void nameRoundTrips() {
        for (WorkflowEventType type : WorkflowEventType.values()) {
            assertThat(WorkflowEventType.valueOf(type.name())).isSameAs(type);
        }
    }
}
