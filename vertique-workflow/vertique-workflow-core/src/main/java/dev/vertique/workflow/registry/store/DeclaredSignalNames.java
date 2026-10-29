// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.plan.BranchStart;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.FailNode;
import dev.vertique.workflow.plan.ForkNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.JoinNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * INTERNAL framework helper: extracts the signal names a plan declares, in the shape a definition row stores.
 *
 * <p>The result is {@code {"instance": [names], "branches": {"<forkStep>/<branch>": [names]}}}. A name in
 * {@code instance} is awaited by a step that no fork branch reaches; a name under a branch key is awaited by a step
 * reachable from the first step of that branch. Names are sorted and de-duplicated, and {@code branches} is empty
 * for a plan without a fork.
 */
public final class DeclaredSignalNames {

    private DeclaredSignalNames() {}

    /**
     * Extracts the declared signal names of a plan.
     *
     * @param workflow the runtime workflow
     * @return the stored shape; never {@code null}
     */
    public static JsonObject of(RuntimeWorkflow workflow) {
        Map<String, WorkflowNode> nodeById = workflow.nodeById();
        Map<String, Set<String>> branchSteps = new TreeMap<>();
        Set<String> inBranch = new HashSet<>();
        for (WorkflowNode node : workflow.plan().nodes()) {
            if (node instanceof ForkNode fork) {
                for (BranchStart branch : fork.branches()) {
                    Set<String> reachable = reachableFrom(branch.startStepId(), fork.stepId(), nodeById);
                    branchSteps.put(fork.stepId() + "/" + branch.branchId(), reachable);
                    inBranch.addAll(reachable);
                }
            }
        }
        TreeSet<String> instance = new TreeSet<>();
        for (WorkflowNode node : workflow.plan().nodes()) {
            if (node instanceof WaitSignalNode wait && !inBranch.contains(wait.stepId())) {
                instance.add(wait.signalName());
            }
        }
        JsonObject branches = new JsonObject();
        branchSteps.forEach((key, steps) -> {
            TreeSet<String> names = new TreeSet<>();
            for (String stepId : steps) {
                if (nodeById.get(stepId) instanceof WaitSignalNode wait) names.add(wait.signalName());
            }
            branches.put(key, new JsonArray(List.copyOf(names)));
        });
        return new JsonObject()
                .put("instance", new JsonArray(List.copyOf(instance)))
                .put("branches", branches);
    }

    /** Follows forward step references from a branch start; stops at completion, failure, joins and compensation. */
    private static Set<String> reachableFrom(String startStepId, String forkStepId, Map<String, WorkflowNode> nodes) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startStepId);
        while (!queue.isEmpty()) {
            String stepId = queue.poll();
            if (stepId == null || stepId.equals(forkStepId) || !visited.add(stepId)) continue;
            WorkflowNode node = nodes.get(stepId);
            if (node == null
                    || node instanceof CompleteNode
                    || node instanceof FailNode
                    || node instanceof JoinNode
                    || node instanceof CompensationNode) continue;
            if (node instanceof ServiceDispatchNode dispatch) {
                queue.add(dispatch.nextStepId());
            } else if (node instanceof WaitSignalNode wait) {
                queue.add(wait.nextStepId());
                if (wait.timeout() != null) queue.add(wait.timeout().timeoutNextStepId());
            } else if (node instanceof TimerNode timer) {
                queue.add(timer.nextStepId());
            } else if (node instanceof HumanTaskNode task) {
                for (HumanTaskNode.TaskDecision decision : task.decisions()) queue.add(decision.nextStepId());
                if (task.dueNextStepId() != null) queue.add(task.dueNextStepId());
            }
        }
        return visited;
    }
}
