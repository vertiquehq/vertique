// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TIMEOUT} history
 * entry.
 *
 * <p>Recorded when the timeout branch of a
 * {@link dev.vertique.workflow.plan.WaitSignalNode} fires — i.e., the expected signal did not
 * arrive before the timer deadline. The workflow is advanced to {@code timeoutNextStepId}
 * (the {@link dev.vertique.workflow.plan.WaitSignalNode.TimeoutBranch#timeoutNextStepId()})
 * rather than the normal signal path.
 *
 * @param stepId the plan step id of the {@link dev.vertique.workflow.plan.WaitSignalNode}
 *     that timed out
 * @param timerId the stable UUID of the timeout timer that fired
 * @param firedAt the UTC instant at which the timer fired
 * @param timeoutNextStepId the step id the workflow advanced to via the timeout branch
 */
record TimeoutHistoryPayload(String stepId, UUID timerId, Instant firedAt, String timeoutNextStepId) {}
