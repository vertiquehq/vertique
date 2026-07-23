// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TIMER_CANCELLED}
 * history entry.
 *
 * <p>Recorded when a scheduled timer is explicitly cancelled — either because the awaited
 * signal arrived before the deadline ({@link TimerCancelledCause#SIGNAL_ARRIVED}) or because
 * the workflow was cancelled ({@link TimerCancelledCause#WORKFLOW_CANCELLED}).
 *
 * @param stepId the plan step id of the node that owned the timer
 * @param timerId the stable UUID of the timer that was cancelled
 * @param cancelledAt the UTC instant at which the timer was cancelled
 * @param cause the reason the timer was cancelled
 */
record TimerCancelledHistoryPayload(String stepId, UUID timerId, Instant cancelledAt, TimerCancelledCause cause) {}
