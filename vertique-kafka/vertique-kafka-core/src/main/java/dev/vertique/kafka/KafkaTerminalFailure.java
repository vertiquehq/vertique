// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.Objects;

/**
 * Failure a {@link KafkaRecordHandler} raises to say that retrying the record cannot help. The
 * consumer does not retry it and does not pause: the record takes the binding's exhausted strategy
 * at once. Under {@link ErrorStrategy#DEAD_LETTER} it is published to the dead-letter topic, and
 * under {@link ErrorStrategy#SKIP} it is skipped with a log. For a binding that retries, the
 * exhausted strategy is {@link RetryConfig#exhaustedStrategy()}.
 *
 * <p>The failure carries a short {@link #errorType() error type} and nothing else: no cause and no
 * message beyond the type, so nothing from the record can reach a log line or the dead-letter
 * {@code x-dlq-error} header, which holds the error type alone. It also records no stack trace.
 */
public final class KafkaTerminalFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorType;

    /**
     * Creates a terminal failure.
     *
     * @param errorType a short, stable name for the kind of failure; it must not carry data taken
     *     from the record
     */
    public KafkaTerminalFailure(String errorType) {
        super(Objects.requireNonNull(errorType, "errorType"), null, false, false);
        this.errorType = errorType;
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
