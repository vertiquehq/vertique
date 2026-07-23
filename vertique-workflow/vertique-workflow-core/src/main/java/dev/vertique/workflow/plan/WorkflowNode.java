// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * Sealed base interface for all node types in a {@link WorkflowPlan}.
 *
 * <p>Each node represents a single step in the workflow. The sealed hierarchy ensures the engine
 * handles every known node type at compile time.
 *
 * <p>Permitted implementations:
 * <ul>
 *   <li>{@link ServiceDispatchNode} — dispatches a service call and advances to the next step</li>
 *   <li>{@link WaitSignalNode} — suspends the workflow until a named signal arrives</li>
 *   <li>{@link DecisionNode} — dynamically resolves the next step based on current state</li>
 *   <li>{@link CompleteNode} — marks the workflow as successfully completed</li>
 *   <li>{@link FailNode} — marks the workflow as failed and triggers compensation</li>
 *   <li>{@link CompensationNode} — records a compensating action for a forward dispatch step</li>
 *   <li>{@link TimerNode} — schedules a timer and advances when it fires</li>
 *   <li>{@link HumanTaskNode} — creates a human task and waits for its completion</li>
 *   <li>{@link ForkNode} — fans out to a bounded set of named branches; PRD-WF-002 placeholder
 *       until engine integration lands in a later slice</li>
 *   <li>{@link JoinNode} — joins branches at a matching fork; placeholder until engine
 *       integration lands</li>
 * </ul>
 */
public sealed interface WorkflowNode
        permits ServiceDispatchNode,
                WaitSignalNode,
                DecisionNode,
                CompleteNode,
                FailNode,
                CompensationNode,
                TimerNode,
                HumanTaskNode,
                ForkNode,
                JoinNode {

    /**
     * Returns the unique step identifier within the plan.
     *
     * @return non-null, non-empty step id
     */
    String stepId();
}
