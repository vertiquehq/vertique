// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.events;

import dev.vertique.events.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Dagger-injectable bean that observes {@link OrderCreated} events.
 *
 * <p>The {@code vertique-codegen-events} processor discovers the {@link #onOrder} method via its
 * {@code @Observes OrderCreated} parameter and generates a {@code @Provides @IntoSet
 * ObserverRegistration} in {@code GeneratedEventsModule} that:
 * <ul>
 *   <li>is registered for event type {@code OrderCreated.class},
 *   <li>carries {@code priority = 1000} (the {@link Observes} default), and
 *   <li>wraps a direct, reflection-free lambda: {@code e -> onOrder((OrderCreated) e)} bound to
 *       the Dagger-injected {@code OrderObserver} instance.
 * </ul>
 *
 * <p>The test reads {@link #lastReceived()} after firing to assert the observer was invoked.
 *
 * <p>{@code @Singleton} is required: without it, Dagger creates a separate {@code OrderObserver}
 * instance for the {@code @Provides @IntoSet ObserverRegistration} provider and for
 * {@link dev.vertique.examples.events.OrderComponent#orderObserver()}, so mutations in the
 * registration lambda are invisible to the component accessor.
 */
@Singleton
public class OrderObserver {

    private final AtomicReference<OrderCreated> lastReceived = new AtomicReference<>();

    /**
     * Creates the observer. The {@code @Inject} constructor satisfies the v1 binding-origin
     * requirement (exactly one {@code @Inject} constructor) so Dagger can inject this bean.
     */
    @Inject
    public OrderObserver() {}

    /**
     * Observes an {@link OrderCreated} event. Records the received event in {@link #lastReceived}
     * so the test can verify the observer was invoked.
     *
     * @param event the created order event delivered by the events runtime
     */
    public void onOrder(@Observes OrderCreated event) {
        lastReceived.set(event);
    }

    /**
     * Returns the last {@link OrderCreated} event this observer received, or {@code null} if no
     * event has been fired yet.
     *
     * @return the last received event, or {@code null}
     */
    public OrderCreated lastReceived() {
        return lastReceived.get();
    }
}
