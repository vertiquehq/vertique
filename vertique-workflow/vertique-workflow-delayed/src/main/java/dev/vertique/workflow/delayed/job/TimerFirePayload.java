// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Payload for the {@link WorkflowTimerFireJob} delayed job.
 *
 * <p>Carries the identifiers required to fire a workflow timer: the stable timer UUID (used to
 * lock the {@code workflow_timers} row) and the workflow instance ID (cross-checked against the
 * locked row before dispatching engine callbacks).
 *
 * <p>{@code timerId} and {@code workflowId} are required. {@code branchTokenId} is retained for
 * wire compatibility with previously enqueued jobs but is unused: production construction sites
 * always pass {@code null}, and {@link WorkflowTimerFireExecutor} resolves branch identity from
 * the locked {@link dev.vertique.workflow.timer.TimerRecord} rather than from this field.
 *
 * @param timerId       the stable UUID identifying the timer row in {@code workflow_timers}
 * @param workflowId    the workflow instance that owns this timer
 * @param branchTokenId unused; always {@code null} on newly enqueued jobs
 */
public record TimerFirePayload(
        UUID timerId,
        WorkflowInstanceId workflowId,
        @Nullable UUID branchTokenId) {}
