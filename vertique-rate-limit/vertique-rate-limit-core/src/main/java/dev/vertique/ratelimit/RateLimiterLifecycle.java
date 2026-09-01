// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared close/fencing state for one {@link RateLimiters} runtime, owned by that runtime and
 * threaded into every {@link RateLimiter} handle it resolves. Mirrors {@code Resilience}'s
 * idempotent-close idiom (same {@code AtomicReference<Promise<Void>>} pattern): every caller of
 * {@link #close()} observes the exact same terminal future, and every in-flight {@code
 * execute(...)} registered through {@link #register(Runnable)} is force-failed exactly once when
 * this runtime closes, regardless of when its underlying action eventually completes.
 */
final class RateLimiterLifecycle {

    private final Object monitor = new Object();
    private final Set<Runnable> activeFences = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final AtomicReference<Promise<Void>> closePromise = new AtomicReference<>();

    /**
     * @return {@code true} once {@link #close()} has been called, even while it is still resolving
     */
    boolean isClosed() {
        return closed;
    }

    /**
     * Registers one in-flight {@code execute(...)} fence so {@link #close()} can force-fail it.
     *
     * @param fence force-fails the caller's own guarded promise; idempotent (a {@code tryFail})
     * @return {@code fence} itself, or {@code null} when this runtime is already closed — the
     *     caller must then fail immediately without registering
     */
    Runnable register(Runnable fence) {
        synchronized (monitor) {
            if (closed) {
                return null;
            }
            activeFences.add(fence);
        }
        return fence;
    }

    /** Removes one fence once its guarded action has completed on its own, closed or not. */
    void unregister(Runnable fence) {
        activeFences.remove(fence);
    }

    /**
     * Closes this runtime, fencing every currently in-flight registered execution.
     *
     * <p>The operation is idempotent: every caller, concurrent or sequential, receives the exact
     * same terminal future.
     *
     * @return the shared close future, already completed by the time this method returns
     */
    Future<Void> close() {
        Promise<Void> existing = closePromise.get();
        if (existing != null) {
            return existing.future();
        }
        Promise<Void> shutdown = Promise.promise();
        Set<Runnable> fences;
        synchronized (monitor) {
            existing = closePromise.get();
            if (existing != null) {
                return existing.future();
            }
            closed = true;
            closePromise.set(shutdown);
            fences = Set.copyOf(activeFences);
        }
        fences.forEach(Runnable::run);
        shutdown.tryComplete();
        return shutdown.future();
    }
}
