// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.events;

import dev.vertique.core.async.Combinators;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.CapturedAuthorityActivatedEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.IdentitySnapshotDegradationEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Fan-out emitter that dispatches a security event to every bound {@link SecurityEventObserver}.
 *
 * <p>Observers are invoked in parallel via the
 * {@link Combinators#joinAllSwallow(List, Function, java.util.function.BiConsumer)} kernel, which
 * waits for all observer futures to settle regardless of individual outcomes and always succeeds.
 * Per-observer failures — both synchronous exceptions thrown from the observer method and
 * asynchronous {@link Future} failures — are caught and logged by the per-observer {@link #safe}
 * wrapper so that one misbehaving observer cannot:
 * <ul>
 *   <li>prevent other observers from receiving the event (AC-SE-6)</li>
 *   <li>alter the authentication or authorization result that produced the event (AC-SE-6)</li>
 * </ul>
 *
 * <p>Observer invocation ordering is not guaranteed. Observers must be idempotent and self-ordered
 * if ordering within a downstream pipeline matters.
 *
 * <p>Long-running or blocking work must be offloaded inside the observer (e.g. via
 * {@code vertx.executeBlocking(...)}) — the emitter does not force-offload observer work (AC-SE-7).
 *
 * <p>The emitter is transport-neutral and lives in {@code vertique-security-runtime} so any
 * enforcement layer (REST, WebSocket, services, jobs) can emit security events. It is wired to the
 * Dagger graph via the {@code Set<SecurityEventObserver>} multibinding declared in
 * {@link SecurityEventsModule}. Applications and surfaces contribute observers via
 * {@code @Provides @IntoSet}.
 *
 * @see SecurityEventObserver
 * @see SecurityEventsModule
 */
@Slf4j
@Singleton
public final class SecurityEventEmitter {

    private final List<SecurityEventObserver> observers;

    /**
     * Creates a new emitter with the registered observer set.
     *
     * @param observers the set of security event observers to fan out to; must not be {@code null}
     */
    @Inject
    public SecurityEventEmitter(Set<SecurityEventObserver> observers) {
        this.observers = List.copyOf(Objects.requireNonNull(observers, "observers"));
    }

    // --- Emit overloads ---

    /**
     * Fans out a {@link CredentialAcceptedEvent} to all registered observers.
     *
     * <p>Each observer's {@link SecurityEventObserver#onCredentialAccepted} is invoked;
     * failures are isolated per-observer.
     *
     * @param event the accepted-credential event; must not be {@code null}
     * @return a {@link Future} that succeeds when all observers have completed or their failures
     *         have been recovered; never fails
     */
    public Future<Void> emit(CredentialAcceptedEvent event) {
        return fanOut(o -> safe(() -> o.onCredentialAccepted(event), o, "onCredentialAccepted"));
    }

    /**
     * Fans out a {@link CredentialRejectedEvent} to all registered observers.
     *
     * <p>Each observer's {@link SecurityEventObserver#onCredentialRejected} is invoked;
     * failures are isolated per-observer.
     *
     * @param event the rejected-credential event; must not be {@code null}
     * @return a {@link Future} that succeeds when all observers have completed or their failures
     *         have been recovered; never fails
     */
    public Future<Void> emit(CredentialRejectedEvent event) {
        return fanOut(o -> safe(() -> o.onCredentialRejected(event), o, "onCredentialRejected"));
    }

    /**
     * Fans out an {@link AuthorizationDecisionEvent} to all registered observers.
     *
     * <p>Each observer's {@link SecurityEventObserver#onAuthorizationDecided} is invoked;
     * failures are isolated per-observer.
     *
     * @param event the authorization-decision event; must not be {@code null}
     * @return a {@link Future} that succeeds when all observers have completed or their failures
     *         have been recovered; never fails
     */
    public Future<Void> emit(AuthorizationDecisionEvent event) {
        return fanOut(o -> safe(() -> o.onAuthorizationDecided(event), o, "onAuthorizationDecided"));
    }

    /**
     * Fans out a {@link ChannelLifecycleEvent} to all registered observers.
     *
     * <p>Each observer's {@link SecurityEventObserver#onChannelLifecycle} is invoked;
     * failures are isolated per-observer.
     *
     * @param event the channel-lifecycle event; must not be {@code null}
     * @return a {@link Future} that succeeds when all observers have completed or their failures
     *         have been recovered; never fails
     */
    public Future<Void> emit(ChannelLifecycleEvent event) {
        return fanOut(o -> safe(() -> o.onChannelLifecycle(event), o, "onChannelLifecycle"));
    }

    /**
     * Fans out an {@link IdentitySnapshotDegradationEvent} to all registered observers.
     *
     * <p>Each observer's {@link SecurityEventObserver#onIdentitySnapshotDegradation} is invoked;
     * failures are isolated per-observer.
     *
     * @param event the identity-snapshot-degradation event; must not be {@code null}
     * @return a {@link Future} that succeeds when all observers have completed or their failures
     *         have been recovered; never fails
     */
    public Future<Void> emit(IdentitySnapshotDegradationEvent event) {
        return fanOut(o -> safe(() -> o.onIdentitySnapshotDegradation(event), o, "onIdentitySnapshotDegradation"));
    }

    /**
     * Fans out a {@link CapturedAuthorityActivatedEvent} to all registered observers.
     *
     * <p>Each observer's {@link SecurityEventObserver#onCapturedAuthorityActivated} is invoked;
     * failures are isolated per-observer. The returned future resolving is what makes the
     * activation seam's emit-and-await guarantee possible: it completes only once every observer
     * has settled (successfully or via isolated failure).
     *
     * @param event the captured-authority-activated event; must not be {@code null}
     * @return a {@link Future} that succeeds when all observers have completed or their failures
     *         have been recovered; never fails
     */
    public Future<Void> emit(CapturedAuthorityActivatedEvent event) {
        return fanOut(o -> safe(() -> o.onCapturedAuthorityActivated(event), o, "onCapturedAuthorityActivated"));
    }

    // --- Fan-out and isolation helpers ---

    /**
     * Invokes {@code invoker} for every observer and joins on all results via the
     * {@link Combinators#joinAllSwallow(List, Function, java.util.function.BiConsumer)} kernel.
     *
     * <p>{@code joinAllSwallow} launches every hook, waits for all of them to settle (never
     * short-circuiting on an early failure), and always succeeds. The {@code invoker} passed in by
     * each {@code emit} overload is already the per-observer {@link #safe} wrapper, so every hook
     * handed to the kernel returns a succeeded future — the kernel's {@code onFailure} channel is
     * therefore unreachable here (kept as the kernel's isolation net). The observer {@link Set} is
     * passed as an unordered list: iteration order is intentionally not imposed.
     *
     * @param invoker a function that, given an observer, returns the safe future for that observer
     * @return a succeeded future once all observer futures have resolved
     */
    private Future<Void> fanOut(Function<SecurityEventObserver, Future<Void>> invoker) {
        if (observers.isEmpty()) {
            return Future.succeededFuture();
        }
        // Pass the Set as an unordered List (no sorting) so the kernel preserves the existing
        // unspecified observer iteration order.
        return Combinators.joinAllSwallow(
                observers,
                // safe() (built into invoker by each emit overload) always returns a succeeded
                // future, so the hook never fails.
                invoker::apply,
                // Unreachable: safe() always returns a succeeded future, so onFailure never fires;
                // kept as the kernel's isolation net.
                (observer, cause) -> {});
    }

    /**
     * Wraps a single observer invocation to isolate both synchronous and asynchronous failures.
     *
     * <p>If the observer throws synchronously, the exception is caught and logged and
     * {@link Future#succeededFuture()} is returned. The catch deliberately widens to
     * {@link Exception} (not just {@link RuntimeException}) so that a sneak-thrown <em>checked</em>
     * exception is routed through this same logging-and-isolation path: it can never abort the emit,
     * and the failure is always logged. This widens the documented observer-isolation contract to
     * cover sneak-thrown checked exceptions, and is what makes the {@link #fanOut} kernel's no-op
     * {@code onFailure} channel genuinely unreachable — every synchronous throw is converted here
     * into a succeeded future before it can reach the kernel. If the observer returns {@code null}
     * it is treated as a success. If the returned future fails asynchronously, the failure is
     * recovered and logged. The returned future therefore never fails.
     *
     * @param invocation a {@link Supplier} that calls the observer method and returns its future
     * @param observer   the observer instance (used only for log messages)
     * @param method     the observer method name (used only for log messages)
     * @return a future that always succeeds, regardless of observer behaviour
     */
    private Future<Void> safe(Supplier<Future<Void>> invocation, SecurityEventObserver observer, String method) {
        Future<Void> result;
        try {
            result = invocation.get();
            if (result == null) {
                log.warn(
                        "Security observer {} returned null Future from {}; treating as success",
                        observer.getClass().getName(),
                        method);
                return Future.succeededFuture();
            }
        } catch (Exception synchronousFailure) {
            // Catch Exception (not just RuntimeException) so a sneak-thrown checked exception is
            // also logged-and-isolated here rather than escaping into the kernel's no-op onFailure.
            log.warn(
                    "Security observer {} threw from {}: {}",
                    observer.getClass().getName(),
                    method,
                    synchronousFailure.toString());
            return Future.succeededFuture();
        }
        return result.recover(t -> {
            log.warn(
                    "Security observer {} failed asynchronously in {}: {}",
                    observer.getClass().getName(),
                    method,
                    t.toString());
            return Future.succeededFuture();
        });
    }
}
