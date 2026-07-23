// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.exception.NotFoundException;

/**
 * Workflow semantic root for not-found failures (missing instance/task/definition). Maps to HTTP
 * 404 via {@link NotFoundException}.
 *
 * <p>All workflow-specific not-found exceptions (e.g. {@link WorkflowInstanceNotFoundException},
 * {@link WorkflowTaskNotFoundException}, {@link WorkflowDefinitionMissingException}) extend this
 * class, so callers can catch all workflow not-found conditions with a single handler.
 */
public class WorkflowNotFoundException extends NotFoundException {

    /**
     * Creates a new {@code WorkflowNotFoundException} with the given message.
     *
     * @param message description of the not-found condition
     */
    public WorkflowNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowNotFoundException} with the given message and underlying cause.
     *
     * @param message description of the not-found condition
     * @param cause   the underlying exception that caused this failure
     */
    public WorkflowNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
