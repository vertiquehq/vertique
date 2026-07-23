// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import dev.vertique.core.util.TypeResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Indexes the application's observer registrations and matches them against a fired event type.
 *
 * <p>The registry is constructed from the Dagger-aggregated {@code Set<ObserverRegistration>} —
 * every observer declared in any included module contributes one {@link ObserverRegistration} via
 * an {@code @IntoSet} multibinding (see {@link ObserverRegistration}). At dispatch time,
 * {@link #match(Class)} returns the registrations whose declared event type is the fired type or a
 * supertype of it (walking both the superclass chain and all interfaces), sorted by ascending
 * priority so the {@link Event} publisher can fan out in deterministic order. Subtype routing is a
 * registry concern: an observer of a supertype receives a fired subtype.
 */
@Singleton
public final class ObserverRegistry {

    private final Set<ObserverRegistration> registrations;

    /**
     * Creates a registry over the multibound set of observer registrations.
     *
     * @param registrations the Dagger-aggregated observer registrations from every included module
     */
    @Inject
    public ObserverRegistry(Set<ObserverRegistration> registrations) {
        this.registrations = registrations;
    }

    /**
     * Returns the registrations whose event type is the fired type or one of its supertypes.
     *
     * <p>A registration matches when its {@link ObserverRegistration#eventType()} is assignable from
     * {@code firedType} — that is, the fired type is the registered type, a subclass of it, or an
     * implementation of it (the superclass chain <em>and</em> all transitively reachable interfaces
     * are considered). Matches are returned sorted by ascending {@link ObserverRegistration#priority()}
     * so observers fire lower-priority-first. <strong>The relative order of observers with equal
     * priority is unspecified</strong> and depends on the iteration order of the injected
     * {@link Set}; callers that require a strict ordering must assign distinct priority values to
     * their observers.
     *
     * @param firedType the runtime type of the fired event
     * @return the matching registrations, ordered by ascending priority (never {@code null})
     */
    public List<ObserverRegistration> match(Class<?> firedType) {
        Set<Class<?>> supertypes = supertypesOf(firedType);
        return registrations.stream()
                .filter(reg -> supertypes.contains(reg.eventType()))
                .distinct()
                .sorted(Comparator.comparingInt(ObserverRegistration::priority))
                .toList();
    }

    /**
     * Builds the set of types {@code firedType} matches against — the type itself, every superclass
     * up the chain, and every transitively reachable interface.
     *
     * @param firedType the runtime type of the fired event
     * @return the fired type plus all its supertypes (superclass chain and interfaces)
     */
    private static Set<Class<?>> supertypesOf(Class<?> firedType) {
        Set<Class<?>> supertypes = new HashSet<>(TypeResolver.getAllInterfaces(firedType));
        for (Class<?> c = firedType; c != null && c != Object.class; c = c.getSuperclass()) {
            supertypes.add(c);
        }
        return supertypes;
    }
}
