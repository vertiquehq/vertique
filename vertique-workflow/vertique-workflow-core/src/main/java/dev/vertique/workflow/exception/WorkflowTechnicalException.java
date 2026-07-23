// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.exception.TechnicalException;

/**
 * Workflow semantic root for technical failures. Maps to HTTP 500 via {@link TechnicalException}.
 *
 * <p>The persistence subtype {@link WorkflowPersistenceException} carries a retryable signal.
 * Other direct subclasses represent non-retryable internal failures specific to the workflow
 * engine (e.g. plan-hash drift, migration state mismatches).
 */
public class WorkflowTechnicalException extends TechnicalException {

    /**
     * Creates a new {@code WorkflowTechnicalException} with the given message.
     *
     * @param message description of the technical failure
     */
    public WorkflowTechnicalException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowTechnicalException} with the given message and underlying cause.
     *
     * @param message description of the technical failure
     * @param cause   the underlying exception that caused this failure
     */
    public WorkflowTechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
