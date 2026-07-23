// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.events;

/**
 * A simple domain event representing an order that has been created.
 *
 * <p>This is the event type for the end-to-end events example. {@link OrderObserver} observes it via
 * {@code @Observes OrderCreated} and {@link OrderService} fires it via {@code Event<OrderCreated>}.
 * The {@code vertique-codegen-events} processor discovers {@code OrderCreated} from both the
 * {@code @Observes} scan and the {@code Event<T>} injection-site scan, and generates:
 * <ul>
 *   <li>an {@code OrderCreated$Event extends Event<OrderCreated>} publisher, and
 *   <li>a {@code GeneratedEventsModule} with a {@code @Binds Event<OrderCreated>} and a
 *       {@code @Provides @IntoSet ObserverRegistration} for every {@code @Observes OrderCreated}
 *       observer method.
 * </ul>
 *
 * @param orderId the identifier of the created order
 */
public record OrderCreated(String orderId) {}
