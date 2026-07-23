// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * Persisted state of a single fan-in join (PRD-WF-002).
 *
 * <p>Stored in {@code workflow_join_states}, primary key
 * {@code (workflow_id, fork_step_id, join_step_id)}. The CAS on {@link #version()} is the unique-
 * writer guarantee for the join decision: only the first concurrent branch completion that wins
 * the version race can transition the row out of {@link JoinStateStatus#OPEN}, so {@code
 * FAN_IN_COMPLETED} / {@code FAN_IN_FAILED} history entries are written exactly once.
 *
 * @param workflowId parent workflow instance id
 * @param forkStepId step id of the matching {@link dev.vertique.workflow.plan.ForkNode}
 * @param joinStepId step id of the matching {@link dev.vertique.workflow.plan.JoinNode}
 * @param policy persisted form of the join's policy
 * @param status lifecycle status (OPEN, COMPLETED, FAILED)
 * @param winningBranchId branch id that decided the join when {@code status != OPEN}; null while
 *     the join is still open
 * @param decidedAt timestamp when the join was decided; null while still open
 * @param version optimistic-concurrency token; CAS-incremented on each update
 * @param createdAt insert timestamp
 * @param updatedAt last-update timestamp
 */
public record JoinState(
        WorkflowInstanceId workflowId,
        String forkStepId,
        String joinStepId,
        JoinPolicyType policy,
        JoinStateStatus status,
        @Nullable String winningBranchId,
        @Nullable Instant decidedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Compact constructor enforcing non-null required fields.
     */
    public JoinState {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(forkStepId, "forkStepId");
        Objects.requireNonNull(joinStepId, "joinStepId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
