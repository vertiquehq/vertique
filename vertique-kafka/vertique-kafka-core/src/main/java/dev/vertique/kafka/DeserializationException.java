// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.exception.TechnicalException;

/**
 * Thrown when a Kafka record value cannot be deserialized to the target type.
 */
public class DeserializationException extends TechnicalException {

    /**
     * Creates a deserialization exception.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
