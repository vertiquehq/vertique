// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
     * Atomically re-validates this runtime is not closed, registers {@code fence}, and invokes
     * {@code action} — all while holding this lifecycle's own monitor, the same one {@link
     * #close()} synchronizes on. This closes the registration&#8594;invocation race a two-step
     * {@code register(fence)} followed by a separate {@code action.get()} call cannot: with two
     * separate steps, a {@link #close()} that becomes observable strictly between them still lets
     * {@code action} run, because {@code register}'s already-returned non-null result carries no
     * live guarantee once the monitor has been released. Here, either this call's critical section
     * runs to completion (including invoking {@code action}) entirely before {@link #close()} can
     * acquire the monitor, or {@link #close()} runs to completion entirely first — in which case
     * this call observes {@code closed} and never invokes {@code action} at all. There is no
     * observable state in between.
     *
     * @param fence force-fails the caller's own guarded promise; idempotent (a {@code tryFail}),
     *     identical contract to {@link #register(Runnable)}'s {@code fence} parameter
     * @param action invoked at most once, synchronously, from inside the held monitor; a
     *     synchronous throw is caught and normalized into a failed future, a {@code null} result is
     *     normalized into a failed future wrapping a {@link NullPointerException}, and either way
     *     the fence is unregistered immediately since there is no real future left to track
     * @param <T> the action's result type
     * @return the result of invoking {@code action} (never {@code null}), or {@code null} when this
     *     runtime is already closed — the caller must then fail immediately without registering or
     *     invoking
     */
    <T> Future<T> registerAndInvoke(Runnable fence, Supplier<Future<T>> action) {
        synchronized (monitor) {
            if (closed) {
                return null;
            }
            activeFences.add(fence);
            Future<T> actionFuture;
            try {
                actionFuture = action.get();
            } catch (Throwable failure) {
                activeFences.remove(fence);
                return Future.failedFuture(failure);
            }
            if (actionFuture == null) {
                activeFences.remove(fence);
                return Future.failedFuture(new NullPointerException("execute action must not return a null Future"));
            }
            return actionFuture;
        }
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
