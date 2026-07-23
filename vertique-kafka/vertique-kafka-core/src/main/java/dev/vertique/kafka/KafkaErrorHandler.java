// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles record processing errors using the configured {@link ErrorStrategy}: SKIP, DEAD_LETTER,
 * or RETRY.
 *
 * <p>Consumer lifecycle operations (pause, resume, seek, commit) are delegated back to
 * {@link ConsumerControl} so this class remains decoupled from the Kafka consumer instance.
 */
@Slf4j
final class KafkaErrorHandler {

    // --- DLQ header constants ---

    private static final String DLQ_HEADER_SOURCE_TOPIC = "x-dlq-source-topic";
    private static final String DLQ_HEADER_SOURCE_PARTITION = "x-dlq-source-partition";
    private static final String DLQ_HEADER_SOURCE_OFFSET = "x-dlq-source-offset";
    private static final String DLQ_HEADER_CONSUMER = "x-dlq-consumer";
    private static final String DLQ_HEADER_ERROR = "x-dlq-error";

    private final ConsumerEntry entry;
    private final KafkaProducerFactory producerFactory;

    /**
     * Tracks per-record retry counts keyed by {@code "{topic}:{partition}:{offset}"}. Populated
     * only when {@link ErrorStrategy#RETRY} is active.
     */
    final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /**
     * Callback interface for consumer operations needed by error handling. Implemented by
     * {@link KafkaConsumerVerticle} to decouple from the Kafka consumer instance.
     */
    interface ConsumerControl {

        /** Pauses the consumer stream. */
        void pause();

        /**
         * Resumes the consumer stream after the given delay.
         *
         * @param delayMs delay in milliseconds before resuming
         */
        void scheduleResume(long delayMs);

        /**
         * Seeks the consumer to a specific partition offset.
         *
         * @param topic the topic name
         * @param partition the partition number
         * @param offset the target offset
         * @return a future that completes when the seek has finished
         */
        Future<Void> seekToOffset(String topic, int partition, long offset);

        /**
         * Commits the record offset if the commit strategy is {@link CommitStrategy#MANUAL}.
         *
         * @param record the record whose offset should be committed
         */
        void commitIfManual(KafkaConsumerRecord<String, byte[]> record);
    }

    /**
     * Creates a new error handler for the given consumer entry.
     *
     * @param entry the consumer entry describing error strategy, retry config, and DLQ topic
     * @param producerFactory the shared Kafka producer factory used for DLQ publishing
     */
    KafkaErrorHandler(ConsumerEntry entry, KafkaProducerFactory producerFactory) {
        this.entry = entry;
        this.producerFactory = producerFactory;
    }

    // --- Error routing ---

    /**
     * Handles a dispatch error according to the configured {@link ErrorStrategy} and returns a
     * future that resolves with the terminal outcome once all async work (DLQ publish, seek, etc.)
     * has settled.
     *
     * <p>Every existing side effect — {@code commitIfManual}, seek, {@code scheduleResume}, DLQ
     * publish, and logging — is preserved byte-for-byte. The only addition is the returned
     * {@link KafkaTerminalOutcome} value.
     *
     * @param record   the failed consumer record
     * @param rawBytes the raw record bytes (for DLQ publishing)
     * @param headers  the extracted headers
     * @param cause    the error that occurred
     * @param control  consumer control callbacks for pause/resume/seek/commit
     * @return a future that resolves with the terminal outcome after all async operations settle;
     *         the future itself always succeeds — errors during DLQ publish are mapped to
     *         {@link KafkaTerminalOutcome#DLQ_FAILED} rather than failing the future
     */
    Future<KafkaTerminalOutcome> handleError(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            Map<String, String> headers,
            Throwable cause,
            ConsumerControl control) {

        ErrorStrategy strategy = entry.config().errorStrategy();
        return switch (strategy) {
            case SKIP -> {
                log.warn(
                        "[{}] Skipping record topic={} partition={} offset={}: {}",
                        entry.name(),
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        cause.getMessage(),
                        cause);
                control.commitIfManual(record);
                yield Future.succeededFuture(KafkaTerminalOutcome.SKIP);
            }
            case DEAD_LETTER ->
                publishToDlq(record, rawBytes, headers, cause)
                        .map(v -> {
                            control.commitIfManual(record);
                            return KafkaTerminalOutcome.DLQ_PUBLISHED;
                        })
                        .recover(dlqCause -> {
                            log.error(
                                    "[{}] Failed to publish to DLQ, record will not be committed",
                                    entry.name(),
                                    dlqCause);
                            return Future.succeededFuture(KafkaTerminalOutcome.DLQ_FAILED);
                        });
            case RETRY -> handleRetry(record, rawBytes, headers, cause, control);
        };
    }

    // --- Retry strategy ---

