// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.events;

import dev.vertique.events.Event;
import io.vertx.core.Future;
import jakarta.inject.Inject;

/**
 * A Dagger-injectable bean that fires {@link OrderCreated} events.
 *
 * <p>The {@code @Inject} constructor declares an {@code Event<OrderCreated>} parameter. The
 * {@code vertique-codegen-events} processor discovers this injection site and adds {@code OrderCreated}
 * to the event-type inventory (via the constructor-injection scan), ensuring the generated
 * {@code OrderCreated$Event} publisher is bound even when no {@code @Observes OrderCreated} observer
 * exists.
 */
public class OrderService {

    private final Event<OrderCreated> events;

    /**
     * Creates the service with the injected event publisher.
     *
     * @param events the publisher for {@link OrderCreated} events; never {@code null}
     */
    @Inject
    public OrderService(Event<OrderCreated> events) {
        this.events = events;
    }

    /**
     * Creates a new order and fires an {@link OrderCreated} event to notify observers.
     *
     * @param orderId the identifier for the new order
     * @return a future that completes after all observers have been notified; never fails
     */
    public Future<Void> placeOrder(String orderId) {
        return events.fire(new OrderCreated(orderId));
    }
}
