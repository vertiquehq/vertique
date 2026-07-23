// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import java.util.List;
import java.util.Objects;

/**
 * A plan node that fans out to a bounded set of named branches and joins their results at a
 * matching {@link JoinNode}.
 *
 * <p>Reaching a {@code ForkNode} during execution causes the engine to:
 * <ol>
 *   <li>insert one branch token per {@link BranchStart} in declaration order;</li>
 *   <li>insert an open join state for the matching join step;</li>
 *   <li>park the workflow instance on the join (wait type {@code JOIN}); and</li>
 *   <li>drive each branch's first transition inline — recording side-effect intents and history
 *       entries inside the same transition transaction.</li>
 * </ol>
 *
 * <p>Validation rules enforced by {@code WorkflowPlanValidator}:
 * <ul>
 *   <li>Branch list non-empty, ≤ 16 branches in V1.</li>
 *   <li>Branch ids unique within the fork group.</li>
 *   <li>Every {@link BranchStart#startStepId()} resolves to an in-plan node.</li>
 *   <li>{@link #joinStepId()} resolves to a {@link JoinNode} whose {@code stepId} matches.</li>
 *   <li>No nested fan-out: traversal from each branch's start step must not reach another
 *       {@code ForkNode}.</li>
 * </ul>
 *
 * @param stepId unique step identifier within the plan
 * @param branches ordered list of branch declarations; declaration order is part of the plan hash
 *     and drives compensation traversal
 * @param joinStepId step id of the matching {@link JoinNode}
 * @param retryPolicy branch-level retry policy applied uniformly to all branches in the group
 */
public record ForkNode(String stepId, List<BranchStart> branches, String joinStepId, BranchRetryPolicy retryPolicy)
        implements WorkflowNode {

    /**
     * Compact constructor enforcing non-null fields and copying {@code branches} into an
     * unmodifiable list.
     */
    public ForkNode {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(branches, "branches");
        Objects.requireNonNull(joinStepId, "joinStepId");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (stepId.isEmpty()) {
            throw new IllegalArgumentException("stepId must be non-empty");
        }
        if (joinStepId.isEmpty()) {
            throw new IllegalArgumentException("joinStepId must be non-empty");
        }
        branches = List.copyOf(branches);
    }
}
