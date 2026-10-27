// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a signal is refused.
 *
 * <p>A call that records a signal fails with this exception when:
 * <ul>
 *   <li>The instance has ended (completed, compensated, cancelled or expired)</li>
 *   <li>The instance's pinned definition row declares signal names and does not declare this one for the
 *       addressed wait</li>
 * </ul>
 *
 * <p>Applying a recorded signal raises it internally when the instance is not in a compatible waiting state or the
 * pinned definition never waits for the name; the executor then settles the signal's command as rejected instead of
 * failing a caller.
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
