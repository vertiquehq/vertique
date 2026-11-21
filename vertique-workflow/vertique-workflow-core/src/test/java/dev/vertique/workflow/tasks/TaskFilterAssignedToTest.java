// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@code assignedTo} component of {@link TaskFilter}. */
class TaskFilterAssignedToTest {

    private static final TaskPrincipals ALICE = TaskPrincipals.ofUser("alice", Set.of("r"), Set.of("q"));

    @Test
    @DisplayName("empty and the retained constructor leave assignedTo null")
    void defaultsToNull() {
        assertThat(TaskFilter.empty().assignedTo()).isNull();
        assertThat(new TaskFilter(null, null, null, null, null, null, null, null, false, null, "approval").assignedTo())
                .isNull();
    }

    @Test
    @DisplayName("withAssignedTo sets it and every other with* method preserves it")
    void withAssignedToIsPreserved() {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        TaskFilter filter = TaskFilter.empty()
                .withAssignedTo(ALICE)
                .withStatus(TaskStatus.OPEN)
                .withWorkflowId(workflowId)
                .withDueBefore(Instant.EPOCH)
                .withSubjectType("invoice")
                .withSubjectId("1")
                .withIncludeArchived(true)
                .withScopeKey("tenant-a")
                .withTaskType("approval");

        assertThat(filter.assignedTo()).isEqualTo(ALICE);
        assertThat(filter.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(filter.workflowId()).isEqualTo(workflowId);
        assertThat(filter.taskType()).isEqualTo("approval");
        assertThat(filter.withAssignedTo(null).assignedTo()).isNull();
    }

    @Test
    @DisplayName("refuses assignedTo together with a single assignee kind, in either order")
    void exclusiveWithSingleAssignee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskFilter.empty().withAssigneeUser("bob").withAssignedTo(ALICE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskFilter.empty().withAssignedTo(ALICE).withAssigneeRole("r"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskFilter.empty().withAssignedTo(ALICE).withAssigneeQueue("q"));
    }
}
