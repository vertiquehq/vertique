// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import dev.vertique.workflow.actor.WorkflowActor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TASK_EXPIRED} history
 * entry.
 *
 * <p>Recorded when a task's due-date timer fires before the task is completed.
 * {@code expiredBy} is always {@code WorkflowActor.System("due-date-expired")} in cycle 3.
 *
 * @param stepId the plan step id of the expired task
 * @param taskId the stable UUID of the expired task
 * @param expiredAt the UTC instant at which the task expired
 * @param expiredBy the system actor that triggered the expiry; always
 *     {@code WorkflowActor.System("due-date-expired")} in cycle 3
 */
public record TaskExpiredHistoryPayload(String stepId, UUID taskId, Instant expiredAt, WorkflowActor expiredBy) {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException if any field is null
     */
    public TaskExpiredHistoryPayload {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(expiredAt, "expiredAt");
        Objects.requireNonNull(expiredBy, "expiredBy");
    }
}
