// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

/**
 * Service for recording outbound side effects in the outbox table within a database transaction.
 *
 * <p>The outbox pattern ensures that side effects (such as publishing events or triggering
 * downstream workflows) are written atomically with the business operation that produces them.
 * The relay then delivers the recorded entries asynchronously, guaranteeing at-least-once
 * delivery without the risk of lost events on application crash or network failure.
 *
 * <p>Example usage within a transactional business operation:
 * <pre>{@code
 * Future<Void> placeOrder(Order order, SqlClient tx) {
 *     return orderRepository.save(order, tx)
 *         .compose(ignored -> outboxService.publish(tx, OutboxEntry.builder()
 *             .eventType("order.placed")
 *             .destinationType(DestinationType.SERVICE)
 *             .destination("inventory/reserve")
 *             .payload(new JsonObject().put("orderId", order.id()))
 *             .build()))
 *         .mapEmpty();
 * }
 * }</pre>
 */
public interface OutboxService {

    /**
     * Records the given outbox entry in the outbox table within the provided transaction.
     *
     * <p>The entry is inserted as {@link OutboxEntryState#PENDING} and will be picked up by
     * the relay after the transaction commits. The insert and the caller's business operation
     * must share the same {@code tx} to guarantee atomicity.
     *
     * @param tx    open database transaction to use for the outbox insert
     * @param entry the outbox entry describing the side effect to be delivered
     * @return a {@link Future} that completes with the surrogate primary key assigned to the
     *         newly inserted outbox entry
     */
    Future<Long> publish(SqlClient tx, OutboxEntry entry);
}
