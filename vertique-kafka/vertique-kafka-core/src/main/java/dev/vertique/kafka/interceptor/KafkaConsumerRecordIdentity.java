// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import java.util.Objects;

/**
 * Framework-owned identity of one delivery of a Kafka consumer record: which consumer received it,
 * where it came from, and which attempt this is.
 *
 * <p>The consumer builds it from the broker's record before the pre-deserialization filter, route
 * resolution, deserialization and any {@link KafkaConsumerInterceptor} run. Unlike a
 * {@link KafkaDispatchContext}, an interceptor cannot replace it. It carries no key, headers or
 * value, so it is safe to log or to hand to a metrics observer as is.
 *
 * <p>The coordinates ({@code topic}, {@code partition}, {@code offset}) name the record, not one
 * processing attempt: a redelivered record has the same coordinates and a higher
 * {@code retryCount}.
 *
 * @param consumerName the Kafka consumer binding name; never {@code null}
 * @param topic        the source topic; never {@code null}
 * @param partition    the source partition number (zero-based)
 * @param offset       the record offset within the partition
 * @param timestamp    the record timestamp (epoch milliseconds)
 * @param retryCount   the number of retries before this attempt ({@code 0} on first delivery)
 */
public record KafkaConsumerRecordIdentity(
        String consumerName, String topic, int partition, long offset, long timestamp, int retryCount) {

    /** Validates required components. */
    public KafkaConsumerRecordIdentity {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(topic, "topic");
    }
}
