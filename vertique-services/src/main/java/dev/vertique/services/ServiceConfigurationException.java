// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when service layer configuration validation fails at startup.
 *
 * <p>Covers service registration violations and other service-specific wiring
 * problems detected during initialization.
 */
public class ServiceConfigurationException extends ConfigurationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public ServiceConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ServiceConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
