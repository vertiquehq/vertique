// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Kafka's default for {@code max.poll.interval.ms}. */
    private static final long DEFAULT_MAX_POLL_INTERVAL_MS = 300_000L;

    // --- DLQ header constants ---

    /** The header keys this handler writes; an inbound header with one of them is not forwarded. */
    private static final Set<String> DLQ_HEADER_KEYS = Set.of(
            KafkaDlqHeaders.SOURCE_TOPIC,
            KafkaDlqHeaders.SOURCE_PARTITION,
            KafkaDlqHeaders.SOURCE_OFFSET,
            KafkaDlqHeaders.CONSUMER,
            KafkaDlqHeaders.ERROR);

    private final ConsumerEntry entry;
    private final KafkaProducerFactory producerFactory;

    /**
     * Tracks per-record retry counts keyed by {@code "{topic}:{partition}:{offset}"}. Populated
     * only when {@link ErrorStrategy#RETRY} is active.
     */
    final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /**
     * Counts failed dead-letter publishes per record, keyed like {@link #retryCounts}. Populated only
     * for an entry that forces its strategies, which redelivers a record whose publish failed.
     */
    final Map<String, Integer> dlqFailures = new ConcurrentHashMap<>();

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
     * <p>A {@link KafkaRedeliverFailure} on an entry that forces its strategies spends no retry: the
     * consumer pauses, seeks back to the record and resumes after a backoff, and the outcome is
     * {@link KafkaTerminalOutcome#RETRY_SCHEDULED}. On any other entry it takes the strategy like any
     * other failure.
     *
     * @param record   the failed consumer record
     * @param rawBytes the raw record bytes (for DLQ publishing)
     * @param headers  the record's headers as received; forwarded to the dead-letter topic
     * @param cause    the error that occurred
     * @param control  consumer control callbacks for pause/resume/seek/commit
     * @return a future that resolves with the terminal outcome after all async operations settle;
     *         errors during DLQ publish are mapped to {@link KafkaTerminalOutcome#DLQ_FAILED} rather
     *         than failing the future. The one exception is an entry that forces its strategies: its
     *         future fails when a seek back to the record could not be confirmed, because the record
     *         is then neither settled nor redelivered
     */
    Future<KafkaTerminalOutcome> handleError(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            KafkaRecordHeaders headers,
            Throwable cause,
            ConsumerControl control) {

        if (cause instanceof KafkaTerminalFailure terminal) {
            return handleTerminalFailure(record, rawBytes, headers, terminal, control);
        }

        if (cause instanceof KafkaRedeliverFailure && entry.forceStrategies()) {
            // The record is fine and the system is busy: deliver it again without spending a retry.
            return seekBack(record, control, "the handler asked to receive it again")
                    .map(v -> KafkaTerminalOutcome.RETRY_SCHEDULED);
        }

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
                publish(record, rawBytes, headers, cause)
                        .map(v -> {
                            dlqFailures.remove(positionKey(record));
                            control.commitIfManual(record);
                            return KafkaTerminalOutcome.DLQ_PUBLISHED;
                        })
                        .recover(dlqCause -> {
                            log.error(
                                    "[{}] Failed to publish to DLQ, record will not be committed",
                                    entry.name(),
                                    dlqCause);
                            return dlqPublishFailed(record, control);
                        });
            case RETRY -> handleRetry(record, rawBytes, headers, cause, control);
        };
    }

    // --- Terminal failure ---

    /**
     * Applies the binding's exhausted strategy to a record whose handler failed with a
     * {@link KafkaTerminalFailure}, at once: the record is neither retried nor does it pause the
     * consumer. Under {@link ErrorStrategy#DEAD_LETTER} it is published to the dead-letter topic and
     * committed; under {@link ErrorStrategy#SKIP} it is committed and the skip is logged. Either way
     * the record's retry count, if an earlier failure left one, is removed.
     *
     * <p>For a binding that retries, the exhausted strategy is {@link RetryConfig#exhaustedStrategy()};
     * otherwise it is the binding's error strategy. Only the error type reaches the log and the
     * dead-letter error header.
     *
     * @param record the failed consumer record
     * @param rawBytes the raw record bytes (for DLQ publishing)
     * @param headers the record's headers as received; forwarded to the dead-letter topic
     * @param terminal the terminal failure
     * @param control consumer control callbacks for commit
     * @return a future that resolves with the terminal outcome after the dead-letter publish settles
     */
    private Future<KafkaTerminalOutcome> handleTerminalFailure(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            KafkaRecordHeaders headers,
            KafkaTerminalFailure terminal,
            ConsumerControl control) {

        retryCounts.remove(record.topic() + ":" + record.partition() + ":" + record.offset());

        ErrorStrategy strategy = entry.config().errorStrategy();
        if (strategy == ErrorStrategy.RETRY) {
            RetryConfig retryConfig = entry.config().retryConfig();
            strategy = (retryConfig != null ? retryConfig : RetryConfig.DEFAULT).exhaustedStrategy();
        }
        return switch (strategy) {
            case DEAD_LETTER ->
                publish(record, rawBytes, headers, terminal)
                        .map(v -> {
                            dlqFailures.remove(positionKey(record));
                            control.commitIfManual(record);
                            return KafkaTerminalOutcome.DLQ_PUBLISHED;
                        })
                        .recover(dlqCause -> {
                            log.error(
                                    "[{}] Failed to publish terminal failure {} to DLQ, record will not be committed",
                                    entry.name(),
                                    terminal.errorType(),
                                    dlqCause);
                            return dlqPublishFailed(record, control);
                        });
            case SKIP -> {
                log.warn(
                        "[{}] Skipping record topic={} partition={} offset={} after terminal failure {}",
                        entry.name(),
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        terminal.errorType());
                control.commitIfManual(record);
                yield Future.succeededFuture(KafkaTerminalOutcome.SKIP);
            }
            default -> {
                log.error("[{}] Invalid exhausted strategy: {}", entry.name(), strategy);
                yield Future.succeededFuture(KafkaTerminalOutcome.SKIP);
            }
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
     * @param headers  the record's headers as received; forwarded on exhaustion to the dead-letter
     *                 topic
     * @param cause    the dispatch error
     * @param control  consumer control callbacks for pause/resume/seek/commit
     * @return a future that resolves with the terminal outcome once all async operations settle
     */
    private Future<KafkaTerminalOutcome> handleRetry(
            KafkaConsumerRecord<String, byte[]> record,
            byte[] rawBytes,
            KafkaRecordHeaders headers,
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

            // Apply exhausted strategy — delegate to the same outcome-bearing paths
            ErrorStrategy exhausted = retryConfig.exhaustedStrategy();
            // A forced entry whose dead-letter publish fails is redelivered; keeping the count makes the
            // redelivery go straight back to the publish instead of through every retry again.
            if (!(entry.forceStrategies() && exhausted == ErrorStrategy.DEAD_LETTER)) {
                retryCounts.remove(retryKey);
            }
            return switch (exhausted) {
                case SKIP -> {
                    log.warn("[{}] Retry exhausted — skipping record", entry.name());
                    control.commitIfManual(record);
                    yield Future.succeededFuture(KafkaTerminalOutcome.SKIP);
                }
                case DEAD_LETTER ->
                    publish(record, rawBytes, headers, cause)
                            .map(v -> {
                                retryCounts.remove(retryKey);
                                dlqFailures.remove(retryKey);
                                control.commitIfManual(record);
                                return KafkaTerminalOutcome.DLQ_PUBLISHED;
                            })
                            .recover(dlqCause -> {
                                log.error(
                                        "[{}] Failed to publish exhausted-retry record to DLQ,"
                                                + " record will not be committed",
                                        entry.name(),
                                        dlqCause);
                                return dlqPublishFailed(record, control);
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
                    if (entry.forceStrategies()) {
                        // Not a settled record: the caller keeps the slot and the consumer paused.
                        return Future.failedFuture(seekCause);
                    }
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

    // --- Failed dead-letter publish ---

    /**
     * Starts a dead-letter publish. An entry that forces its strategies treats a synchronous throw,
     * including an {@link Error}, as a failed publish, so the record is sought back to like any other
     * whose publish failed. Any other entry lets the throw propagate, as it always has.
     *
     * @param record the failed record
     * @param rawBytes the raw record bytes
     * @param headers the record's headers as received
     * @param cause the error that caused the publish
     * @return the publish future
     */
    private Future<Void> publish(
            KafkaConsumerRecord<String, byte[]> record, byte[] rawBytes, KafkaRecordHeaders headers, Throwable cause) {
        if (!entry.forceStrategies()) {
            return publishToDlq(record, rawBytes, headers, cause);
        }
        try {
            return publishToDlq(record, rawBytes, headers, cause);
        } catch (Throwable thrown) {
            return Future.failedFuture(thrown);
        }
    }

    /**
     * Settles a record whose dead-letter publish failed. Its offset is not committed. An entry that
     * forces its strategies also pauses the consumer, seeks back to the record and resumes after a
     * backoff, as {@link #handleRetry} does, so the record is delivered again and a later record
     * cannot commit past it; the backoff grows with the failures of that record. When the seek
     * cannot be confirmed the returned future fails, and the record is not settled. Any other entry
     * leaves the record uncommitted and goes on, as it always has.
     *
     * @param record the record whose publish failed
     * @param control consumer control callbacks for pause/seek/resume
     * @return a future that resolves with {@link KafkaTerminalOutcome#DLQ_FAILED}; on a forced entry
     *     it fails when the seek back could not be confirmed
     */
    private Future<KafkaTerminalOutcome> dlqPublishFailed(
            KafkaConsumerRecord<String, byte[]> record, ConsumerControl control) {

        if (!entry.forceStrategies()) {
            return Future.succeededFuture(KafkaTerminalOutcome.DLQ_FAILED);
        }
        return seekBack(record, control, "its dead-letter publish failed");
    }

    /**
     * Pauses the consumer, seeks back to the record and resumes after a backoff that grows with the
     * failures of that record, so the record is delivered again. Only for an entry that forces its
     * strategies. The returned future succeeds once the seek is confirmed; every throw, including an
     * {@link Error}, and a failed seek fail it, and the record is then not settled.
     *
     * @param record the record to deliver again
     * @param control consumer control callbacks for pause/seek/resume
     * @param reason why the record is delivered again, for the log
     * @return a future that resolves with {@link KafkaTerminalOutcome#DLQ_FAILED} once the seek is
     *     confirmed and the resume is scheduled
     */
    Future<KafkaTerminalOutcome> seekBack(
            KafkaConsumerRecord<String, byte[]> record, ConsumerControl control, String reason) {

        try {
            RetryConfig retryConfig = entry.config().retryConfig();
            if (retryConfig == null) {
                retryConfig = RetryConfig.DEFAULT;
            }
            int failures = dlqFailures.merge(positionKey(record), 1, Integer::sum);
            long delayMs = Math.min(retryConfig.delayMs(failures - 1), longestPauseMs());
            log.warn(
                    "[{}] Will deliver topic={} partition={} offset={} again in {}ms; {}",
                    entry.name(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    delayMs,
                    reason);
            control.pause();
            return control.seekToOffset(record.topic(), record.partition(), record.offset())
                    .map(v -> {
                        control.scheduleResume(delayMs);
                        return KafkaTerminalOutcome.DLQ_FAILED;
                    });
        } catch (Throwable thrown) {
            return Future.failedFuture(thrown);
        }
    }

    /**
     * The longest pause of a redelivery that repeats without limit. A paused consumer does not poll, so a
     * pause past {@code max.poll.interval.ms} would evict it from its group; half of it leaves time to
     * handle the record that the resume delivers.
     */
    private long longestPauseMs() {
        String configured = entry.config().kafkaProperties().get("max.poll.interval.ms");
        return (configured == null ? DEFAULT_MAX_POLL_INTERVAL_MS : Long.parseLong(configured.trim())) / 2;
    }

    /**
     * Forgets the retry and dead-letter failure counts of a record, for a record that this consumer
     * will not be given again.
     *
     * @param positionKey the record's position as {@code topic:partition:offset}
     */
    void forget(String positionKey) {
        retryCounts.remove(positionKey);
        dlqFailures.remove(positionKey);
    }

    static String positionKey(KafkaConsumerRecord<String, byte[]> record) {
        return record.topic() + ":" + record.partition() + ":" + record.offset();
    }

    // --- DLQ publishing ---

    /**
     * Publishes a failed record to the dead-letter topic using the shared {@link KafkaProducerFactory}.
     *
     * <p>The dead-letter record carries the failed record's headers as they were received, in
     * order, with repeated keys, binary values and framework context headers kept verbatim, followed
     * by five {@code x-dlq-*} headers that describe the failure. Two kinds of inbound header are left
     * out: one whose key is exactly one of those five (a record that was dead-lettered before would
     * otherwise carry stale ones next to the new ones), and one with a {@code null} value, which a
     * producer record cannot carry.
     *
     * @param record the failed consumer record
     * @param rawBytes the raw record bytes to republish
     * @param headers the failed record's headers as received
     * @param cause the error that caused the DLQ publish
     * @return a future that completes when the DLQ publish has been attempted
     */
    Future<Void> publishToDlq(
            KafkaConsumerRecord<String, byte[]> record, byte[] rawBytes, KafkaRecordHeaders headers, Throwable cause) {

        String dlqTopic = entry.config().deadLetterTopic();
        log.warn(
                "[{}] Publishing to DLQ '{}' for topic={} partition={} offset={}: {}",
                entry.name(),
                dlqTopic,
                record.topic(),
                record.partition(),
                record.offset(),
                cause instanceof KafkaTerminalFailure terminal ? terminal.errorType() : cause.getMessage());

        // The header objects are immutable, so the forwarded ones are reused rather than copied.
        List<KafkaRecordHeader> dlqHeaders = new ArrayList<>(headers.entries().size() + DLQ_HEADER_KEYS.size());
        for (KafkaRecordHeader header : headers) {
            if (header.hasValue() && !DLQ_HEADER_KEYS.contains(header.key())) {
                dlqHeaders.add(header);
            }
        }
        dlqHeaders.add(KafkaRecordHeader.ofUtf8(KafkaDlqHeaders.SOURCE_TOPIC, record.topic()));
        dlqHeaders.add(KafkaRecordHeader.ofUtf8(KafkaDlqHeaders.SOURCE_PARTITION, String.valueOf(record.partition())));
        dlqHeaders.add(KafkaRecordHeader.ofUtf8(KafkaDlqHeaders.SOURCE_OFFSET, String.valueOf(record.offset())));
        dlqHeaders.add(KafkaRecordHeader.ofUtf8(KafkaDlqHeaders.CONSUMER, entry.name()));
        String errorDetail;
        if (cause instanceof KafkaTerminalFailure terminal) {
            errorDetail = terminal.errorType();
        } else {
            String msg = cause.getMessage();
            errorDetail = cause.getClass().getSimpleName()
                    + (msg != null ? ": " + msg.substring(0, Math.min(msg.length(), 200)) : "");
        }
        dlqHeaders.add(KafkaRecordHeader.ofUtf8(KafkaDlqHeaders.ERROR, errorDetail));

        return producerFactory
                .sendForDlq(dlqTopic, record.key(), rawBytes, new KafkaRecordHeaders(dlqHeaders))
                .mapEmpty();
    }
}
