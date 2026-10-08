// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import dev.vertique.core.payload.PayloadSource;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, framework-owned identity of one Kafka consumer record: where it came from, its key,
 * headers and raw bytes, exactly as the broker delivered them.
 *
 * <p>The consumer builds it before any {@link KafkaConsumerInterceptor} runs, so unlike a
 * {@link KafkaDispatchContext} it cannot be replaced by an interceptor. It reaches
 * {@link KafkaConsumerCaptureHook} as {@link KafkaConsumerTerminal#identity()} on every path a
 * record can take, including records that exit before a dispatch context exists (pre-deserialization
 * filter, no matching route, deserialization failure).
 *
 * @param consumerName the Kafka consumer binding name; non-null
 * @param topic        the source topic; non-null
 * @param partition    the source partition number (zero-based)
 * @param offset       the record offset within the partition
 * @param key          the raw record key; nullable (tombstone records have a null key)
 * @param headers      extracted header map; unmodifiable; non-null
 * @param rawEvidence  the raw record value as a {@link PayloadSource}, or
 *                     {@link dev.vertique.core.payload.PayloadSources#absent()} for tombstones; non-null
 * @param timestamp    the record timestamp (epoch milliseconds)
 * @param retryCount   number of times this record has been retried (0 = first attempt)
 */
public record KafkaRawRecordDisposition(
        String consumerName,
        String topic,
        int partition,
        long offset,
        @Nullable String key,
        Map<String, String> headers,
        PayloadSource rawEvidence,
        long timestamp,
        int retryCount) {

    /** Validates required fields and copies {@code headers} into an unmodifiable map. */
    public KafkaRawRecordDisposition {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(headers, "headers");
        headers = Map.copyOf(headers);
        Objects.requireNonNull(rawEvidence, "rawEvidence");
    }
}
