// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Immutable Kafka message context passed to {@link KafkaRecordHandler} implementations.
 *
 * <p>The headers are the record's headers as they are on the wire: every header in order, with
 * repeated keys, {@code null} values and binary values. Read one header with
 * {@link KafkaRecordHeaders#lastHeader(String)}, or one text value per key from
 * {@link KafkaRecordHeaders#asMap()}:
 *
 * <pre>{@code
 * Optional<KafkaRecordHeader> signature = message.headers().lastHeader("signature");
 * String eventType = message.headers().asMap().get("event-type");
 * }</pre>
 *
 * @param value the deserialized message value
 * @param key the message key, or {@code null}
 * @param topic the source topic
 * @param partition the source partition
 * @param offset the message offset
 * @param timestamp the message timestamp (epoch milliseconds)
 * @param headers the message headers as received, in wire order; immutable; never {@code null}
 * @param <V> the value type
 */
public record KafkaMessage<V>(
        V value, String key, String topic, int partition, long offset, long timestamp, KafkaRecordHeaders headers) {

    /**
     * Replaces {@code null} headers with {@link KafkaRecordHeaders#empty()}. The headers are
     * immutable, so they are held as given and not copied.
     *
     * @param value the deserialized message value
     * @param key the message key, or {@code null}
     * @param topic the source topic
     * @param partition the source partition
     * @param offset the message offset
     * @param timestamp the message timestamp (epoch milliseconds)
     * @param headers the message headers, or {@code null} for none
     */
    public KafkaMessage {
        headers = headers != null ? headers : KafkaRecordHeaders.empty();
    }
}
