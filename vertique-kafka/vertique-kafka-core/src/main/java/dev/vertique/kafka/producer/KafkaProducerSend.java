// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.kafka.KafkaRecordHeaders;
import io.vertx.core.AsyncResult;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;

/**
 * One settled Kafka producer send, as {@link KafkaProducerCaptureHook#onSend(KafkaProducerSend)}
 * receives it. New send details are added as components here rather than as further positional
 * hook parameters.
 *
 * @param origin    the origin of this send; never {@code null}
 * @param topic     the target topic; never {@code null}
 * @param key       the record key, or {@code null} if none was provided
 * @param value     a no-copy {@link PayloadSource} over the serialized wire bytes; never {@code null}
 * @param headers   the headers of the record as sent, in wire order: the application headers followed
 *                  by the framework context headers, with repeated keys and binary values kept;
 *                  never {@code null}
 * @param operation the {@code @KafkaProducer} operation for {@link KafkaSendOrigin#DIRECT_PRODUCER};
 *                  {@code null} for all other origins
 * @param result    the settled result of the send; never {@code null}
 * @param originRef the caller's reference to what this send carries: the outbox entry id for a
 *                  {@link KafkaSendOrigin#OUTBOX} send made through
 *                  {@link KafkaProducerFactory#sendForOutbox(String, String, byte[], KafkaRecordHeaders,
 *                  dev.vertique.core.context.DurableMetadata, String)}; {@code null} for every other
 *                  send
 */
public record KafkaProducerSend(
        KafkaSendOrigin origin,
        String topic,
        @Nullable String key,
        PayloadSource value,
        KafkaRecordHeaders headers,
        @Nullable KafkaProducerOperation operation,
        AsyncResult<RecordMetadata> result,
        @Nullable String originRef) {

    /**
     * Creates a send without an origin reference.
     *
     * @param origin    the origin of this send; never {@code null}
     * @param topic     the target topic; never {@code null}
     * @param key       the record key, or {@code null} if none was provided
     * @param value     a no-copy {@link PayloadSource} over the serialized wire bytes; never {@code null}
     * @param headers   the headers of the record as sent, in wire order; never {@code null}
     * @param operation the {@code @KafkaProducer} operation for {@link KafkaSendOrigin#DIRECT_PRODUCER};
     *                  {@code null} for all other origins
     * @param result    the settled result of the send; never {@code null}
     */
    public KafkaProducerSend(
            KafkaSendOrigin origin,
            String topic,
            @Nullable String key,
            PayloadSource value,
            KafkaRecordHeaders headers,
            @Nullable KafkaProducerOperation operation,
            AsyncResult<RecordMetadata> result) {
        this(origin, topic, key, value, headers, operation, result, null);
    }
}
