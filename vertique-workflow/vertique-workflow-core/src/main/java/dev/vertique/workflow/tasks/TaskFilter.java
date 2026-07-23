// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Query filter for {@link TaskStore#findByFilter}.
 *
 * <p>All fields are optional ({@code @Nullable}). Providing no fields returns all tasks subject
 * to the pagination cursor. Providing multiple fields produces a conjunction (AND). The
 * {@code subjectType} / {@code subjectId} pair filters via a JOIN to
 * {@code workflow_instances.subject_type} / {@code subject_id} since the tasks table does not
 * store subject columns directly.
 *
 * @param assigneeUser filter by assignment to this specific user id; null means no filter
 * @param assigneeRole filter by assignment to this specific role id; null means no filter
 * @param assigneeQueue filter by assignment to this specific queue name; null means no filter
 * @param workflowId filter by workflow instance; null means no filter
 * @param status filter by task status; null means no filter
 * @param dueBefore filter to tasks whose {@code due_at} is before this instant; null means no
 *     filter
 * @param subjectType filter by the subject type of the owning workflow instance; null means no
 *     filter
 * @param subjectId filter by the subject id of the owning workflow instance; null means no filter
 * @param includeArchived when {@code true}, includes tasks belonging to archived workflow
 *     instances; defaults to {@code false}
 */
public record TaskFilter(
        @Nullable String assigneeUser,
        @Nullable String assigneeRole,
        @Nullable String assigneeQueue,
        @Nullable WorkflowInstanceId workflowId,
        @Nullable TaskStatus status,
        @Nullable Instant dueBefore,
        @Nullable String subjectType,
        @Nullable String subjectId,
        boolean includeArchived) {

    /**
     * Validates that at most one of {@code assigneeUser} / {@code assigneeRole} /
     * {@code assigneeQueue} is set. A row in {@code workflow_tasks} has exactly one
     * {@code assignee_type}, so combining two would always return zero rows; rather than silently
     * masking the misuse, fail loud at filter construction.
     */
    public TaskFilter {
        int assigneeCount = 0;
        if (assigneeUser != null) assigneeCount++;
        if (assigneeRole != null) assigneeCount++;
        if (assigneeQueue != null) assigneeCount++;
        if (assigneeCount > 1) {
            throw new IllegalArgumentException(
                    "TaskFilter accepts at most one of assigneeUser/assigneeRole/assigneeQueue;"
                            + " a task row has a single assignee_type. Use a separate query per assignee kind.");
        }
    }

    // --- Factory ---

    /**
     * Returns an empty filter that matches all tasks.
     *
     * @return an all-null {@code TaskFilter}
     */
    public static TaskFilter empty() {
        return new TaskFilter(null, null, null, null, null, null, null, null, false);
    }

    // --- Fluent with* methods ---

    /**
     * Returns a copy of this filter with the {@code assigneeUser} field set.
     *
     * @param assigneeUser the user id to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withAssigneeUser(@Nullable String assigneeUser) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code assigneeRole} field set.
     *
     * @param assigneeRole the role id to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withAssigneeRole(@Nullable String assigneeRole) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code assigneeQueue} field set.
     *
     * @param assigneeQueue the queue name to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withAssigneeQueue(@Nullable String assigneeQueue) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code workflowId} field set.
     *
     * @param workflowId the workflow instance id to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withWorkflowId(@Nullable WorkflowInstanceId workflowId) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code status} field set.
     *
     * @param status the task status to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withStatus(@Nullable TaskStatus status) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code dueBefore} field set.
     *
     * @param dueBefore the cutoff instant for due-date filtering; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withDueBefore(@Nullable Instant dueBefore) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code subjectType} field set.
     *
     * @param subjectType the subject type string to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withSubjectType(@Nullable String subjectType) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code subjectId} field set.
     *
     * @param subjectId the subject id string to filter by; null removes the filter
     * @return a new {@code TaskFilter} with the updated field
     */
    public TaskFilter withSubjectId(@Nullable String subjectId) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }

    /**
     * Returns a copy of this filter with the {@code includeArchived} flag set.
     *
     * <p>When {@code true}, tasks belonging to archived workflow instances are included in the
     * result. Defaults to {@code false}.
     *
     * @param includeArchived {@code true} to include archived instances; {@code false} to exclude them
     * @return a new {@code TaskFilter} with the updated flag and all other fields preserved
     */
    public TaskFilter withIncludeArchived(boolean includeArchived) {
        return new TaskFilter(
                assigneeUser,
                assigneeRole,
                assigneeQueue,
                workflowId,
                status,
                dueBefore,
                subjectType,
                subjectId,
                includeArchived);
    }
}
