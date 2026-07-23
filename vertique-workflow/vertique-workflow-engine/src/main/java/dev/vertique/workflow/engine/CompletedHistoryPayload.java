// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code COMPLETED} history entry.
 *
 * <p>Records the step id of the {@link dev.vertique.workflow.plan.CompleteNode} that terminated
 * the workflow instance in a successful state.
 *
 * @param stepId the id of the {@link dev.vertique.workflow.plan.CompleteNode}
 */
record CompletedHistoryPayload(String stepId) {}
