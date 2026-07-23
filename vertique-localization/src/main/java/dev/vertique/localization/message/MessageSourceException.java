// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import dev.vertique.localization.LocalizationException;

/**
 * Base exception for message-subsystem failures in the localization module.
 *
 * <p>This exception is the root of the message-source exception hierarchy. Known sub-types:
 * <ul>
 *   <li>{@link NoSuchMessageException} — thrown when a message code cannot be resolved in any
 *       configured bundle and no default message has been supplied.</li>
 * </ul>
 *
 * <p>Note: this class intentionally does <em>not</em> extend
 * {@link dev.vertique.core.exception.TechnicalException}. HTTP-status mapping (e.g. 500) is
 * applied by a REST-layer exception-mapper customizer in {@code vertique-rest-localization},
 * keeping this module decoupled from REST concerns.
 *
 * @see LocalizationException
 * @see NoSuchMessageException
 */
public class MessageSourceException extends LocalizationException {

    /**
     * Constructs a new message-source exception with the given detail message.
     *
     * @param message the detail message
     */
    public MessageSourceException(String message) {
        super(message);
    }

    /**
     * Constructs a new message-source exception with the given detail message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public MessageSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
