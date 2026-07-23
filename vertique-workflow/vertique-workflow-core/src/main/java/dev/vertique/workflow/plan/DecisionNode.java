// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;

/**
 * A plan node that dynamically resolves the next step based on the current workflow state.
 *
 * <p>The engine invokes the resolver callback registered under {@code nextStepResolverCallbackId}
 * with the current state object. The resolver must return a valid {@code stepId} that exists in
 * the plan; otherwise the engine fails the workflow.
 *
 * @param stepId unique step identifier within the plan
 * @param nextStepResolverCallbackId callback id for the {@code Function<S, String>} that inspects
 *     the current state and returns the id of the next step to execute
 */
public record DecisionNode(String stepId, CallbackId nextStepResolverCallbackId) implements WorkflowNode {}
