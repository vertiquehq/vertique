// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import dev.vertique.workflow.plan.AllRequiredJoinPolicy;
import dev.vertique.workflow.plan.BranchResult;
import dev.vertique.workflow.plan.FirstFailureJoinPolicy;
import dev.vertique.workflow.plan.FirstSuccessJoinPolicy;
import dev.vertique.workflow.plan.JoinNode;
import dev.vertique.workflow.plan.JoinPolicy;
import dev.vertique.workflow.registry.CallbackId;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Fluent scope for configuring a fan-in join (PRD-WF-002).
 *
 * <p>Returned by {@link WorkflowBuilder#join(String)}. The caller selects the join policy and
 * registers a reducer (one of {@link #allRequired}, {@link #firstSuccess}, {@link #firstFailure}),
 * configures {@link #toStep(String)} and optionally {@link #onFailure(String)}, then terminates
 * with {@link #endJoin()} to append the {@link JoinNode} and return to the parent builder.
 *
 * @param <S> the workflow state type
 */
public final class JoinScope<S> {

    private final WorkflowBuilder<S> parent;
    private final String stepId;

    @Nullable
    private JoinPolicy policy;

    @Nullable
    private BiFunction<S, Map<String, BranchResult>, S> reducer;

    @Nullable
    private String nextStepId;

    @Nullable
    private String failureStepId;

    JoinScope(WorkflowBuilder<S> parent, String stepId) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        if (stepId.isEmpty()) {
            throw new IllegalArgumentException("stepId must be non-empty");
        }
    }

    /**
     * Selects {@link AllRequiredJoinPolicy} and registers the reducer.
     *
     * @param reducer reducer that combines the per-branch results into the next workflow state
     * @return this scope for chaining
     */
    public JoinScope<S> allRequired(BiFunction<S, Map<String, BranchResult>, S> reducer) {
        return setPolicy(AllRequiredJoinPolicy.INSTANCE, reducer);
    }

    /**
     * Selects {@link FirstSuccessJoinPolicy} and registers the reducer.
     *
     * @param reducer reducer that combines the per-branch results (typically the winner only)
     *     into the next workflow state
     * @return this scope for chaining
     */
    public JoinScope<S> firstSuccess(BiFunction<S, Map<String, BranchResult>, S> reducer) {
        return setPolicy(FirstSuccessJoinPolicy.INSTANCE, reducer);
    }

    /**
     * Selects {@link FirstFailureJoinPolicy} and registers the reducer.
     *
     * <p>{@link FirstFailureJoinPolicy} requires {@link #onFailure(String)} to be set before
     * {@link #endJoin()}; otherwise the validator rejects the plan.
     *
     * @param reducer reducer that combines the per-branch results (typically the failing branch's
     *     diagnostics) into the next workflow state
     * @return this scope for chaining
     */
    public JoinScope<S> firstFailure(BiFunction<S, Map<String, BranchResult>, S> reducer) {
        return setPolicy(FirstFailureJoinPolicy.INSTANCE, reducer);
    }

    /**
     * Sets the next step taken when the join completes successfully.
     *
     * @param nextStepId the step id to advance to
     * @return this scope for chaining
     */
    public JoinScope<S> toStep(String nextStepId) {
        this.nextStepId = Objects.requireNonNull(nextStepId, "nextStepId");
        if (nextStepId.isEmpty()) {
            throw new IllegalArgumentException("nextStepId must be non-empty");
        }
        return this;
    }

    /**
     * Sets the failure route taken when the join fails (e.g., a required branch fails under
     * {@link AllRequiredJoinPolicy}, or the first branch reaches a terminal failure under
     * {@link FirstFailureJoinPolicy}).
     *
     * @param failureStepId the step id to advance to on failure
     * @return this scope for chaining
     */
    public JoinScope<S> onFailure(String failureStepId) {
        this.failureStepId = Objects.requireNonNull(failureStepId, "failureStepId");
        if (failureStepId.isEmpty()) {
            throw new IllegalArgumentException("failureStepId must be non-empty");
        }
        return this;
    }

    /**
     * Terminates the join configuration and appends the {@link JoinNode} to the parent builder,
     * registering the reducer under a deterministic callback id derived from this join's step id.
     *
     * @return the parent builder for continued chaining
     * @throws IllegalStateException if no policy has been selected, or {@link #toStep(String)} was
     *     not called
     */
    public WorkflowBuilder<S> endJoin() {
        if (policy == null || reducer == null) {
            throw new IllegalStateException("JoinScope '" + stepId
                    + "': call allRequired/firstSuccess/firstFailure with a reducer before endJoin()");
        }
        if (nextStepId == null) {
            throw new IllegalStateException("JoinScope '" + stepId + "': call toStep(...) before endJoin()");
        }
        CallbackId reducerId = new CallbackId(stepId + ".reducer");
        JoinNode join = new JoinNode(stepId, policy, reducerId, nextStepId, failureStepId);
        parent.appendJoinNode(join, reducer);
        return parent;
    }

    private JoinScope<S> setPolicy(JoinPolicy newPolicy, BiFunction<S, Map<String, BranchResult>, S> newReducer) {
        Objects.requireNonNull(newPolicy, "policy");
        Objects.requireNonNull(newReducer, "reducer");
        if (this.policy != null) {
            throw new IllegalStateException("JoinScope '" + stepId
                    + "': join policy already set to " + this.policy.getClass().getSimpleName()
                    + "; only one policy may be selected per join");
        }
        this.policy = newPolicy;
        this.reducer = newReducer;
        return this;
    }
}
