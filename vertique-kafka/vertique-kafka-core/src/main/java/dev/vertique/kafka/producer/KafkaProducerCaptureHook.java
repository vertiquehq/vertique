// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.kafka.KafkaRecordHeaders;
import io.vertx.core.AsyncResult;
import io.vertx.kafka.client.producer.RecordMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;

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
 * does not change the send result or break processing of subsequent hooks. A {@link LinkageError},
 * {@link AssertionError} or {@link StackOverflowError} is swallowed the same way. A swallowed
 * failure is logged by class name, with the failure itself at debug level only; a
 * {@link LinkageError} is logged at error level at a limited rate per hook class.
 *
 * <h2>Origin and method</h2>
 *
 * <p>The {@code origin} parameter identifies how the record entered the funnel:
 * <ul>
 *   <li>{@link KafkaSendOrigin#DIRECT_PRODUCER} — sent via a {@link KafkaProducer @KafkaProducer}
 *       proxy method. {@link KafkaProducerSend#operation()} (and the positional
 *       {@code producerMethod}) is <em>non-null</em> for this origin only, so downstream adapters
 *       can inspect the producer interface's type-level and the method's method-level
 *       annotations.</li>
 *   <li>{@link KafkaSendOrigin#OUTBOX} — forwarded by the transactional outbox relay.
 *       {@link KafkaProducerSend#originRef()} carries the outbox entry id for this origin, so a hook
 *       can join the wire bytes to the entry they belong to.</li>
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
     * Called once per send after the Kafka {@code producer.send(record)} call settles — by the
     * default {@link #onSend(KafkaProducerSend)}, which the framework calls; a hook that overrides
     * that form receives sends there instead.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation. A {@link LinkageError} and an {@link AssertionError} are
     * contained the same way, and so is a {@link StackOverflowError}; a {@link LinkageError} is
     * reported at error level at a limited rate.
     *
     * @deprecated this positional form cannot carry the origin reference
     *     ({@link KafkaProducerSend#originRef()}) or any send detail added later. Override
     *     {@link #onSend(KafkaProducerSend)} instead; it replaces this method. This form keeps
     *     working: the default {@link #onSend(KafkaProducerSend)} still delegates to it.
     * @param origin         the origin of this send; never {@code null}
     * @param topic          the target topic; never {@code null}
     * @param key            the record key, or {@code null} if none was provided
     * @param value          a no-copy {@link PayloadSource} over the serialized wire bytes; never
     *                       {@code null}
     * @param headers        the headers of the record as sent, in wire order: the application
     *                       headers followed by the framework context headers, with repeated
     *                       keys and binary values kept; never {@code null}
     * @param producerMethod the {@link KafkaProducer @KafkaProducer} interface method that
     *                       initiated the send, or {@code null} for all origins except
     *                       {@link KafkaSendOrigin#DIRECT_PRODUCER}
     * @param result         the settled result of the send; the result's
     *                       {@link AsyncResult#succeeded()} or {@link AsyncResult#failed()} state
     *                       reflects whether Kafka acknowledged the record; never {@code null}
     */
    @Deprecated
    default void onSend(
            KafkaSendOrigin origin,
            String topic,
            @Nullable String key,
            PayloadSource value,
            KafkaRecordHeaders headers,
            @Nullable Method producerMethod,
            AsyncResult<RecordMetadata> result) {}

    /**
     * Called once per send after the Kafka {@code producer.send(record)} call settles; the framework
     * always calls this method.
     *
     * <p>{@link KafkaProducerSend#operation()} carries the {@link KafkaProducer @KafkaProducer}
     * interface the sending proxy was created for, its name, and the method — read type-level
     * annotations from {@link KafkaProducerOperation#producerType()}, not from the method's
     * declaring class, which is a super-interface for an inherited send method.
     * {@link KafkaProducerSend#originRef()} carries the outbox entry id of an outbox send; the
     * positional form does not receive it.
     *
     * <p>The default delegates to the positional {@link #onSend(KafkaSendOrigin, String, String,
     * PayloadSource, KafkaRecordHeaders, Method, AsyncResult)}, so a hook overrides whichever form it needs — but
     * never make the positional form delegate back to this one, which would recurse.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. A {@link LinkageError} and an {@link AssertionError} are
     * contained the same way, and so is a {@link StackOverflowError}; a {@link LinkageError} is
     * reported at error level at a limited rate.
     *
     * @param send the settled send; never {@code null}
     */
    default void onSend(KafkaProducerSend send) {
        onSend(
                send.origin(),
                send.topic(),
                send.key(),
                send.value(),
                send.headers(),
                send.operation() != null ? send.operation().method() : null,
                send.result());
    }

    /**
     * Validates a {@link KafkaProducer @KafkaProducer} interface when {@link KafkaProducerFactory}
     * creates its proxy, so a configuration error fails application startup instead of surfacing,
     * or being swallowed, on a later send.
     *
     * <p>{@link KafkaProducerFactory#create(Class)} calls this once per hook, before the proxy is
     * built. An exception thrown here propagates out of {@code create} and stops the producer from
     * being created; unlike {@link #onSend}, this callback may throw. The default accepts every
     * producer interface.
     *
     * @param producerInterface the {@code @KafkaProducer} interface being created; never {@code null}
     */
    default void validateProducer(Class<?> producerInterface) {}
}
