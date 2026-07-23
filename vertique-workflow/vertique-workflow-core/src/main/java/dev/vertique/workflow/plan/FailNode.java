// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;

/**
 * A terminal plan node that marks the workflow instance as failed and triggers compensation.
 *
 * <p>When the engine reaches this node it records a failure, then initiates the compensation flow
 * (LIFO over completed compensable steps). The {@code errorType} and the message produced by
 * {@code messageFactoryCallbackId} are persisted on the instance for observability.
 *
 * @param stepId unique step identifier within the plan
 * @param errorType application-defined error category string persisted on the instance
 * @param messageFactoryCallbackId callback id for the {@code Function<S, String>} that produces a
 *     human-readable error message from the current workflow state
 */
public record FailNode(String stepId, String errorType, CallbackId messageFactoryCallbackId) implements WorkflowNode {}
