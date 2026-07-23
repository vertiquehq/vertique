// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import dev.vertique.core.async.Combinators;
import io.vertx.core.Future;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The injectable publisher for a single event type {@code T}.
 *
 * <p>A concrete {@code X$Event extends Event<X>} subclass is generated per event type in the
 * inventory by the {@code vertique-codegen-events} processor and bound so that {@code Event<X>} is
 * injectable. Application code injects {@code Event<X>} and calls {@link #fire(Object)} to notify all
 * observers of {@code X} (and of any supertype of {@code X}).
 *
 * <p>Dispatch is <strong>subtype-routed</strong> (observers registered on a supertype receive a fired
 * subtype), <strong>ordered by priority</strong> (lower fires earlier, sequentially), and
 * <strong>never fails due to observer errors</strong>: a synchronous throw from an observer is
 * swallowed-and-logged so it cannot prevent the remaining observers from running, and the returned
 * {@link Future} completes successfully once every matched observer has settled. Firing an event with
 * no matching observers is a no-op that completes immediately. Events are notification-only —
 * {@code fire()} carries no result. A {@code null} event argument is a programming error and is
 * rejected synchronously with a {@link NullPointerException}.
 *
 * @param <T> the event type this publisher fires
 */
public abstract class Event<T> {

    private static final Logger log = LoggerFactory.getLogger(Event.class);

    private final ObserverRegistry registry;
    private final Class<T> type;

    /**
     * Creates a publisher bound to its registry and event type.
     *
     * @param registry the registry used to match observers against the fired type
     * @param type the event type this publisher fires
     */
    protected Event(ObserverRegistry registry, Class<T> type) {
        this.registry = registry;
        this.type = type;
    }

    /**
     * Fires the given event, notifying every matched observer in priority order.
     *
     * <p>Observers are resolved via {@link ObserverRegistry#match(Class)} against the event type,
     * invoked sequentially in ascending-priority order, and any {@link Throwable} thrown by an
     * observer — including {@link Error} and its subclasses — is swallowed and logged so it isolates
     * that observer without failing the fan-out or short-circuiting the remaining observers. The
     * returned future completes once all matched observers have settled and never fails
     * (notification-only).
     *
     * <p>The "never fails" guarantee applies to <em>observer failures</em>. A {@code null}
     * {@code event} argument is a programming error and is rejected synchronously with a
     * {@link NullPointerException} before any observer is invoked.
     *
     * @param event the event instance to deliver to observers; must not be {@code null}
     * @return a future that completes successfully after all matched observers have settled; never
     *     fails
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public Future<Void> fire(T event) {
        Objects.requireNonNull(event, "event must not be null");
        List<ObserverRegistration> matched = registry.match(event.getClass());
        return Combinators.foldSequential(matched, (Void) null, (registration, ignored) -> {
            try {
                registration.invoke().accept(event);
            } catch (Throwable t) {
                // Notification-only: isolate a throwing observer — including Error subclasses —
                // so it cannot stop the fan-out nor fail the returned future.
                // Log the throwable class only, never the payload.
                log.warn(
                        "Observer for event type {} threw {}; swallowed",
                        registration.eventType().getName(),
                        t.getClass().getName());
            }
            return Future.succeededFuture(ignored);
        });
    }

    /**
     * Returns the registry this publisher uses to match observers.
     *
     * @return the observer registry
     */
    protected final ObserverRegistry registry() {
        return registry;
    }

    /**
     * Returns the event type this publisher fires.
     *
     * @return the event type
     */
    protected final Class<T> type() {
        return type;
    }
}
