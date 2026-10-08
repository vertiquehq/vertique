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
 * deterministic point at which all pipeline decisions are known, on every path a record can take.
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
 * <p>The method is abstract: a hook that still declares one of the pre-0.3.0 callbacks
 * ({@code onTerminalOutcome(KafkaDispatchContext, KafkaTerminalOutcome)} or
 * {@code onPreDispatchTerminalOutcome}) no longer compiles, with or without {@code @Override}.
 *
 * <p>Register implementations via Dagger multibinding ({@code @IntoSet}).
 *
 * @see KafkaTerminalOutcome
 * @see KafkaConsumerInterceptor
 */
public interface KafkaConsumerCaptureHook extends OrderedExtension {

    /**
     * Called once per record at the terminal point of the pipeline, after all async operations (DLQ
     * publish, seek, recovery) have settled, for every record the consumer received: dispatched,
     * filtered, unroutable, or failed before dispatch.
     *
     * <p>Take the record's coordinates, key, headers and raw bytes from
     * {@link KafkaConsumerTerminal#identity()}, which the consumer owns and no interceptor can
     * change. {@link KafkaConsumerTerminal#context()} is the interceptor chain's view of the record
     * and is {@code null} when the record exited before the chain: a pre-deserialization filter
     * rejection, a router-kind no-matching-route, or a deserialization failure.
     *
     * <p>For a record that exits before the chain, the outcome is {@link KafkaTerminalOutcome#SKIP} for
     * a filter rejection or a missing route, and whatever {@code KafkaErrorHandler.handleError}
     * returns for a deserialization failure (typically {@link KafkaTerminalOutcome#DLQ_PUBLISHED},
     * {@link KafkaTerminalOutcome#RETRY_SCHEDULED}, {@link KafkaTerminalOutcome#DLQ_FAILED}, or
     * {@link KafkaTerminalOutcome#SKIP}).
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param terminal the record's identity, interceptor-visible context, and final outcome
     */
    void onTerminalOutcome(KafkaConsumerTerminal terminal);
}
