// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * One Kafka consumer record that reached its terminal outcome, as
 * {@link KafkaConsumerCaptureHook#onTerminalOutcome(KafkaConsumerTerminal)} delivers it.
 *
 * <p>{@link #identity()} is framework-owned: the consumer builds it from the broker's record before
 * any {@link KafkaConsumerInterceptor} runs and holds it for the record's whole lifetime, so no
 * interceptor can replace or alter it. Read the record's coordinates, key, headers and raw bytes
 * from it.
 *
 * <p>{@link #context()} is the dispatch context as the interceptor chain left it. An interceptor
 * may have replaced it wholesale, so it is the chain's view of the record, not the record's own
 * identity. It is {@code null} when the record never reached the interceptor chain: a
 * pre-deserialization filter rejection, no matching route, or a deserialization failure.
 *
 * @param identity the record's framework-owned identity; never {@code null}
 * @param context  the dispatch context after the interceptor chain ran, or {@code null} when the
 *                 record exited before the chain
 * @param outcome  the final disposition of the record; never {@code null}
 */
public record KafkaConsumerTerminal(
        KafkaRawRecordDisposition identity, @Nullable KafkaDispatchContext<?> context, KafkaTerminalOutcome outcome) {

    /** Validates required fields. */
    public KafkaConsumerTerminal {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(outcome, "outcome");
    }
}
