// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when REST layer configuration validation fails at startup.
 *
 * <p>Covers security policy violations, route registration errors, and other
 * REST-specific wiring problems detected during initialization.
 */
public class RestConfigurationException extends ConfigurationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public RestConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public RestConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
