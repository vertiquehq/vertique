// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as the event an observer method observes.
 *
 * <p>A method whose parameter carries {@code @Observes T} is an observer of event type {@code T}: it
 * is invoked (synchronously, returning {@code void}) when a matching {@code T}-or-subtype event is
 * fired. The processor reads the annotated parameter's type to build the event-type inventory and to
 * generate the observer registration; the annotation itself is {@link RetentionPolicy#SOURCE
 * SOURCE}-retained, so it does not exist at runtime — cross-module aggregation is achieved through a
 * Dagger {@code @IntoSet} multibinding of {@link ObserverRegistration} instead.
 *
 * <p>{@link #priority()} orders observers of the same fired event relative to one another: a
 * <em>lower</em> priority value fires <em>earlier</em>.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Observes {

    /**
     * The observer's dispatch priority; lower values fire earlier. Observers are dispatched in
     * ascending priority order. <strong>The relative order of observers with equal priority is
     * unspecified</strong>; assign distinct values when a strict ordering is required.
     *
     * @return the priority of this observer in the fan-out for its event type (default {@code 1000})
     */
    int priority() default 1000;
}
