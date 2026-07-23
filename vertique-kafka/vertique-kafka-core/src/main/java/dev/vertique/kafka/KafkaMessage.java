// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable Kafka message context passed to {@link KafkaRecordHandler} implementations.
 *
 * @param value the deserialized message value
 * @param key the message key, or {@code null}
 * @param topic the source topic
 * @param partition the source partition
 * @param offset the message offset
 * @param timestamp the message timestamp (epoch milliseconds)
 * @param headers the message headers
 * @param <V> the value type
 */
public record KafkaMessage<V>(
        V value, String key, String topic, int partition, long offset, long timestamp, Map<String, String> headers) {

    /**
     * Defensive copy of headers in compact constructor.
     *
     * @param value the deserialized message value
     * @param key the message key, or {@code null}
     * @param topic the source topic
     * @param partition the source partition
     * @param offset the message offset
     * @param timestamp the message timestamp (epoch milliseconds)
     * @param headers the message headers
     */
    public KafkaMessage {
        headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
    }

    /**
     * Returns a single header value by name.
     *
     * @param name the header name
     * @return the header value, or empty if not present
     */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }
}
