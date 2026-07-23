// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when the engine is creating a {@code requireVersionStability=true} task on a workflow
 * whose subject version is {@code null}.
 *
 * <p>This is a caller-driven precondition error: the start command did not supply a versioned
 * subject for a plan whose execution path requires one. The plan definition itself is not broken —
 * the caller simply invoked {@code start()} without attaching a versioned
 * {@link dev.vertique.workflow.subject.WorkflowSubjectRef} to the workflow instance.
 *
 * <p>Extends {@link WorkflowException} (the workflow business-rule root, → 400): this is a
 * caller-input precondition error (a start command omitted a versioned subject for a
 * stability-required task), distinct from configuration faults, state conflicts, and unavailable
 * capabilities, which use their own workflow roots.
 */
public class WorkflowSubjectVersionUnavailableException extends WorkflowException {

    /**
     * Creates a new {@code WorkflowSubjectVersionUnavailableException} with the given message.
     *
     * @param message description of the missing subject version
     */
    public WorkflowSubjectVersionUnavailableException(String message) {
        super(message);
    }
}
