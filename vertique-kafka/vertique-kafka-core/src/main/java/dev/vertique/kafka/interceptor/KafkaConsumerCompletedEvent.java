// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import java.util.Objects;

/**
 * The fact that one Kafka consumer record reached its final disposition, as
 * {@link KafkaConsumerInterceptor#onRecordCompleted} delivers it.
 *
 * <p>The event carries framework facts only: no key, headers, value or dispatch context. It can be
 * logged or handed to a metrics observer as is. The record's key, headers and raw value are
 * reachable through the {@link KafkaConsumerRecordView} passed alongside it.
 *
 * @param identity the record's framework-owned identity; the same instance as
 *                 {@link KafkaConsumerRecordView#identity()}; never {@code null}
 * @param outcome  the final disposition of the record; never {@code null}
 */
public record KafkaConsumerCompletedEvent(KafkaConsumerRecordIdentity identity, KafkaTerminalOutcome outcome) {

    /** Validates required components. */
    public KafkaConsumerCompletedEvent {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(outcome, "outcome");
    }
}
