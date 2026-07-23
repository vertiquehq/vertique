// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import dev.vertique.services.dispatch.DispatchContext;
import java.util.Map;
import java.util.Optional;

/**
 * Kafka record metadata accessible on the service handler side during dispatch.
 *
 * <p>Propagated through the event bus via {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext()}
 * and stored in the dispatch-scoped {@link DispatchContext}. Handler methods can declare this
 * type as a parameter for auto-injection, or use {@link #current()} in direct implementations.
 *
 * @param consumerName the Kafka consumer binding name
 * @param topic the source topic
 * @param partition the source partition
 * @param offset the message offset
 * @param key the message key, or {@code null}
 * @param timestamp the message timestamp (epoch milliseconds)
 * @param correlationId the correlation ID (from header or auto-generated)
 * @param headers all Kafka headers
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
        Map<String, String> headers)
        implements ContextValue {

    /**
     * Defensive copy of headers in compact constructor.
     *
     * @param consumerName the Kafka consumer binding name
     * @param topic the source topic
     * @param partition the source partition
     * @param offset the message offset
     * @param key the message key, or {@code null}
     * @param timestamp the message timestamp (epoch milliseconds)
     * @param correlationId the correlation ID (from header or auto-generated)
     * @param headers all Kafka headers
     */
    public KafkaRecordContext {
        // Defensive copy via Map.copyOf, not Collections.unmodifiableMap: the latter is a view
        // that still tracks the caller's map, so a caller that retained a reference could mutate
        // it and the mutation would surface on this record (and on any duplicate(true) sibling
        // sharing this instance through the context-holder). Map.copyOf snapshots the entries.
        headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    /**
     * Returns a single header value by name.
     *
     * @param name the header name
     * @return the header value, or empty if not present
     */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
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
