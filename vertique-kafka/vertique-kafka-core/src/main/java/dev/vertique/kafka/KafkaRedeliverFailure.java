// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.util.Strings;

/**
 * Failure a {@link KafkaRecordHandler} raises to say that the record is fine and must be run again
 * later, without spending a retry. It applies on a binding that forces its strategies: the record
 * goes back to the queue, holding no permit and keeping its attempts, and stays uncommitted, with
 * every record after it. The consumer starts no record and fetches no partition until it asks its
 * {@link KafkaDispatchGate} again and finds it open, and the record then runs again. The record is
 * never published to the dead-letter topic because of this failure, and the number of times it is
 * returned has no limit.
 *
 * <p>On any other binding it is an ordinary failure, handled by the binding's strategy.
 *
 * <p>The failure carries a short {@link #errorType() error type} and nothing else: no cause and no
 * message beyond the type, so nothing from the record can reach a log line. It also records no stack
 * trace.
 *
 * <p>The error type must be a simple token: a letter, {@code _} or {@code $} followed by up to 127
 * letters, digits and {@code _ $ . -}. Any other text, such as one with a space or a control
 * character, is replaced by {@code InvalidErrorType}, so a value that arrives from outside
 * cannot reach a log line.
 */
public final class KafkaRedeliverFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorType;

    /**
     * Creates a redeliver failure.
     *
     * @param errorType a short, stable name for the kind of failure; it must not carry data taken
     *     from the record. A value that is not a simple token is replaced by
     *     {@code InvalidErrorType}
     */
    public KafkaRedeliverFailure(String errorType) {
        super(checked(errorType), null, false, false);
        this.errorType = getMessage();
    }

    private static String checked(String errorType) {
        return Strings.errorTypeToken(errorType);
    }

    /**
     * Returns the error type.
     *
     * @return the error type given at construction
     */
    public String errorType() {
        return errorType;
    }
}
