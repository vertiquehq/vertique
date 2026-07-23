// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;
import jakarta.annotation.Nullable;

/**
 * A plan node that suspends the workflow until a named external signal arrives.
 *
 * <p>When the engine reaches this node it sets the instance status to {@code WAITING} with
 * {@code waitType="SIGNAL"} and {@code waitKey=signalName}. The workflow resumes when
 * {@code WorkflowOperations.signal(...)} is called with a matching name.
 *
 * <p>Signal names MUST be unique across all {@code WaitSignalNode}s within the same plan. Apps
 * that need to wait on the same logical signal at different points in the workflow MUST route via a
 * {@link DecisionNode}.
 *
 * <p>An optional {@link TimeoutBranch} can be attached. When present, the engine schedules a
 * durable timer alongside the signal wait. If the timer fires before the signal arrives the engine
 * takes the timeout branch instead of the normal signal path. If the signal arrives first the
 * timer is cancelled. Timeout support requires the {@code vertique-workflow-delayed} module and is
 * fully implemented in cycle 2; the field is accepted by the plan model so that definitions can
 * express it without a cycle-1 engine change.
 *
 * @param stepId unique step identifier within the plan
 * @param signalName name of the signal this node waits for; must be unique within the plan
 * @param payloadTypeName fully-qualified class name ({@link Class#getName()}) of the expected
 *     signal payload type; used by the engine to coerce externally-ingested payloads (e.g., raw
 *     {@code Map}) to the typed class via {@code Json.decodeValue}
 * @param stateUpdaterCallbackId callback id for the {@code BiFunction<S, P, S>} that merges the
 *     incoming signal payload into the current workflow state
 * @param nextStepId step to advance to after the signal is processed
 * @param timeout optional timeout branch; {@code null} means no timeout is configured and the
 *     node waits indefinitely for the signal
 */
public record WaitSignalNode(
        String stepId,
        String signalName,
        String payloadTypeName,
        CallbackId stateUpdaterCallbackId,
        String nextStepId,
        @Nullable TimeoutBranch timeout)
        implements WorkflowNode {

    /**
     * Specifies the timeout behaviour for a {@link WaitSignalNode}.
     *
     * <p>When the timeout timer fires before the awaited signal arrives, the engine calls
     * {@code onTimeoutMutatorCallbackId} with the current workflow state to produce the mutated
     * state, then advances to {@code timeoutNextStepId}.
     *
     * @param timeout how the timeout deadline is determined
     * @param onTimeoutMutatorCallbackId callback id for the {@code Function<S, S>} that mutates
     *     the workflow state when the timeout path is taken; looked up via
     *     {@link dev.vertique.workflow.registry.WorkflowCallbackRegistry#stateMutator(CallbackId)}
     * @param timeoutNextStepId step to advance to when the timeout path is taken
     */
    public record TimeoutBranch(TimerSpec timeout, CallbackId onTimeoutMutatorCallbackId, String timeoutNextStepId) {}
}
