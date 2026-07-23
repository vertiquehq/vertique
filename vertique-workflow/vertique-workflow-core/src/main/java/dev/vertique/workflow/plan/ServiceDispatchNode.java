// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;
import jakarta.annotation.Nullable;

/**
 * A plan node that dispatches a service call and advances to the next step.
 *
 * <p>The engine builds a {@code WorkflowSideEffectIntent} from this node and records it via the
 * {@code WorkflowSideEffectRecorder}. After recording the intent, the engine advances to
 * {@code nextStepId}. If a {@code compensationStepId} is set, compensation is possible if the
 * workflow subsequently fails.
 *
 * @param stepId unique step identifier within the plan
 * @param targetId service target identifier resolved at dispatch time
 * @param payloadCallbackId callback id for the function that derives the request payload from state
 * @param compensationStepId optional step id of the matching {@link CompensationNode}; null if no
 *     compensation is needed for this dispatch
 * @param nextStepId step to advance to after the intent is recorded
 */
public record ServiceDispatchNode(
        String stepId,
        String targetId,
        CallbackId payloadCallbackId,
        @Nullable String compensationStepId,
        String nextStepId)
        implements WorkflowNode {}
