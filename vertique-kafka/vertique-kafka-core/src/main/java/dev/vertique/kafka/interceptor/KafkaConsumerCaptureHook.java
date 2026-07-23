// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import dev.vertique.core.extension.OrderedExtension;

/**
 * SPI for observing the terminal outcome of each Kafka consumer record after the full dispatch
 * pipeline has settled.
 *
 * <p>{@link #onTerminalOutcome} is called exactly once per record at the terminal point — after
 * all async work (DLQ publish, seek, interceptor recovery, etc.) has completed — with the final
 * {@link KafkaTerminalOutcome} value. This gives evidence, metrics, and tracing hooks a single,
 * deterministic point at which all pipeline decisions are known.
 *
 * <p>See also {@link #onPreDispatchTerminalOutcome} for records that exit before deserialization.
 *
 * <p><strong>Observer-only contract:</strong> implementations MUST NOT perform any action that
 * affects commit, retry, or delivery behaviour. The hook is called after those decisions have
 * been irrevocably made. Side-effects that alter the Kafka consumer state from within a hook
 * will corrupt the pipeline in unpredictable ways.
 *
 * <p>Exceptions thrown by an implementation are swallowed — they do not affect the dispatch
 * outcome or break processing of subsequent hooks or records.
 *
 * <p>Hooks are ordered using the {@link OrderedExtension} contract: phase first (coarse), then
 * ascending {@link #priority()} (lower runs first within a phase), then {@link #orderKey()} as
 * a stable tie-break.
 *
 * <p>Register implementations via Dagger multibinding ({@code @IntoSet}).
 *
 * @see KafkaTerminalOutcome
 * @see KafkaConsumerInterceptor
 */
public interface KafkaConsumerCaptureHook extends OrderedExtension {

    /**
     * Called once per record at the terminal point of the dispatch pipeline, after all async
     * operations (DLQ publish, seek, recovery) have settled.
     *
     * <p>Implementations MUST NOT throw checked exceptions. Any unchecked exception is swallowed
     * by the framework and does not affect dispatch.
     *
     * @param ctx     the dispatch context at the time of the terminal event; the context is
     *                immutable and reflects the pre-dispatch state (deserialized value, headers,
     *                topic/partition/offset, retry count)
     * @param outcome the final disposition of this record
     */
    default void onTerminalOutcome(KafkaDispatchContext<?> ctx, KafkaTerminalOutcome outcome) {}

    /**
     * Called when a record exits the pipeline at a pre-dispatch point — before deserialization
     * completes or a route is resolved — so no {@link KafkaDispatchContext} is available.
     *
     * <p>Three exit paths reach this method:
     * <ol>
     *   <li>Pre-deserialization filter rejection ({@code entry.filter().accept()} returned
     *       {@code false}) — outcome is always {@link KafkaTerminalOutcome#SKIP}.</li>
     *   <li>Router-kind no-matching-route — outcome is always {@link KafkaTerminalOutcome#SKIP}.</li>
     *   <li>Deserialization failure — outcome is whatever {@code KafkaErrorHandler.handleError}
     *       returns for the exception (typically {@link KafkaTerminalOutcome#DLQ_PUBLISHED},
     *       {@link KafkaTerminalOutcome#RETRY_SCHEDULED}, {@link KafkaTerminalOutcome#DLQ_FAILED},
     *       or {@link KafkaTerminalOutcome#SKIP}).</li>
     * </ol>
     *
     * <p>The same observer-only contract applies: implementations MUST NOT affect commit, retry, or
     * delivery behaviour. Exceptions are swallowed by the framework.
     *
     * @param disposition the raw record metadata available before deserialization; non-null
     * @param outcome     the final disposition of this record; non-null
     */
    default void onPreDispatchTerminalOutcome(KafkaRawRecordDisposition disposition, KafkaTerminalOutcome outcome) {}
}
