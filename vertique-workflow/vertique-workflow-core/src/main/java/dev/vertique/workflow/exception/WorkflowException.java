// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.exception.BusinessRuleException;

/**
 * Base class for workflow business-rule violations. This is NOT the base of every workflow failure
 * — missing resources, state conflicts, configuration/contract problems, unavailable runtime
 * capabilities, and technical failures use their own workflow semantic roots
 * ({@link WorkflowNotFoundException}, {@link WorkflowConflictException},
 * {@link WorkflowConfigurationException}, {@link WorkflowUnavailableException},
 * {@link WorkflowTechnicalException}). Maps to HTTP 400 via {@link BusinessRuleException}.
 */
public class WorkflowException extends BusinessRuleException {

    /**
     * Creates a new {@code WorkflowException} with the given message.
     *
     * @param message human-readable description of the error
     */
    public WorkflowException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowException} with the given message and cause.
     *
     * @param message human-readable description of the error
     * @param cause the underlying exception that caused this error
     */
    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
