// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Base exception for startup and wiring failures. Thrown when the framework detects
 * invalid configuration, missing bindings, or contract violations during initialization.
 *
 * <p>Not mapped to an HTTP status — these are startup-time errors that prevent the
 * application from starting.
 */
public class ConfigurationException extends VertiqueException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
