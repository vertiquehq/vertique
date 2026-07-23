// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TIMER_FAILED}
 * history entry.
 *
 * <p>Recorded when the delayed-job executor reports that a timer-fire attempt permanently
 * failed after exhausting retries. The timer row is marked
 * {@link dev.vertique.workflow.timer.TimerStatus#FAILED} and the workflow instance is left
 * in a state requiring manual intervention.
 *
 * @param stepId the plan step id of the node whose timer failed
 * @param timerId the stable UUID of the timer that failed
 * @param failedAt the UTC instant at which the failure was reported
 * @param errorType the error category reported by the delayed job executor
 * @param errorMessage human-readable description of the failure cause
 */
record TimerFailedHistoryPayload(
        String stepId, UUID timerId, Instant failedAt, String errorType, String errorMessage) {}
