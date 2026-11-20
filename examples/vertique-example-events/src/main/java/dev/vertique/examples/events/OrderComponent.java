// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.events;

import dagger.Component;
import jakarta.inject.Singleton;

/**
 * Dagger component proving the {@code vertique-codegen-events} processor's multi-module observer
 * aggregation.
 *
 * <p>This component installs the <em>generated</em> {@code GeneratedEventsModule} — whose
 * {@code @Binds Event<OrderCreated>} makes the publisher injectable and whose
 * {@code @Provides @IntoSet ObserverRegistration} contributions feed the {@link
 * dev.vertique.events.ObserverRegistry} multibinding. When the Dagger graph is assembled, the
 * {@link dev.vertique.events.ObserverRegistry} receives the full {@code Set<ObserverRegistration>}
 * from the multibinding, and firing an event via {@link OrderService} invokes all matched observers.
 */
@Singleton
@Component(modules = {GeneratedEventsModule.class})
public interface OrderComponent {

    /**
     * Returns the {@link OrderService} — which injects {@code Event<OrderCreated>} and fires events.
     *
     * @return the order service; never {@code null}
     */
    OrderService orderService();

    /**
     * Returns the {@link OrderObserver} — which observes {@link OrderCreated} events and records the
     * last received event for test assertions.
     *
     * @return the observer; never {@code null}
     */
    OrderObserver orderObserver();
}
