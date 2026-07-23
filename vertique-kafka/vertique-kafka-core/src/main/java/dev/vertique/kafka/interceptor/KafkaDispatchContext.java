// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import dev.vertique.core.payload.PayloadSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable dispatch context passed to {@link KafkaConsumerInterceptor} callbacks.
 *
 * <p>Uses copy-on-write semantics: {@link #withFiltered(boolean)} and
 * {@link #withAttribute(String, Object)} return new instances.
 *
 * <p>{@link #rawEvidence()} exposes a neutral, no-eager-copy view of the wire value via
 * {@link PayloadSource}. For normal records the {@link dev.vertique.core.payload.PayloadSources#buffered(byte[],
 * String)} factory captures the byte array by reference — no defensive copy is performed. The
 * a downstream capture layer can read it on a worker thread via
 * {@link PayloadSource#bufferedStream()}.
 * For tombstone records (Kafka delete markers with a {@code null} value),
 * {@link dev.vertique.core.payload.PayloadSources#absent()} is used so that downstream capture
 * code does not NPE when calling {@code declaredLength()} or {@code bufferedStream()}.
 *
 * @param consumerName the Kafka consumer binding name
 * @param topic the source topic
 * @param partition the source partition
 * @param offset the message offset
 * @param key the message key, or {@code null}
 * @param value the deserialized value
 * @param rawEvidence a neutral, no-eager-copy view of the original wire bytes;
 *     {@link dev.vertique.core.payload.PayloadKind#BUFFERED} for records that carry a value,
 *     {@link dev.vertique.core.payload.PayloadKind#ABSENT} for tombstone records (null value)
 * @param headers the Kafka headers
 * @param timestamp the message timestamp (epoch milliseconds)
 * @param retryCount the number of times this record has been retried (0-based; 0 means first
 *     attempt). Populated from the Kafka-native retry tracking map when
 *     {@link dev.vertique.kafka.ErrorStrategy#RETRY} is active.
 * @param filtered if {@code true}, the record will be skipped (not dispatched)
 * @param attributes mutable attribute map for interceptor communication
 * @param <V> the value type
 */
public record KafkaDispatchContext<V>(
        String consumerName,
        String topic,
        int partition,
        long offset,
        String key,
        V value,
        PayloadSource rawEvidence,
        Map<String, String> headers,
        long timestamp,
        int retryCount,
        boolean filtered,
        Map<String, Object> attributes) {

    /**
     * Defensive copies of headers and attributes in compact constructor. {@link PayloadSource} is a
     * lazy view that holds no mutable state of its own — no defensive copy is needed.
     *
     * @param consumerName the Kafka consumer binding name
     * @param topic the source topic
     * @param partition the source partition
     * @param offset the message offset
     * @param key the message key, or {@code null}
     * @param value the deserialized value
     * @param rawEvidence a neutral, no-eager-copy view of the original wire bytes; ABSENT for tombstones
     * @param headers the Kafka headers
     * @param timestamp the message timestamp (epoch milliseconds)
     * @param retryCount the number of times this record has been retried (0-based)
     * @param filtered if {@code true}, the record will be skipped (not dispatched)
     * @param attributes mutable attribute map for interceptor communication
     */
    public KafkaDispatchContext {
        headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
        attributes = attributes != null ? Collections.unmodifiableMap(attributes) : Collections.emptyMap();
    }

    /**
     * Returns a copy with the filtered flag changed.
     *
     * @param filtered the new filtered flag
     * @return a new context with the updated flag
     */
    public KafkaDispatchContext<V> withFiltered(boolean filtered) {
        return new KafkaDispatchContext<>(
                consumerName,
                topic,
                partition,
                offset,
                key,
                value,
                rawEvidence,
                headers,
                timestamp,
                retryCount,
                filtered,
                attributes);
    }

    /**
     * Returns a copy with an additional attribute.
     *
     * @param attrKey the attribute key
     * @param attrValue the attribute value
     * @return a new context with the added attribute
     */
    public KafkaDispatchContext<V> withAttribute(String attrKey, Object attrValue) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(attrKey, attrValue);
        return new KafkaDispatchContext<>(
                consumerName,
                topic,
                partition,
                offset,
                key,
                value,
                rawEvidence,
                headers,
                timestamp,
                retryCount,
                filtered,
                newAttrs);
    }
}
