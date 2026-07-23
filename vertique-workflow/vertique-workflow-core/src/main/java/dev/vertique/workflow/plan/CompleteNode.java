// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * A terminal plan node that marks the workflow instance as successfully {@code COMPLETED}.
 *
 * <p>When the engine reaches this node, the instance status transitions to {@code COMPLETED} and
 * no further transitions occur.
 *
 * @param stepId unique step identifier within the plan
 */
public record CompleteNode(String stepId) implements WorkflowNode {}