    /**
     * Handles the RETRY error strategy using seek-based redelivery.
     *
     * <p>After a dispatch failure, this method pauses the consumer stream via {@link ConsumerControl}
     * and seeks back to the failed record's offset. The seek is necessary because the Kafka client's
     * internal fetch position has already advanced past the failed record. After the backoff period,
     * the consumer resumes and polls from the seeked offset, re-delivering the record through the
     * normal pipeline.
     *
     * <p>After {@link RetryConfig#maxRetries()} redeliveries, the record is routed to the
     * {@link RetryConfig#exhaustedStrategy()} (typically {@link ErrorStrategy#SKIP} or
     * {@link ErrorStrategy#DEAD_LETTER}).
     *
     * <p>All existing side effects (pause, seek, scheduleResume, commitIfManual, DLQ publish) are
     * preserved exactly. The return value threads out the terminal outcome once all async work
     * settles.
     *
     * @param record   the failed consumer record
     * @param rawBytes the raw record bytes (for DLQ publishing on exhaustion)
     * @param headers  the extracted headers
     * @param cause    the dispatch error
     * @param control  consumer control callbacks for pause/resume/seek/commit
     * @return a future that resolves with the terminal outcome once all async operations settle
     */
    private Future<KafkaTerminalOutcome> handleRetry(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            Map<String, String> headers,
            Throwable cause,
            ConsumerControl control) {

        RetryConfig retryConfig = entry.config().retryConfig();
        if (retryConfig == null) {
            retryConfig = RetryConfig.DEFAULT;
        }
        String retryKey = record.topic() + ":" + record.partition() + ":" + record.offset();
        int attempts = retryCounts.merge(retryKey, 1, Integer::sum);

        if (attempts > retryConfig.maxRetries()) {
            log.warn(
                    "[{}] Retry exhausted ({}/{}) for topic={} partition={} offset={}, applying exhausted strategy",
                    entry.name(),
                    attempts - 1,
                    retryConfig.maxRetries(),
                    record.topic(),
                    record.partition(),
                    record.offset());
            retryCounts.remove(retryKey);

            // Apply exhausted strategy — delegate to the same outcome-bearing paths
            ErrorStrategy exhausted = retryConfig.exhaustedStrategy();
            return switch (exhausted) {
                case SKIP -> {
                    log.warn("[{}] Retry exhausted — skipping record", entry.name());
                    control.commitIfManual(record);
                    yield Future.succeededFuture(KafkaTerminalOutcome.SKIP);
                }
                case DEAD_LETTER ->
                    publishToDlq(record, rawBytes, headers, cause)
                            .map(v -> {
                                control.commitIfManual(record);
                                return KafkaTerminalOutcome.DLQ_PUBLISHED;
                            })
                            .recover(dlqCause -> {
                                log.error(
                                        "[{}] Failed to publish exhausted-retry record to DLQ,"
                                                + " record will not be committed",
                                        entry.name(),
                                        dlqCause);
                                return Future.succeededFuture(KafkaTerminalOutcome.DLQ_FAILED);
                            });
                default -> {
                    log.error("[{}] Invalid exhausted strategy: {}", entry.name(), exhausted);
                    yield Future.succeededFuture(KafkaTerminalOutcome.SKIP);
                }
            };
        }

        long delayMs = retryConfig.delayMs(attempts - 1);
        log.warn(
                "[{}] Will retry via Kafka redelivery ({}/{}) topic={} partition={} offset={} in {}ms: {}",
                entry.name(),
                attempts,
                retryConfig.maxRetries(),
                record.topic(),
                record.partition(),
                record.offset(),
                delayMs,
                cause.getMessage());

        // Pause the stream to prevent further records from being dispatched during backoff.
        control.pause();

        // Seek back to the failed record's offset so the next poll re-delivers it.
        // The Kafka client's internal fetch position has already advanced past this offset;
        // seeking back is required to ensure the record is redelivered on resume.
        return control.seekToOffset(record.topic(), record.partition(), record.offset())
                .map(v -> {
                    // Resume after backoff — Kafka re-delivers from the seeked offset
                    control.scheduleResume(delayMs);
                    return KafkaTerminalOutcome.RETRY_SCHEDULED;
                })
                .recover(seekCause -> {
                    log.error(
                            "[{}] Failed to seek back to offset {} for retry — skipping record",
                            entry.name(),
                            record.offset(),
                            seekCause);
                    retryCounts.remove(retryKey);
                    control.scheduleResume(0);
                    return Future.succeededFuture(KafkaTerminalOutcome.RETRY_SCHEDULED);
                });
    }

    // --- DLQ publishing ---

    /**
     * Publishes a failed record to the dead-letter topic using the shared {@link KafkaProducerFactory}.
     * Adds error metadata as Kafka headers.
     *
     * @param record the failed consumer record
     * @param rawBytes the raw record bytes to republish
     * @param headers the extracted record headers
     * @param cause the error that caused the DLQ publish
     * @return a future that completes when the DLQ publish has been attempted
     */
    Future<Void> publishToDlq(
            KafkaConsumerRecord<String, byte[]> record, byte[] rawBytes, Map<String, String> headers, Throwable cause) {

        String dlqTopic = entry.config().deadLetterTopic();
        log.warn(
                "[{}] Publishing to DLQ '{}' for topic={} partition={} offset={}: {}",
                entry.name(),
                dlqTopic,
                record.topic(),
                record.partition(),
                record.offset(),
                cause.getMessage());

        // Build error headers as Map<String, String> for KafkaProducerFactory.send()
        Map<String, String> dlqHeaders = new HashMap<>(headers);
        dlqHeaders.put(DLQ_HEADER_SOURCE_TOPIC, record.topic());
        dlqHeaders.put(DLQ_HEADER_SOURCE_PARTITION, String.valueOf(record.partition()));
        dlqHeaders.put(DLQ_HEADER_SOURCE_OFFSET, String.valueOf(record.offset()));
        dlqHeaders.put(DLQ_HEADER_CONSUMER, entry.name());
        String msg = cause.getMessage();
        String errorDetail = cause.getClass().getSimpleName()
                + (msg != null ? ": " + msg.substring(0, Math.min(msg.length(), 200)) : "");
        dlqHeaders.put(DLQ_HEADER_ERROR, errorDetail);

        return producerFactory
                .sendForDlq(dlqTopic, record.key(), rawBytes, dlqHeaders)
                .mapEmpty();
    }
}
