// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.exception.UnavailableException;

/**
 * Workflow semantic root for unavailable-runtime-capability failures (a valid operation cannot be
 * served because a required workflow capability, e.g. a pinned definition version, is not currently
 * available). Maps to HTTP 503 via {@link UnavailableException}.
 *
 * <p>Unlike {@link WorkflowNotFoundException} (which signals a permanently missing entity),
 * this exception represents a transient condition — the capability may become available later.
 * Callers should treat this as a retryable failure.
 */
public class WorkflowUnavailableException extends UnavailableException {

    /**
     * Creates a new {@code WorkflowUnavailableException} with the given message.
     *
     * @param message description of the unavailable capability
     */
    public WorkflowUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowUnavailableException} with the given message and underlying
     * cause.
     *
     * @param message description of the unavailable capability
     * @param cause   the underlying exception that caused this failure
     */
    public WorkflowUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
