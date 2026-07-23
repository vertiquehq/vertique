// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Public command value type carrying the inputs for a task-reassignment operation.
 *
 * <p>Passed to {@code TaskService.reassign(...)} (cycle 3) and routed to
 * {@link TransactionalTaskCallbacks#taskReassigned}. The command is idempotency-keyed so that a
 * retry of the same reassignment (same actor, same target assignment, same key) is observably a
 * no-op rather than producing duplicate {@code TASK_REASSIGNED} history entries.
 *
 * <p>A blank {@code reason} string is normalized to {@code null} before fingerprint computation so
 * that callers passing different blank-reason representations are treated as logically equivalent.
 *
 * @param taskId the stable UUID of the task to reassign; must not be null
 * @param newAssignment the target assignment to apply; must be a literal (user, role, or queue);
 *     must not be null
 * @param reassignedBy the actor performing the reassignment; required for audit; must not be null
 * @param idempotencyKey caller-supplied key used to de-duplicate retries; must not be null or blank
 * @param reason optional free-text audit note (e.g., {@code "out of office"}); null is acceptable;
 *     blank strings are treated as null
 */
public record TaskReassignmentCommand(
        UUID taskId,
        TaskAssignment newAssignment,
        WorkflowActor reassignedBy,
        String idempotencyKey,
        @Nullable String reason) {

    /**
     * Validates required fields and normalizes a blank {@code reason} to {@code null}.
     *
     * @throws NullPointerException if {@code taskId}, {@code newAssignment}, {@code reassignedBy},
     *     or {@code idempotencyKey} is null
     * @throws IllegalArgumentException if {@code idempotencyKey} is blank
     */
    public TaskReassignmentCommand {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(newAssignment, "newAssignment");
        Objects.requireNonNull(reassignedBy, "reassignedBy");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        // Normalize blank reason to null for fingerprint consistency
        if (reason != null && reason.isBlank()) {
            reason = null;
        }
    }
}
