// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TASK_CREATED} history
 * entry.
 *
 * <p>Recorded when the engine creates a task and begins waiting at a
 * {@link dev.vertique.workflow.plan.HumanTaskNode} step.
 *
 * @param stepId the plan step id that caused the task to be created
 * @param taskId the stable UUID assigned to the task
 * @param assignment the initial assignment (user, role, or queue) as resolved at task-creation
 *     time
 * @param dueAt the UTC instant at which the task will expire; null when no due-date is configured
 */
public record TaskCreatedHistoryPayload(
        String stepId,
        UUID taskId,
        TaskAssignment assignment,
        @Nullable Instant dueAt) {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException if {@code stepId}, {@code taskId}, or {@code assignment} is null
     */
    public TaskCreatedHistoryPayload {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(assignment, "assignment");
    }
}
