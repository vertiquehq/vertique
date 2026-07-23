// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic validator for the PRD-WF-002 fork/join plan model.
 *
 * <p>Run by {@code DefaultWorkflowRegistry} immediately after {@code WorkflowBuilder.build(...)}
 * returns and before existing graph/signal/callback validation. Plans without {@link ForkNode} or
 * {@link JoinNode} are accepted unchanged — single-path workflows continue to work.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>Each {@code ForkNode} declares between 1 and {@value #MAX_BRANCHES_V1} branches; branch ids
 *       are unique within the fork group; every {@link BranchStart#startStepId()} resolves to an
 *       in-plan node.</li>
 *   <li>Every {@code ForkNode} is paired with exactly one {@code JoinNode} whose stepId equals
 *       {@link ForkNode#joinStepId()}; the join's policy and routes are well-formed.</li>
 *   <li>No nested fan-out: the sub-graph reachable from a branch's {@code startStepId} (following
 *       static next-step references) MUST NOT contain another {@code ForkNode}
 *       (FR-WF-PAR-008).</li>
 *   <li>Race policies ({@link FirstSuccessJoinPolicy}, {@link FirstFailureJoinPolicy}) require all
 *       branches to declare {@link RaceSafety#CANCEL_SAFE} or
 *       {@link RaceSafety#IGNORE_LATE_RESULT_SAFE} (FR-WF-PAR-011); reject plans whose branch sub-
 *       graph contains a {@link CompensationNode} or any {@link ServiceDispatchNode} with a
 *       non-null {@code compensationStepId} (FR-WF-PAR-054); and every reachable
 *       {@link ServiceDispatchNode} must resolve via {@link RaceSafetyTargetRegistry} to a non-
 *       {@link RaceSafety#NORMAL} value (FR-WF-PAR-012).</li>
 * </ul>
 *
 * <p>Static reachability follows {@link ServiceDispatchNode#nextStepId()},
 * {@link WaitSignalNode#nextStepId()} (and timeout branch), {@link TimerNode#nextStepId()}, and
 * {@link HumanTaskNode} decision/due-date branches. Dynamic dispatches via {@link DecisionNode}
 * are not traversed; race-safety claims that pass through a decision node are accepted but not
 * statically enforced past the decision point — runtime resolution may still uncover unsafe
 * targets which the engine rejects there.
 */
@Singleton
public final class WorkflowPlanValidator {

    /** V1 cap on branches per fork group (PRD-WF-002 §A.4.1). */
    public static final int MAX_BRANCHES_V1 = 16;

    private final RaceSafetyTargetRegistry raceSafetyTargets;

    /**
     * Constructs the validator.
     *
     * @param raceSafetyTargets registry of declared race-safe service-dispatch targets
     */
    @Inject
    public WorkflowPlanValidator(RaceSafetyTargetRegistry raceSafetyTargets) {
        this.raceSafetyTargets = raceSafetyTargets;
    }

    /**
     * Validates the plan against the PRD-WF-002 fork/join rules.
     *
     * <p>Plans without {@code ForkNode}/{@code JoinNode} are accepted unchanged.
     *
     * @param plan the plan to validate
     * @throws WorkflowDefinitionException on the first rule violation
     */
    public void validate(WorkflowPlan plan) {
        Map<String, WorkflowNode> nodeById = new HashMap<>();
        for (WorkflowNode n : plan.nodes()) {
            nodeById.put(n.stepId(), n);
        }
        String label = "plan '" + plan.definitionId() + "' v" + plan.definitionVersion();

        for (WorkflowNode n : plan.nodes()) {
            if (n instanceof ForkNode fork) {
                validateFork(fork, nodeById, label);
            } else if (n instanceof JoinNode join) {
                validateJoinShape(join, nodeById, label);
            }
        }
    }

    // --- ForkNode validation ---

    private void validateFork(ForkNode fork, Map<String, WorkflowNode> nodeById, String label) {
        // 1. Branch list shape
        List<BranchStart> branches = fork.branches();
        if (branches.isEmpty()) {
            throw new WorkflowDefinitionException(
                    "ForkNode '" + fork.stepId() + "' in " + label + " declares no branches; at least one is required");
        }
        if (branches.size() > MAX_BRANCHES_V1) {
            throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label + " declares "
                    + branches.size() + " branches; PRD-WF-002 V1 caps fork groups at " + MAX_BRANCHES_V1);
        }
        // 2. Branch ids unique
        Set<String> seenIds = new HashSet<>();
        for (BranchStart bs : branches) {
            if (!seenIds.add(bs.branchId())) {
                throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label
                        + " declares duplicate branchId '" + bs.branchId() + "'");
            }
            // 3. Branch start step resolves
            if (!nodeById.containsKey(bs.startStepId())) {
                throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label + " branch '"
                        + bs.branchId() + "' references unknown startStepId '" + bs.startStepId() + "'");
            }
        }
        // 4. Matching join exists with matching stepId
        WorkflowNode joinNode = nodeById.get(fork.joinStepId());
        if (joinNode == null) {
            throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label
                    + " references unknown joinStepId '" + fork.joinStepId() + "'");
        }
        if (!(joinNode instanceof JoinNode jn)) {
            throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label
                    + " references joinStepId '" + fork.joinStepId() + "' which does not resolve to a JoinNode");
        }
        // 5. Nested-fork detection + branch-owned wait rejection (Codex review round-2 W-new):
        //    branch-owned TimerNode / HumanTaskNode / WaitSignalNode-with-timeout aren't yet
        //    implemented in BranchTransitionEngine. Without this validator gate they would only
        //    fail at runtime, AFTER sibling branches may have committed durable side effects.
        //    Reject at registration so authors see the gap immediately.
        for (BranchStart bs : branches) {
            Set<String> reachable = reachableFrom(bs.startStepId(), fork.stepId(), nodeById);
            for (String stepId : reachable) {
                WorkflowNode n = nodeById.get(stepId);
                if (n instanceof ForkNode nested && !nested.stepId().equals(fork.stepId())) {
                    throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label + " branch '"
                            + bs.branchId() + "' reaches another ForkNode '" + nested.stepId()
                            + "'; nested fan-out is not supported in PRD-WF-002 V1 (FR-WF-PAR-008)");
                }
                // PRD-WF-002 AC #4 / AC #5 implemented: branch-owned HumanTaskNode, TimerNode,
                // and WaitSignalNode-with-timeout are now supported by BranchTransitionEngine.
                // The previous rejection blocks for these node types are intentionally lifted.
                // DecisionNode remains rejected: its dynamic resolution still cannot be statically
                // proven safe.
                if (n instanceof DecisionNode) {
                    throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label + " branch '"
                            + bs.branchId() + "' contains DecisionNode '" + stepId
                            + "'; DecisionNode resolution is dynamic and could route to node types not yet"
                            + " supported inside branches in PRD-WF-002 V1. Move the decision outside the fork.");
                }
            }
        }
        // 6. Race-policy enforcement (FR-WF-PAR-011/012/054)
        boolean isRace = jn.policy() instanceof FirstSuccessJoinPolicy || jn.policy() instanceof FirstFailureJoinPolicy;
        if (isRace) {
            // 6a. Race-safety per branch
            for (BranchStart bs : branches) {
                if (bs.raceSafety() == RaceSafety.NORMAL) {
                    throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label + " uses "
                            + jn.policy().getClass().getSimpleName() + " but branch '" + bs.branchId()
                            + "' has RaceSafety.NORMAL; race joins require CANCEL_SAFE or IGNORE_LATE_RESULT_SAFE"
                            + " (FR-WF-PAR-011)");
                }
            }
            // 6b. No compensation in race branches; every dispatch target must be race-safe
            for (BranchStart bs : branches) {
                Set<String> reachable = reachableFrom(bs.startStepId(), fork.stepId(), nodeById);
                for (String stepId : reachable) {
                    WorkflowNode n = nodeById.get(stepId);
                    if (n instanceof CompensationNode) {
                        throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label
                                + " uses " + jn.policy().getClass().getSimpleName() + " but branch '" + bs.branchId()
                                + "' contains CompensationNode '" + stepId + "'; race joins reject compensable"
                                + " branches (FR-WF-PAR-054)");
                    }
                    if (n instanceof ServiceDispatchNode sdn && sdn.compensationStepId() != null) {
                        throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label
                                + " uses " + jn.policy().getClass().getSimpleName() + " but branch '" + bs.branchId()
                                + "' ServiceDispatchNode '" + sdn.stepId() + "' declares a compensationStepId; race"
                                + " joins reject compensable branches (FR-WF-PAR-054)");
                    }
                    if (n instanceof ServiceDispatchNode sdn) {
                        RaceSafety declared = raceSafetyTargets.lookup(sdn.targetId());
                        if (declared == RaceSafety.NORMAL) {
                            throw new WorkflowDefinitionException("ForkNode '" + fork.stepId() + "' in " + label
                                    + " uses " + jn.policy().getClass().getSimpleName() + " but branch '"
                                    + bs.branchId() + "' dispatches to target '" + sdn.targetId()
                                    + "' which has no race-safe registration; register it via a"
                                    + " RaceSafetyTargetContributor with CANCEL_SAFE or"
                                    + " IGNORE_LATE_RESULT_SAFE (FR-WF-PAR-012)");
                        }
                    }
                }
            }
            // 6c. FirstFailure requires a failure route
            if (jn.policy() instanceof FirstFailureJoinPolicy && jn.failureStepId() == null) {
                throw new WorkflowDefinitionException("JoinNode '" + jn.stepId() + "' in " + label + " uses"
                        + " FirstFailureJoinPolicy but failureStepId is null; the failure route is required"
                        + " for this policy");
            }
        }
    }

    // --- JoinNode standalone shape ---

    private void validateJoinShape(JoinNode join, Map<String, WorkflowNode> nodeById, String label) {
        if (!nodeById.containsKey(join.nextStepId())) {
            throw new WorkflowDefinitionException("JoinNode '" + join.stepId() + "' in " + label
                    + " references unknown nextStepId '" + join.nextStepId() + "'");
        }
        if (join.failureStepId() != null && !nodeById.containsKey(join.failureStepId())) {
            throw new WorkflowDefinitionException("JoinNode '" + join.stepId() + "' in " + label
                    + " references unknown failureStepId '" + join.failureStepId() + "'");
        }
        // Each JoinNode must have a matching ForkNode whose joinStepId equals this stepId.
        boolean hasMatchingFork = nodeById.values().stream()
                .filter(ForkNode.class::isInstance)
                .map(ForkNode.class::cast)
                .anyMatch(f -> f.joinStepId().equals(join.stepId()));
        if (!hasMatchingFork) {
            throw new WorkflowDefinitionException("JoinNode '" + join.stepId() + "' in " + label
                    + " has no matching ForkNode whose joinStepId equals '" + join.stepId() + "'");
        }
    }

    // --- Reachability ---

    /**
     * Computes the set of step ids statically reachable from {@code startStepId} by following
     * forward next-step references on each visited node. The traversal stops at {@link
     * CompleteNode}, {@link FailNode}, and at any {@link JoinNode} reached (joins are evaluated
     * via branch-token state, not by direct execution flow). {@link DecisionNode} is included in
     * the reachable set but its dynamic resolutions are not followed.
     *
     * <p>The fork's own step id ({@code currentForkStepId}) is excluded from the visit queue so a
     * fork doesn't pretend to reach itself; nested {@link ForkNode}s reached via a branch are
     * included in the result so the caller can flag them.
     *
     * @param startStepId the step id to start from
     * @param currentForkStepId the step id of the fork whose branch we're traversing; never
     *     traversed
     * @param nodeById map of all plan nodes by step id
     * @return the set of step ids visited (excluding {@code currentForkStepId})
     */
    private static Set<String> reachableFrom(
            String startStepId, String currentForkStepId, Map<String, WorkflowNode> nodeById) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startStepId);
        while (!queue.isEmpty()) {
            String stepId = queue.poll();
            if (stepId == null || stepId.equals(currentForkStepId) || !visited.add(stepId)) {
                continue;
            }
            WorkflowNode n = nodeById.get(stepId);
            if (n == null) {
                continue;
            }
            // Traversal boundaries: complete, fail, and join nodes do not contribute next steps to
            // the queue. Compensation nodes are not part of forward flow either.
            if (n instanceof CompleteNode
                    || n instanceof FailNode
                    || n instanceof JoinNode
                    || n instanceof CompensationNode) {
                continue;
            }
            // Forward next-step references per node type.
            if (n instanceof ServiceDispatchNode sdn) {
                queue.add(sdn.nextStepId());
            } else if (n instanceof WaitSignalNode wsn) {
                queue.add(wsn.nextStepId());
                if (wsn.timeout() != null) {
                    queue.add(wsn.timeout().timeoutNextStepId());
                }
            } else if (n instanceof TimerNode tn) {
                queue.add(tn.nextStepId());
            } else if (n instanceof HumanTaskNode htn) {
                for (HumanTaskNode.TaskDecision d : htn.decisions()) {
                    queue.add(d.nextStepId());
                }
                if (htn.dueNextStepId() != null) {
                    queue.add(htn.dueNextStepId());
                }
            }
            // DecisionNode: cannot statically follow dynamic resolutions; ForkNode: we want it in
            // the visited set so the caller can flag a nested fork. No next-step traversal in
            // either case.
        }
        return visited;
    }
}
