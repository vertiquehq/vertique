// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code FAILED} history entry.
 *
 * <p>Records the step id, error type, and error message at the point a
 * {@link dev.vertique.workflow.plan.FailNode} transitions the instance to the {@code FAILED}
 * status.
 *
 * @param stepId the id of the {@link dev.vertique.workflow.plan.FailNode}
 * @param errorType the error type discriminator string stored on the node
 * @param errorMessage the resolved error message; may be {@code null} if the message factory
 *     produced no message
 */
record FailedHistoryPayload(String stepId, String errorType, String errorMessage) {}
