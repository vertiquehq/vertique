// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * A plan node that aggregates branch results from a matching {@link ForkNode} and advances the
 * workflow according to its {@link JoinPolicy}.
 *
 * <p>The engine evaluates a {@code JoinNode} only via the join-state row it shares with its
 * matching {@code ForkNode}. Direct execution of a {@code JoinNode} as a forward step is a
 * definition error: the workflow only reaches the next step after the join policy is satisfied
 * and the branch result reducer has produced a new state.
 *
 * @param stepId unique step identifier within the plan; matches the corresponding
 *     {@link ForkNode#joinStepId()}
 * @param policy the join policy (see {@link AllRequiredJoinPolicy}, {@link FirstSuccessJoinPolicy},
 *     {@link FirstFailureJoinPolicy})
 * @param branchResultReducerCallbackId callback id of the {@code BiFunction<S, Map<String,
 *     BranchResult>, S>} reducer that combines per-branch results into the post-join workflow
 *     state
 * @param nextStepId step to advance to when the join completes successfully
 * @param failureStepId step to advance to when the join fails (e.g., a required branch failed
 *     under {@code ALL_REQUIRED}, or a branch reached terminal failure under {@code FIRST_FAILURE});
 *     non-null when {@code policy} is {@link FirstFailureJoinPolicy} (validated by
 *     {@code WorkflowPlanValidator})
 */
public record JoinNode(
        String stepId,
        JoinPolicy policy,
        CallbackId branchResultReducerCallbackId,
        String nextStepId,
        @Nullable String failureStepId)
        implements WorkflowNode {

    /**
     * Compact constructor enforcing non-null fields and non-empty ids.
     */
    public JoinNode {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(branchResultReducerCallbackId, "branchResultReducerCallbackId");
        Objects.requireNonNull(nextStepId, "nextStepId");
        if (stepId.isEmpty()) {
            throw new IllegalArgumentException("stepId must be non-empty");
        }
        if (nextStepId.isEmpty()) {
            throw new IllegalArgumentException("nextStepId must be non-empty");
        }
        if (failureStepId != null && failureStepId.isEmpty()) {
            throw new IllegalArgumentException("failureStepId, when set, must be non-empty");
        }
    }
}
