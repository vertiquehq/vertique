// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

/**
 * The terminal disposition of a single Kafka consumer record after the full dispatch pipeline
 * (interceptors, handler, recovery, and error strategy) has settled.
 *
 * <p>Exactly one outcome is emitted per record at the terminal point — after all async work
 * (DLQ publish, seek, etc.) completes — and passed to every registered
 * {@link KafkaConsumerCaptureHook}. Observers MUST NOT use this value to affect commit,
 * retry, or delivery behaviour; it is a read-only observability signal.
 *
 * <p>Error-path outcomes are produced by {@link dev.vertique.kafka.KafkaErrorHandler};
 * success-path and recovery outcomes are produced by
 * {@link dev.vertique.kafka.KafkaConsumerVerticle}.
 */
public enum KafkaTerminalOutcome {

    /**
     * The record was dispatched to the handler (or event bus) and the handler completed
     * successfully. No error occurred.
     */
    SUCCESS,

    /**
     * The record was skipped according to the {@link dev.vertique.kafka.ErrorStrategy#SKIP}
     * strategy after a dispatch failure. The offset is committed (when in MANUAL mode) and the
     * record is not retried.
     */
    SKIP,

    /**
     * A dispatch failure was recovered by a {@link KafkaConsumerInterceptor#recoverError} handler
     * before the configured error strategy was applied. The offset is committed and processing
     * moves to the next record.
     */
    RECOVERED,

    /**
     * A retry was scheduled for the record using the
     * {@link dev.vertique.kafka.ErrorStrategy#RETRY} strategy. The consumer is paused, seeked
     * back to the failed offset, and scheduled to resume after the backoff delay.
     */
    RETRY_SCHEDULED,

    /**
     * The record was successfully published to the dead-letter topic
     * ({@link dev.vertique.kafka.ErrorStrategy#DEAD_LETTER}). The offset is committed after the
     * DLQ publish completes.
     */
    DLQ_PUBLISHED,

    /**
     * A DLQ publish was attempted ({@link dev.vertique.kafka.ErrorStrategy#DEAD_LETTER}) but the
     * publish failed. The offset is NOT committed; the record remains uncommitted.
     */
    DLQ_FAILED,

    /**
     * The error handler itself failed while handling a dispatch error — the record's disposition
     * (commit, DLQ, retry) is unknown. Analogous to {@link #DLQ_FAILED}, but for a failure of the
     * error-handling future itself rather than a failed DLQ publish.
     */
    ERROR_HANDLER_FAILED,
}
