// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TIMER_FIRED}
 * history entry.
 *
 * <p>Recorded when a durable timer fires and the workflow is advanced to the next step.
 * For standalone {@link dev.vertique.workflow.plan.TimerNode} steps, {@code nextStepId}
 * is the step configured on the node. For signal-wait timeout steps, see
 * {@link TimeoutHistoryPayload} instead.
 *
 * @param stepId the plan step id of the timer node that fired
 * @param timerId the stable UUID of the timer that fired
 * @param firedAt the UTC instant at which the timer was fired
 * @param nextStepId the step id the workflow advanced to after the timer fired
 */
record TimerFiredHistoryPayload(String stepId, UUID timerId, Instant firedAt, String nextStepId) {}
