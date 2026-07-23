// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization;

import dev.vertique.core.exception.VertiqueException;

/**
 * Base exception for all localization module failures.
 *
 * <p>This exception is intentionally semantic-neutral — it does not carry an HTTP status code.
 * REST callers in {@code vertique-rest-localization} decide how to map it to an HTTP response
 * via an exception-mapper customizer. This keeps the localization module decoupled from REST.
 *
 * <p>Sub-packages extend this class for more specific failure categories:
 * <ul>
 *   <li>{@link dev.vertique.localization.message.MessageSourceException} — message-subsystem
 *       failures (malformed patterns, bundle load errors, missing keys).</li>
 * </ul>
 *
 * @see dev.vertique.core.exception.VertiqueException
 * @see dev.vertique.core.exception.ConfigurationException
 */
public class LocalizationException extends VertiqueException {

    /**
     * Constructs a new localization exception with the given message.
     *
     * @param message the detail message
     */
    public LocalizationException(String message) {
        super(message);
    }

    /**
     * Constructs a new localization exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public LocalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
