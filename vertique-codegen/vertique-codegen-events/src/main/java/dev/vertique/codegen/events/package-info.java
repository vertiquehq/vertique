// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compile-time annotation processor for {@code @Observes} typed events.
 *
 * <p>{@link dev.vertique.codegen.events.EventsProcessor} builds an <em>event-type inventory</em> —
 * the union of (distinct {@code @Observes T} parameter types) and (distinct {@code Event<T>}
 * constructor {@code @Inject} parameter type arguments, unwrapping the standard Dagger wrappers
 * {@code Provider<Event<T>>} / {@code Lazy<Event<T>>}). For each inventory type {@code X} it generates
 * a concrete {@code X$Event extends }{@link dev.vertique.events.Event Event}{@code <X>} publisher with
 * an {@code @Inject} constructor {@code (}{@link dev.vertique.events.ObserverRegistry
 * ObserverRegistry}{@code )} and a {@code @Binds }{@code Event<X>} in a generated
 * {@code GeneratedEventsModule}, so {@code Event<X>} is reliably injectable; an event type that is
 * fired but unobserved still gets a valid no-op publisher. Observer methods must return {@code void} —
 * a non-{@code void} return is a compile error.
 */
package dev.vertique.codegen.events;
