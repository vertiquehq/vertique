// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a signal cannot be applied to a workflow instance because the instance is not in a
 * compatible waiting state.
 *
 * <p>This exception is thrown when:
 * <ul>
 *   <li>The instance is not in {@code WAITING} status</li>
 *   <li>The instance is waiting for a different signal name</li>
 *   <li>The instance is in a terminal status (COMPLETED, FAILED, etc.)</li>
 * </ul>
 */
public class WorkflowSignalRejectedException extends WorkflowConflictException {

    /**
     * Creates a new {@code WorkflowSignalRejectedException} with the given message.
     *
     * @param message description of why the signal was rejected
     */
    public WorkflowSignalRejectedException(String message) {
        super(message);
    }
}
