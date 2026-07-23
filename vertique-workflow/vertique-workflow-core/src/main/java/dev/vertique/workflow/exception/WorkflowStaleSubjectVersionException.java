// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a {@link dev.vertique.workflow.ops.TaskCompletionCommand#reviewedSubjectVersion()}
 * does not match the snapshot taken at task creation, or when stability is required but the caller
 * did not supply a reviewed version.
 *
 * <p>Extends {@link WorkflowConflictException} because this is a conflict-flavoured error: the
 * caller can recover by re-fetching the current subject state, reviewing the updated version, and
 * re-submitting (or cancelling the task and starting a new instance).
 *
 * <p>This exception is only thrown when the task's
 * {@link dev.vertique.workflow.plan.HumanTaskNode#requireVersionStability()} flag is {@code true}.
 */
public class WorkflowStaleSubjectVersionException extends WorkflowConflictException {

    /**
     * Creates a new {@code WorkflowStaleSubjectVersionException} with the given message.
     *
     * @param message description of the stale-version conflict
     */
    public WorkflowStaleSubjectVersionException(String message) {
        super(message);
    }
}
