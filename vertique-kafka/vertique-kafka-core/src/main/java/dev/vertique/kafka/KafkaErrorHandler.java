// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes a failed record to its dead-letter topic and logs a skipped one.
 *
 * <p>What happens to a failed record, whether it is retried, skipped or dead-lettered, and when its offset is
 * committed, is decided by {@link KafkaSettlement}. This class holds the two side effects that do not depend on the
 * consumer's state.
 */
@Slf4j
final class KafkaErrorHandler {

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
     * Creates a new error handler for the given consumer entry.
     *
     * @param entry the consumer entry describing the consumer name and the DLQ topic
     * @param producerFactory the shared Kafka producer factory used for DLQ publishing
     */
    KafkaErrorHandler(ConsumerEntry entry, KafkaProducerFactory producerFactory) {
        this.entry = entry;
        this.producerFactory = producerFactory;
    }

    /**
     * Logs a record that was skipped after a failure. Only the error type of a terminal failure reaches the log.
     *
     * @param consumer the consumer's name
     * @param topic the record's topic
     * @param partition the record's partition
     * @param offset the record's offset
     * @param cause the failure
     */
    static void logSkipped(String consumer, String topic, int partition, long offset, Throwable cause) {
        if (cause instanceof KafkaTerminalFailure terminal) {
            log.warn(
                    "[{}] Skipping record topic={} partition={} offset={} after terminal failure {}",
                    consumer,
                    topic,
                    partition,
                    offset,
                    terminal.errorType());
        } else {
            log.warn(
                    "[{}] Skipping record topic={} partition={} offset={}: {}",
                    consumer,
                    topic,
                    partition,
                    offset,
                    cause.getMessage(),
                    cause);
        }
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
