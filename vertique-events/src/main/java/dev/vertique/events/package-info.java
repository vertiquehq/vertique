// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique compile-time {@code @Observes} typed-event runtime.
 *
 * <p>This module defines the small runtime contract that the {@code vertique-codegen-events}
 * processor generates against to deliver typed, subtype-routed events to observer methods on
 * arbitrary application beans. The SPI is deliberately tiny:
 * <ul>
 *   <li>{@link dev.vertique.events.Observes} — the {@code SOURCE}-retained parameter annotation that
 *       marks a method parameter as the observed event type and declares the observer's
 *       {@code priority()} (lower fires earlier).</li>
 *   <li>{@link dev.vertique.events.ObserverRegistration} — a runtime-retained registration record
 *       (event type + priority + a reflection-free {@code Consumer<Object>} invoker) emitted as a
 *       {@code @Provides @IntoSet} binding by each module's generated events module, so observers
 *       aggregate across modules via Dagger multibinding.</li>
 *   <li>{@link dev.vertique.events.ObserverRegistry} — built from the injected set of registrations;
 *       its {@code match(firedType)} returns the registrations whose event type is the fired type or
 *       a supertype (superclass chain <em>and</em> interfaces), ordered by priority.</li>
 *   <li>{@link dev.vertique.events.Event} — the publisher whose {@code fire(event)} dispatches the
 *       matched observers in priority order, sequentially, swallowing-and-logging any observer throw
 *       so the returned {@link io.vertx.core.Future} completes after all observers settle and
 *       <em>never</em> fails (notification-only).</li>
 * </ul>
 *
 * <p>Observer methods are synchronous and return {@code void}; a throw from one observer is isolated
 * and does not prevent the remaining observers from running or fail {@code fire()}. The dispatch
 * reuses {@link dev.vertique.core.async.Combinators#foldSequential} and the routing walk relies on
 * {@link dev.vertique.core.util.TypeResolver#getAllInterfaces} plus a superclass walk.
 */
package dev.vertique.events;
