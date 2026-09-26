// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import dev.vertique.core.payload.PayloadSource;
import io.vertx.core.AsyncResult;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * One settled Kafka producer send, as {@link KafkaProducerCaptureHook#onSend(KafkaProducerSend)}
 * receives it. New send details are added as components here rather than as further positional
 * hook parameters.
 *
 * @param origin    the origin of this send; never {@code null}
 * @param topic     the target topic; never {@code null}
 * @param key       the record key, or {@code null} if none was provided
 * @param value     a no-copy {@link PayloadSource} over the serialized wire bytes; never {@code null}
 * @param headers   the fully-merged wire headers (application + context propagation headers); never
 *                  {@code null}
 * @param operation the {@code @KafkaProducer} operation for {@link KafkaSendOrigin#DIRECT_PRODUCER};
 *                  {@code null} for all other origins
 * @param result    the settled result of the send; never {@code null}
 */
public record KafkaProducerSend(
        KafkaSendOrigin origin,
        String topic,
        @Nullable String key,
        PayloadSource value,
        Map<String, String> headers,
        @Nullable KafkaProducerOperation operation,
        AsyncResult<RecordMetadata> result) {}
