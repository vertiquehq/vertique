// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when the JSON mapper profile configuration is invalid — for example a duplicate or
 * reserved profile id, a mapper that fails its startup round-trip probe, or a request to resolve an
 * unknown profile id.
 *
 * <p>As a {@link ConfigurationException} this is a startup/wiring failure that is not mapped to an
 * HTTP status; it prevents the application from starting (or surfaces at the first registry access).
 */
public final class JsonProfileConfigurationException extends ConfigurationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message describing the invalid profile configuration
     */
    public JsonProfileConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and underlying cause.
     *
     * <p>Use this constructor when the profile configuration failure is triggered by a lower-level
     * exception (e.g. a Jackson serialization error during the round-trip probe) so that the original
     * stack trace is preserved for diagnosis.
     *
     * @param message the detail message describing the invalid profile configuration
     * @param cause   the underlying exception that triggered this configuration failure
     */
    public JsonProfileConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
