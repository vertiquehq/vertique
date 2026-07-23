// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.security;

import dev.vertique.security.events.AuthorizationDecisionEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-observable sink shared across the example-services Dagger graph that records authorization
 * outcomes and guarded-handler invocations.
 *
 * <p>This collector exists so an integration test can prove, through the <em>real</em>
 * Dagger-wired services dispatch pipeline, that the {@code @RequiresAction} policy enforcement
 * point (the {@code @IntoSet}-delivered {@code ServiceAuthorizationInterceptor} running inside a
 * deployed {@code ServiceVerticle}'s {@code ServiceMethodInvoker}) actually:
 * <ol>
 *   <li>lets a permitted actor reach the handler and emits exactly one permit
 *       {@link AuthorizationDecisionEvent};</li>
 *   <li>short-circuits a denied actor before the handler runs and emits exactly one deny event;</li>
 *   <li>fails closed when no caller identity is bound and emits exactly one deny event.</li>
 * </ol>
 *
 * <p>The collector is registered as a {@code @Singleton} so the single instance is shared between
 * the capturing {@code SecurityEventObserver} (which records emitted decision events) and the
 * guarded handler (which records that it executed). Both surfaces are exercised on the Vert.x event
 * loop during dispatch; the backing collections are concurrency-safe so a test thread may read them
 * after the dispatch reply settles.
 *
 * <p>This is example/proof wiring, not part of the framework's production surface.
 */
@Singleton
public final class AuthzEventCollector {

    private final List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger handlerInvocations = new AtomicInteger(0);

    /** Creates an empty collector. */
    @Inject
    public AuthzEventCollector() {}

    /**
     * Records an authorization decision event emitted by the enforcement point.
     *
     * @param event the decision event to record; must not be {@code null}
     */
    public void recordEvent(AuthorizationDecisionEvent event) {
        events.add(event);
    }

    /**
     * Records that a guarded handler method executed. Called from the handler body so a test can
     * prove the handler did (permit) or did not (deny / fail-closed) run.
     */
    public void recordHandlerInvocation() {
        handlerInvocations.incrementAndGet();
    }

    /**
     * Returns an immutable snapshot of all recorded authorization decision events, in arrival order.
     *
     * @return the recorded decision events; never {@code null}
     */
    public List<AuthorizationDecisionEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the number of times a guarded handler method has executed.
     *
     * @return the guarded-handler invocation count
     */
    public int handlerInvocations() {
        return handlerInvocations.get();
    }

    /**
     * Clears all recorded events and resets the handler-invocation counter. Allows a test to isolate
     * per-scenario assertions when several dispatches share one deployed application.
     */
    public void reset() {
        events.clear();
        handlerInvocations.set(0);
    }
}
