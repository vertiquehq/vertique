// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code COMPENSATING_STEP} history entry.
 *
 * <p>Records the identities of one compensation step execution: the forward step being undone, the
 * compensation node that performed the undo, and the service target that received the compensation
 * intent.
 *
 * @param forwardStepId the step id of the {@link dev.vertique.workflow.plan.ServiceDispatchNode}
 *     being compensated
 * @param compensationStepId the step id of the
 *     {@link dev.vertique.workflow.plan.CompensationNode} that executed the rollback
 * @param targetId the service target id that received the compensation intent
 */
record CompensatingStepHistoryPayload(String forwardStepId, String compensationStepId, String targetId) {}
