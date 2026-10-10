// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import dev.vertique.services.dispatch.DispatchContext;
import java.util.Optional;

/**
 * Kafka record metadata accessible on the service handler side during dispatch.
 *
 * <p>Propagated through the event bus via {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext()}
 * and stored in the dispatch-scoped {@link DispatchContext}. Handler methods can declare this
 * type as a parameter for auto-injection, or use {@link #current()} in direct implementations.
 *
 * <p>The headers are the record's headers as they are on the wire: every header in order, with
 * repeated keys, {@code null} values and binary values. Read one header with
 * {@link KafkaRecordHeaders#lastHeader(String)}, or one text value per key from
 * {@link KafkaRecordHeaders#asMap()}.
 *
 * @param consumerName the Kafka consumer binding name
 * @param topic the source topic
 * @param partition the source partition
 * @param offset the message offset
 * @param key the message key, or {@code null}
 * @param timestamp the message timestamp (epoch milliseconds)
 * @param correlationId the correlation ID (from header or auto-generated)
 * @param headers all Kafka headers as received, in wire order; immutable; never {@code null}
 */
@DispatchContextValue
public record KafkaRecordContext(
        String consumerName,
        String topic,
        int partition,
        long offset,
        String key,
        long timestamp,
        String correlationId,
        KafkaRecordHeaders headers)
        implements ContextValue {

    /**
     * Replaces {@code null} headers with {@link KafkaRecordHeaders#empty()}.
     *
     * @param consumerName the Kafka consumer binding name
     * @param topic the source topic
     * @param partition the source partition
     * @param offset the message offset
     * @param key the message key, or {@code null}
     * @param timestamp the message timestamp (epoch milliseconds)
     * @param correlationId the correlation ID (from header or auto-generated)
     * @param headers all Kafka headers, or {@code null} for none
     */
    public KafkaRecordContext {
        // The header collection is an immutable snapshot, so it is held as given: no caller that
        // kept a reference can change it, and neither can a duplicate(true) sibling that shares
        // this instance through the context holder.
        headers = headers != null ? headers : KafkaRecordHeaders.empty();
    }

    /**
     * Returns the Kafka record context from the current dispatch, or empty if the
     * current call was not Kafka-sourced.
     *
     * @return the Kafka record context, or empty
     */
    public static Optional<KafkaRecordContext> current() {
        return DispatchContext.current(KafkaRecordContext.class);
    }
}
