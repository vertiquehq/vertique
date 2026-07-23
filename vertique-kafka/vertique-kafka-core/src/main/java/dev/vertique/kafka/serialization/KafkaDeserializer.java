// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.serialization;

import dev.vertique.kafka.DeserializationException;
import java.util.Map;

/**
 * SPI for deserializing Kafka record values from raw bytes.
 *
 * <p>The signature includes topic and headers to support schema-registry-based
 * deserializers (e.g., Avro) that need topic context.
 *
 * <p>Implementations must be safe for concurrent use: the framework reuses one instance across
 * records and across consumer deployment instances (see {@link KafkaSerdeProvider}).
 *
 * @param <V> the deserialized value type
 */
@FunctionalInterface
public interface KafkaDeserializer<V> {

    /**
     * Deserializes raw bytes into a typed value.
     *
     * @param data the raw message bytes
     * @param topic the Kafka topic (needed by Avro/Schema Registry deserializers)
     * @param headers the record headers (for content-type-based deserialization)
     * @return the deserialized value
     * @throws DeserializationException if deserialization fails
     */
    V deserialize(byte[] data, String topic, Map<String, String> headers) throws DeserializationException;

    /**
     * Whether this deserializer may block the calling thread (e.g. a Schema Registry HTTP call on a
     * cache miss). When {@code true}, the framework forces the consumer verticle onto the worker
     * threading model so the blocking deserialize never runs on a Vert.x event-loop thread. JSON
     * deserializers stay {@code false}. A custom Model-2 deserializer can opt into the worker-threading
     * enforcement by overriding this to return {@code true}. The {@link KafkaSerdeRegistry} wraps
     * provider-built deserializers so this flag delegates to the resolving
     * {@link KafkaSerdeProvider#mayBlock()} automatically.
     *
     * @return {@code true} if deserialization may block; {@code false} (default) if it never blocks
     */
    default boolean mayBlock() {
        return false;
    }

    /**
     * Releases any resources held by this deserializer (e.g. a Schema Registry client and its schema
     * cache). The default is a no-op — JSON deserializers hold nothing. Registry-backed deserializers
     * (and the {@link KafkaSerdeRegistry} wrapper) override it.
     *
     * <p>The framework closes the <em>per-deployment</em> deserializers it builds (router route and
     * routing deserializers) when the owning consumer verticle is undeployed. The single
     * BINDING/HANDLER deserializer built per {@code ConsumerEntry} is reused across deployment
     * instances and redeploys, so it is treated as application-lifetime and is not closed on undeploy
     * (closing it would be a use-after-close on the next deploy).
     */
    default void close() {}
}
