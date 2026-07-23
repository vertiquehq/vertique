// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import io.vertx.core.Future;

/**
 * Handler interface for custom Kafka record processing (Model 4).
 *
 * <p>Implement this interface and annotate the class with {@link KafkaListener} for
 * full control over message routing, transformation, and multi-service dispatch.
 *
 * @param <V> the deserialized value type
 * @see KafkaListener
 */
@FunctionalInterface
public interface KafkaRecordHandler<V> {

    /**
     * Handles a single Kafka record.
     *
     * @param message the Kafka message with deserialized value and metadata
     * @return a future that completes when processing is done
     */
    Future<Void> handle(KafkaMessage<V> message);
}
