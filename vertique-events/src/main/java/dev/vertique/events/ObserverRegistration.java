// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import java.util.function.Consumer;

/**
 * A runtime-retained registration of a single observer method.
 *
 * <p>Because {@link Observes} is {@code SOURCE}-retained and therefore invisible across module
 * boundaries at runtime, observer aggregation is performed through Dagger multibinding: each module's
 * generated events module emits one {@code @Provides @IntoSet ObserverRegistration} per observer, and
 * the {@link ObserverRegistry} is built from the injected {@code Set<ObserverRegistration>}. The
 * {@link #invoke} consumer is a direct, reflection-free lambda bound to the observer method on its
 * bean — observers are synchronous and return {@code void}, hence a plain {@link Consumer}.
 *
 * @param eventType the declared event type this observer is registered for (matched against the fired
 *     type or any of its subtypes)
 * @param priority the dispatch priority; lower values fire earlier
 * @param invoke the reflection-free invoker that delivers the fired event to the observer method
 */
public record ObserverRegistration(Class<?> eventType, int priority, Consumer<Object> invoke) {}
