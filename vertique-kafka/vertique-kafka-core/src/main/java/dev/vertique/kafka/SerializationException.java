// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.exception.TechnicalException;

/**
 * Thrown when a Kafka record value cannot be serialized to bytes.
 *
 * <p>The message of a serializer failure carries no record value; the underlying exception is kept
 * as the cause.
 */
public class SerializationException extends TechnicalException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a serialization exception.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
