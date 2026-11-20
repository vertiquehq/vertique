// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the {@code taskType} component of {@link TaskFilter} and {@link TaskRecord}. */
class TaskFilterTaskTypeTest {

    @Test
    @DisplayName("empty() has no task type and withTaskType sets and clears it")
    void withTaskTypeSetsAndClears() {
        assertThat(TaskFilter.empty().taskType()).isNull();
        assertThat(TaskFilter.empty().withTaskType("approval").taskType()).isEqualTo("approval");
        assertThat(TaskFilter.empty()
                        .withTaskType("approval")
                        .withTaskType(null)
                        .taskType())
                .isNull();
    }

    @Test
    @DisplayName("withTaskType keeps every other component")
    void withTaskTypeCarriesTheOtherComponents() {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        Instant due = Instant.parse("2026-12-31T00:00:00Z");

        TaskFilter filter = new TaskFilter(
                        null, "role", null, workflowId, TaskStatus.OPEN, due, "order", "42", true, "scope", null)
                .withTaskType("approval");

        assertThat(filter)
                .isEqualTo(new TaskFilter(
                        null,
                        "role",
                        null,
                        workflowId,
                        TaskStatus.OPEN,
                        due,
                        "order",
                        "42",
                        true,
                        "scope",
                        "approval"));
    }

    @Test
    @DisplayName("every other wither keeps the task type")
    void everyWitherCarriesTheTaskType() {
        TaskFilter base = TaskFilter.empty().withTaskType("approval");
        List<UnaryOperator<TaskFilter>> withers = List.of(
                f -> f.withAssigneeUser("alice"),
                f -> f.withAssigneeRole("role"),
                f -> f.withAssigneeQueue("queue"),
                f -> f.withWorkflowId(new WorkflowInstanceId(UUID.randomUUID())),
                f -> f.withStatus(TaskStatus.OPEN),
                f -> f.withDueBefore(Instant.EPOCH),
                f -> f.withSubjectType("order"),
                f -> f.withSubjectId("42"),
                f -> f.withIncludeArchived(true),
                f -> f.withScopeKey("scope"));

        for (UnaryOperator<TaskFilter> wither : withers) {
            assertThat(wither.apply(base).taskType()).isEqualTo("approval");
        }
    }

    @Test
    @DisplayName("the earlier constructors leave the task type null")
    void earlierConstructorsHaveNoTaskType() {
        assertThat(new TaskFilter(null, null, null, null, null, null, null, null, false).taskType())
                .isNull();
        assertThat(new TaskFilter(null, null, null, null, null, null, null, null, false, "scope").taskType())
                .isNull();
    }

    @Test
    @DisplayName("a task record keeps its task type, and the earlier constructors leave it null")
    void taskRecordKeepsTaskType() {
        UUID taskId = UUID.randomUUID();
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        List<TaskDecisionDescriptor> decisions =
                List.of(new TaskDecisionDescriptor("approve", String.class.getName(), "ship"));
        TaskRecord typed = new TaskRecord(
                taskId,
                workflowId,
                "review",
                new TaskAssignment.Role("compliance"),
                TaskStatus.OPEN,
                decisions,
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                null,
                (WorkflowActor) null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "default",
                null,
                null,
                "approval");
        TaskRecord untyped = new TaskRecord(
                taskId,
                workflowId,
                "review",
                new TaskAssignment.Role("compliance"),
                TaskStatus.OPEN,
                decisions,
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "default",
                null,
                null);

        assertThat(typed.taskType()).isEqualTo("approval");
        assertThat(untyped.taskType()).isNull();
    }
}
