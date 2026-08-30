// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadSource;
import io.vertx.core.AsyncResult;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * SPI for observing every Kafka producer send after it completes.
 *
 * <p>{@link #onSend} is called exactly once per send in the shared wire funnel of
 * {@link KafkaProducerFactory}, after the underlying {@code producer.send(record)} call settles
 * (success or failure). This gives evidence, metrics, and tracing hooks a single, deterministic
 * point from which all send details are known.
 *
 * <h2>Observer-only contract</h2>
 *
 * <p>Implementations <strong>MUST NOT</strong> perform any action that affects the send result,
 * record content, or the future returned to the caller. The hook fires <em>after</em> the result
 * has been irrevocably determined. Any exception thrown by an implementation is swallowed — it
 * does not change the send result or break processing of subsequent hooks.
 *
 * <h2>Origin and method</h2>
 *
 * <p>The {@code origin} parameter identifies how the record entered the funnel:
 * <ul>
 *   <li>{@link KafkaSendOrigin#DIRECT_PRODUCER} — sent via a {@link KafkaProducer @KafkaProducer}
 *       proxy method. The {@code producerMethod} parameter is <em>non-null</em> for this origin
 *       only, so downstream adapters can inspect method-level annotations.</li>
 *   <li>{@link KafkaSendOrigin#OUTBOX} — forwarded by the transactional outbox relay.</li>
 *   <li>{@link KafkaSendOrigin#DLQ} — a dead-letter publish from error handling.</li>
 *   <li>{@link KafkaSendOrigin#INTERNAL} — an internal framework send not covered above.</li>
 * </ul>
 *
 * <h2>Payload</h2>
 *
 * <p>The {@code value} parameter is a no-copy {@link PayloadSource} over the already-serialized
 * bytes (the bytes that were put on the wire). It is constructed via
 * {@link dev.vertique.core.payload.PayloadSources#buffered(byte[], String)} and shares the
 * underlying byte array — callers must not mutate it.
 *
 * <h2>Ordering</h2>
 *
 * <p>Hooks are ordered using the {@link OrderedExtension} contract: phase first, then ascending
 * {@link #priority()} (lower runs first within a phase), then {@link #orderKey()} as a stable
 * tie-break. Register via Dagger multibinding ({@code @IntoSet}).
 *
 * @see KafkaSendOrigin
 * @see KafkaProducerFactory
 */
public interface KafkaProducerCaptureHook extends OrderedExtension {

    /**
     * Called once per send after the Kafka {@code producer.send(record)} call settles.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param origin         the origin of this send; never {@code null}
     * @param topic          the target topic; never {@code null}
     * @param key            the record key, or {@code null} if none was provided
     * @param value          a no-copy {@link PayloadSource} over the serialized wire bytes; never
     *                       {@code null}
     * @param headers        the fully-merged wire headers (application + context propagation
     *                       headers); never {@code null}
     * @param producerMethod the {@link KafkaProducer @KafkaProducer} interface method that
     *                       initiated the send, or {@code null} for all origins except
     *                       {@link KafkaSendOrigin#DIRECT_PRODUCER}
     * @param result         the settled result of the send; the result's
     *                       {@link AsyncResult#succeeded()} or {@link AsyncResult#failed()} state
     *                       reflects whether Kafka acknowledged the record; never {@code null}
     */
    default void onSend(
            KafkaSendOrigin origin,
            String topic,
            @Nullable String key,
            PayloadSource value,
            Map<String, String> headers,
            @Nullable Method producerMethod,
            AsyncResult<RecordMetadata> result) {}
}
