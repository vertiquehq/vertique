// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Payload for the {@link WorkflowTimerFireJob} delayed job.
 *
 * <p>Carries the minimal identifiers required to fire a workflow timer: the stable timer UUID
 * (used to lock the {@code workflow_timers} row), the workflow instance ID (used to invoke
 * {@link dev.vertique.workflow.ops.TransactionalTimerCallbacks} on the engine), and optional
 * branch-identity fields for routing the timer callback back to the owning branch when the timer
 * was created inside a fan-out branch.
 *
 * <p>{@code timerId} and {@code workflowId} are required. {@code branchTokenId} is set only for
 * branch-owned timers; absent from single-path timer queue entries (Jackson tolerates absent
 * fields and deserialises them as {@code null}, preserving backward compatibility).
 *
 * @param timerId       the stable UUID identifying the timer row in {@code workflow_timers}
 * @param workflowId    the workflow instance that owns this timer
 * @param branchTokenId the branch token id when the timer was created inside a fan-out branch;
 *     null for single-path (non-branch) timers
 */
public record TimerFirePayload(
        UUID timerId,
        WorkflowInstanceId workflowId,
        @Nullable UUID branchTokenId) {}
