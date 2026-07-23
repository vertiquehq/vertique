// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * SPI for observing every Kafka outbox publish after serialization and result classification.
 *
 * <p>{@link #onOutboxPublish} is called exactly once per outbox publish attempt, after the payload
 * has been serialized to bytes and after the final {@link OutboxPublishResult} has been determined
 * (success, retryable failure, or permanent failure). This gives audit, metrics, and tracing
 * adapters a single, deterministic observation point from which all relevant send details are known.
 *
 * <h2>Observer-only contract</h2>
 *
 * <p>Implementations <strong>MUST NOT</strong> perform any action that affects the publish result,
 * the Kafka record content, or the {@link io.vertx.core.Future} returned to the outbox relay. The
 * hook fires <em>after</em> the result has been irrevocably determined. Any exception thrown by an
 * implementation is swallowed by the framework — it does not change the result or break subsequent
 * hook invocations.
 *
 * <h2>Payload</h2>
 *
 * <p>The {@code value} parameter is a no-copy {@link PayloadSource} over the already-serialized
 * bytes (the bytes that were or would have been put on the wire). It is constructed via
 * {@link PayloadSources#buffered(byte[], String)} and shares the underlying byte array — callers
 * must not mutate it. For serialization failures, {@code value} is absent ({@link PayloadSource}
 * is not provided — the parameter will be {@code null}).
 *
 * <h2>Outcomes</h2>
 *
 * <p>The {@code result} parameter carries the classified outcome:
 * <ul>
 *   <li>{@link OutboxPublishResult.Success} — Kafka acknowledged the record.</li>
 *   <li>{@link OutboxPublishResult.RetryableFailure} — a transient transport error occurred;
 *       the relay will back off and retry.</li>
 *   <li>{@link OutboxPublishResult.PermanentFailure} — a deterministic failure occurred
 *       (e.g. serialization error, reserved-header collision); the entry will be dead-lettered.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 *
 * <p>Hooks are ordered using the {@link OrderedExtension} contract: phase first, then ascending
 * {@link #priority()} (lower runs first within a phase), then {@link #orderKey()} as a stable
 * tie-break. Register via Dagger multibinding ({@code @IntoSet}) on
 * {@link TransactionalMessagingKafkaModule}.
 *
 * @see KafkaOutboxDestinationHandler
 * @see OutboxPublishResult
 */
public interface KafkaOutboxCaptureHook extends OrderedExtension {

    /**
     * Called once per outbox publish attempt after serialization and result classification.
     *
     * <p>Implementations MUST NOT throw checked exceptions. Any unchecked exception is swallowed
     * by the framework and does not affect the publish result or delivery behavior.
     *
     * @param topic   the Kafka topic the outbox entry targets; never {@code null}
     * @param key     the Kafka record key derived from
     *                {@link dev.vertique.inboxoutbox.OutboxEnvelope#aggregateId()}, or {@code null}
     *                if the aggregate ID was not set
     * @param value   a no-copy {@link PayloadSource} over the serialized wire bytes; {@code null}
     *                when serialization failed before any bytes were produced
     * @param headers the application headers from the outbox envelope; never {@code null}
     * @param result  the classified publish outcome; never {@code null}
     * @param entryId the string form of
     *                {@link dev.vertique.inboxoutbox.OutboxEnvelope#entryId()}; never {@code null}
     */
    default void onOutboxPublish(
            String topic,
            @Nullable String key,
            @Nullable PayloadSource value,
            Map<String, String> headers,
            OutboxPublishResult result,
            String entryId) {}
}
