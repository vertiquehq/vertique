// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;

/**
 * A plan node that records a compensating service call for a previously completed dispatch step.
 *
 * <p>Compensation nodes are discovered by the engine during the compensation flow. The engine
 * locates a {@code CompensationNode} by finding the node whose {@code forwardStepId} matches a
 * completed {@link ServiceDispatchNode}. The compensation intent is recorded as a
 * {@code WorkflowSideEffectIntent} (kind=SERVICE) in LIFO order relative to the completed forward
 * steps.
 *
 * @param stepId unique step identifier within the plan
 * @param forwardStepId step id of the {@link ServiceDispatchNode} this node compensates
 * @param targetId service target identifier for the compensation call
 * @param payloadCallbackId callback id for the {@code Function<S, Object>} that derives the
 *     compensation request payload from the current workflow state
 */
public record CompensationNode(String stepId, String forwardStepId, String targetId, CallbackId payloadCallbackId)
        implements WorkflowNode {}
