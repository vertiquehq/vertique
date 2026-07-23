// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import dev.vertique.workflow.plan.BranchRetryPolicy;
import dev.vertique.workflow.plan.BranchStart;
import dev.vertique.workflow.plan.ForkNode;
import dev.vertique.workflow.plan.RaceSafety;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent scope for declaring a fan-out fork (PRD-WF-002).
 *
 * <p>Returned by {@link WorkflowBuilder#fork(String)}. Captures the branch declarations and the
 * fork-group retry policy; on {@link #join(String)} the captured state is materialised as a
 * {@link ForkNode} and appended to the parent builder, which is then returned for continued
 * chaining.
 *
 * <p>Branch <em>bodies</em> are authored separately on the parent builder using the existing DSL
 * methods ({@code dispatch}, {@code waitFor}, etc.); each branch's first node must have step id
 * matching the {@code startStepId} declared here. The validator checks the cross-references at
 * registration time.
 *
 * @param <S> the workflow state type
 */
public final class ForkScope<S> {

    private final WorkflowBuilder<S> parent;
    private final String stepId;
    private final List<BranchStart> branches = new ArrayList<>();
    private BranchRetryPolicy retryPolicy = BranchRetryPolicy.none();

    ForkScope(WorkflowBuilder<S> parent, String stepId) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        if (stepId.isEmpty()) {
            throw new IllegalArgumentException("stepId must be non-empty");
        }
    }

    /**
     * Sets the branch-level retry policy applied to every branch in this fork group.
     *
     * <p>Calling {@code retry(...)} more than once replaces the previous policy.
     *
     * @param policy the retry policy
     * @return this scope for chaining
     */
    public ForkScope<S> retry(BranchRetryPolicy policy) {
        this.retryPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Declares a branch with default {@link RaceSafety#NORMAL} race-safety.
     *
     * @param branchId branch identifier; must be unique within this fork group
     * @param startStepId the step id of the branch's first node
     * @return this scope for chaining
     */
    public ForkScope<S> branch(String branchId, String startStepId) {
        return branch(branchId, startStepId, RaceSafety.NORMAL);
    }

    /**
     * Declares a branch with an explicit race-safety classification.
     *
     * @param branchId branch identifier; must be unique within this fork group
     * @param startStepId the step id of the branch's first node
     * @param raceSafety race-safety classification (see {@link RaceSafety})
     * @return this scope for chaining
     */
    public ForkScope<S> branch(String branchId, String startStepId, RaceSafety raceSafety) {
        branches.add(new BranchStart(branchId, startStepId, raceSafety));
        return this;
    }

    /**
     * Terminates the fork declaration and appends the {@link ForkNode} to the parent builder.
     *
     * @param joinStepId the matching join step id (also used to look up the {@link
     *     dev.vertique.workflow.plan.JoinNode} authored separately via {@link
     *     WorkflowBuilder#join(String)})
     * @return the parent builder for continued chaining
     */
    public WorkflowBuilder<S> join(String joinStepId) {
        Objects.requireNonNull(joinStepId, "joinStepId");
        if (joinStepId.isEmpty()) {
            throw new IllegalArgumentException("joinStepId must be non-empty");
        }
        ForkNode fork = new ForkNode(stepId, branches, joinStepId, retryPolicy);
        parent.appendForkNode(fork);
        return parent;
    }
}
