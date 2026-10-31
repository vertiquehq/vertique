// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import dev.vertique.core.scope.ScopeKey;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Dispatch context value injected into service handler parameters when a message is delivered
 * via the transactional messaging relay.
 *
 * <p>Types annotated with {@link DispatchContextValue} are recognized as injectable by the
 * service dispatch framework. The relay populates this record in the dispatch context so that
 * handlers can access message metadata without coupling to transport-level details.
 *
 * <p>Handler methods can declare this type as a parameter:
 * <pre>{@code
 * public Future<Void> handleOrderPlaced(OrderPlacedEvent event, TransactionalMessageContext ctx) {
 *     log.info("Processing message {} for aggregate {}/{}", ctx.messageId(), ctx.aggregateType(), ctx.aggregateId());
 *     // ...
 * }
 * }</pre>
 *
 * @param messageId     surrogate primary key of the originating outbox entry
 * @param eventType     logical event type name (e.g., {@code "order.placed"})
 * @param aggregateType optional domain aggregate type that produced the event
 * @param aggregateId   optional identifier of the specific aggregate instance
 * @param headers       key-value metadata passed alongside the event payload
 * @param scopeKey      the scope the originating entry was published in, read from the stored row;
 *                      {@code null} is read as {@link ScopeKey#DEFAULT}, so the accessor never returns
 *                      {@code null}. It is a label, not authorization, and is independent of the
 *                      ambient {@code ScopeContext}
 */
@DispatchContextValue
public record TransactionalMessageContext(
        long messageId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Map<String, String> headers,
        @Nullable String scopeKey)
        implements ContextValue {

    /**
     * Creates a context in the default scope.
     *
     * <p>This is the constructor shape that predates {@code scopeKey}; it passes {@code null}, which
     * the canonical constructor reads as {@link ScopeKey#DEFAULT}.
     *
     * @param messageId     surrogate primary key of the originating outbox entry
     * @param eventType     logical event type name
     * @param aggregateType optional domain aggregate type that produced the event
     * @param aggregateId   optional identifier of the specific aggregate instance
     * @param headers       key-value metadata; {@code null} means none
     */
    public TransactionalMessageContext(
            long messageId, String eventType, String aggregateType, String aggregateId, Map<String, String> headers) {
        this(messageId, eventType, aggregateType, aggregateId, headers, null);
    }

    /**
     * Compact constructor — defensively copies the caller's {@code headers} map via
     * {@link Map#copyOf} so the record's invariant (immutable component values) holds even if the
     * caller retains a reference and mutates the original map. {@code null} headers are normalised
     * to {@link Map#of()} so callers never have to guard for {@code null}. A {@code null}
     * {@code scopeKey} is mapped to {@link ScopeKey#DEFAULT}.
     *
     * @param headers  caller-supplied headers; may be {@code null} (treated as empty)
     * @param scopeKey the scope label; may be {@code null} (treated as the default scope)
     */
    public TransactionalMessageContext {
        headers = headers != null ? Map.copyOf(headers) : Map.of();
        scopeKey = scopeKey != null ? scopeKey : ScopeKey.DEFAULT.value();
    }
}
