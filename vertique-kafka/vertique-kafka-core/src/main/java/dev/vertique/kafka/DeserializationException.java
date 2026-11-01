// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.exception.TechnicalException;

/**
 * Thrown when a Kafka record value cannot be deserialized to the target type.
 *
 * <p>A failure is either permanent, which is the default and means the same bytes would fail again,
 * or {@linkplain #isTransient() transient}, which means the bytes may decode later because the cause
 * lies in infrastructure such as a schema registry that cannot be reached.
 */
public class DeserializationException extends TechnicalException {

    private static final long serialVersionUID = 1L;

    private final boolean transientFailure;

    /**
     * Creates a deserialization exception.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DeserializationException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /**
     * Creates a deserialization exception that says whether retrying the same bytes could succeed.
     *
     * @param message the error message
     * @param cause the underlying cause
     * @param transientFailure {@code true} if the failure comes from infrastructure and the same
     *     bytes may decode later
     */
    public DeserializationException(String message, Throwable cause, boolean transientFailure) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    /**
     * Returns whether the failure comes from infrastructure, so that the same bytes may decode
     * later. A binding that ignores undecodable records ignores only a failure for which this is
     * {@code false}.
     *
     * @return {@code true} if the failure is transient
     */
    public boolean isTransient() {
        return transientFailure;
    }
}
