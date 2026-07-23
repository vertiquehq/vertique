// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.exception;

import dev.vertique.core.exception.TechnicalException;

/**
 * Technical root for delayed-job failures.
 *
 * <p>Base class for infrastructure-level runtime failures raised within the delayed-job subsystem.
 * Extends {@link TechnicalException} so delayed-job technical failures align with the
 * framework-wide technical exception hierarchy.
 */
public class DelayedJobTechnicalException extends TechnicalException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public DelayedJobTechnicalException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public DelayedJobTechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
