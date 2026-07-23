// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.serialization;

import java.util.Map;

/**
 * SPI for serializing values to raw bytes for Kafka producer messages.
 *
 * <p>Implementations must be safe for concurrent use: the producer reuses one instance per method
 * across concurrent sends (see {@link KafkaSerdeProvider}).
 *
 * @param <V> the value type
 */
@FunctionalInterface
public interface KafkaSerializer<V> {

    /**
     * Serializes a value to raw bytes with topic and header context. Schema-registry serializers
     * (e.g. Avro) need the topic for subject naming ({@code <topic>-value}); JSON ignores both.
     *
     * @param value the value to serialize
     * @param topic the target Kafka topic
     * @param headers the record headers being sent
     * @return the serialized bytes
     */
    byte[] serialize(V value, String topic, Map<String, String> headers);

    /**
     * Whether this serializer may block the calling thread (e.g. a Schema Registry HTTP call on a
     * cache miss). When {@code true}, the framework offloads serialization off the Vert.x event loop
     * (the producer wraps the call in {@code executeBlocking}). JSON serializers stay {@code false}
     * and run synchronously on-loop. The {@link KafkaSerdeRegistry} wraps provider-built serializers
     * so this flag delegates to the resolving {@link KafkaSerdeProvider#mayBlock()} automatically.
     *
     * @return {@code true} if serialization may block; {@code false} (default) if it never blocks
     */
    default boolean mayBlock() {
        return false;
    }

    /**
     * Releases any resources held by this serializer (e.g. a Schema Registry client). The default is
     * a no-op — JSON serializers hold nothing. Registry-backed serializers (and the
     * {@link KafkaSerdeRegistry} wrapper) override it; the producer factory calls it on close so
     * per-method serdes do not leak.
     */
    default void close() {}
}
