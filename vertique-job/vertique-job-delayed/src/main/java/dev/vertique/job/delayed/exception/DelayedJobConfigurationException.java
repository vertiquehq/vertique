// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.exception;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Configuration/contract failure in delayed job registration.
 *
 * <p>Base class for all configuration and contract violations raised during delayed job startup.
 * Extends {@link ConfigurationException} so the delayed-job module's failures align with the
 * framework-wide configuration exception hierarchy.
 */
public class DelayedJobConfigurationException extends ConfigurationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public DelayedJobConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public DelayedJobConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
