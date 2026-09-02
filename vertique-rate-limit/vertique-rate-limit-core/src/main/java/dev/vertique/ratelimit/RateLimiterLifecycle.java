// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Shared close/fencing state for one {@link RateLimiters} runtime, owned by that runtime and
 * threaded into every {@link RateLimiter} handle it resolves. Mirrors {@code Resilience}'s
 * idempotent-close idiom (same {@code AtomicReference<Promise<Void>>} pattern): every caller of
 * {@link #close()} observes the exact same terminal future, and every in-flight {@code
 * execute(...)} registered through {@link #registerAndInvoke(Runnable, Supplier)} is force-failed
 * exactly once when this runtime closes, regardless of when its underlying action eventually
 * completes.
 *
 * <p>T021 W2: {@link #registerAndInvoke(Runnable, Supplier)} no longer holds this lifecycle's
 * monitor across {@code action}'s invocation — a third external deep review found that doing so
 * serialized every LOCAL backend admission across <em>every</em> policy this runtime resolves a
 * handle for (one shared {@link RateLimiterLifecycle} per runtime), since {@code
 * LocalBucket4jRateLimitBackend#consume} runs synchronously inside that critical section. Instead,
 * each call gets its own {@link Registration}: registration (closed-check + add) still happens
 * under the monitor, but is released before {@code action} runs; a per-registration CAS ({@code
 * REGISTERED -> INVOKING}) closes the same race the old lock-hold closed — a {@link #close()} that
 * becomes observable strictly between this call's registration and its CAS attempt still wins the
 * CAS race (moving the registration straight to {@code FENCED}), so {@code action} is never invoked
 * once close has claimed this registration. {@link #close()}'s own contract is otherwise unchanged:
 * every currently-registered call is fenced (force-failed) exactly once, whether or not its action
 * ever started.
 */
final class RateLimiterLifecycle {

    /** No-op default for {@link #registerAndInvoke(Runnable, Supplier, Runnable)}'s test seam. */
    private static final Runnable NO_OP_HOOK = () -> {};

    private final Object monitor = new Object();
    private final Map<Runnable, Registration> activeRegistrations = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final AtomicReference<Promise<Void>> closePromise = new AtomicReference<>();

    /**
     * @return {@code true} once {@link #close()} has been called, even while it is still resolving
     */
    boolean isClosed() {
        return closed;
    }

    /** Removes one registration once its guarded action has completed on its own, closed or not. */
    void unregister(Runnable fence) {
        activeRegistrations.remove(fence);
    }

    /**
     * Registers one {@link Registration} for {@code fence} under the monitor (closed-check + add,
     * exactly like the old {@code register(Runnable)} did), releases the monitor, then CASes that
     * registration from {@code REGISTERED} to {@code INVOKING} and only invokes {@code action} on
     * success. A concurrent {@link #close()} that becomes observable strictly between this call's
     * registration and its own CAS attempt still wins: {@link Registration#fenceAndBlockInvocation()}
     * CASes {@code REGISTERED -> FENCED} first, so this call's own {@code REGISTERED -> INVOKING}
     * CAS then fails and {@code action} is never invoked — the fence has already force-failed the
     * caller's guarded promise, so returning {@code null} here (the same "already closed" signal a
     * pre-registration close observes) is correct either way.
     *
     * <p>Once past the CAS, {@code action} runs outside any lock — this is exactly what stops one
     * policy's backend consumption from serializing every other policy's admission behind this
     * runtime's single shared monitor (see the class javadoc).
     *
     * @param fence force-fails the caller's own guarded promise; idempotent (a {@code tryFail})
     * @param action invoked at most once, outside this lifecycle's monitor, only when this call
     *     wins its registration's CAS; a synchronous throw is caught and normalized into a failed
     *     future, a {@code null} result is normalized into a failed future wrapping a {@link
     *     NullPointerException}, and either way the registration is removed immediately since there
     *     is no real future left to track
     * @param <T> the action's result type
     * @return the result of invoking {@code action} (never {@code null}), or {@code null} when this
     *     runtime is already closed (before or during registration) — the caller must then fail
     *     immediately without invoking
     */
    <T> Future<T> registerAndInvoke(Runnable fence, Supplier<Future<T>> action) {
        return registerAndInvoke(fence, action, NO_OP_HOOK);
    }

    /**
     * Test seam only (package-private, exercised directly by {@code
     * RateLimiterLifecycleRegistrationInvocationRaceTest}): identical to {@link
     * #registerAndInvoke(Runnable, Supplier)}, except {@code afterRegisterBeforeCas} runs after
     * registration is released (the exact point the old lock-hold design closed structurally) and
     * before this call's own {@code REGISTERED -> INVOKING} CAS attempt — letting a test
     * deterministically land a concurrent {@link #close()} inside that gap instead of relying on
     * timing.
     */
    <T> Future<T> registerAndInvoke(Runnable fence, Supplier<Future<T>> action, Runnable afterRegisterBeforeCas) {
        Registration registration = new Registration(fence);
        synchronized (monitor) {
            if (closed) {
                return null;
            }
            activeRegistrations.put(fence, registration);
        }
        afterRegisterBeforeCas.run();
        if (!registration.tryStartInvoking()) {
            // A concurrent close() already fenced this registration between our registration and
            // this CAS attempt — it already force-failed the caller's guarded promise. action must
            // never run.
            return null;
        }
        Future<T> actionFuture;
        try {
            actionFuture = action.get();
        } catch (Throwable failure) {
            activeRegistrations.remove(fence);
            return Future.failedFuture(failure);
        }
        if (actionFuture == null) {
            activeRegistrations.remove(fence);
            return Future.failedFuture(new NullPointerException("execute action must not return a null Future"));
        }
        return actionFuture;
    }

    /**
     * Closes this runtime, fencing every currently in-flight registered execution.
     *
     * <p>The operation is idempotent: every caller, concurrent or sequential, receives the exact
     * same terminal future.
     *
     * <p>Every currently-registered {@link Registration} is fenced unconditionally, exactly as
     * before: whether or not its action has started, {@link Registration#fenceAndBlockInvocation()}
     * force-fails its guarded promise. The only behavior change from the pre-T021 W2 design is
     * <em>when</em> a not-yet-started action is prevented from starting — via this registration's own
     * CAS racing {@link #registerAndInvoke(Runnable, Supplier)}'s CAS, not via lock-hold.
     *
     * @return the shared close future, already completed by the time this method returns
     */
    Future<Void> close() {
        Promise<Void> existing = closePromise.get();
        if (existing != null) {
            return existing.future();
        }
        Promise<Void> shutdown = Promise.promise();
        Collection<Registration> registrations;
        synchronized (monitor) {
            existing = closePromise.get();
            if (existing != null) {
                return existing.future();
            }
            closed = true;
            closePromise.set(shutdown);
            registrations = List.copyOf(activeRegistrations.values());
        }
        registrations.forEach(Registration::fenceAndBlockInvocation);
        shutdown.tryComplete();
        return shutdown.future();
    }

    /**
     * One {@link #registerAndInvoke(Runnable, Supplier)} call's registration: a CAS state machine
     * with exactly one winning transition out of {@code REGISTERED} — either the registering call's
     * own {@code -> INVOKING} (permitting its action to run) or {@link #close()}'s {@code ->
     * FENCED} (permanently blocking it). {@code fence} itself is idempotent (a {@code tryFail}), so
     * running it more than once (e.g. defensively) is harmless; this state machine's real job is
     * gating whether {@code action} is ever invoked at all, not de-duplicating the fence.
     */
    private static final class Registration {

        private static final int REGISTERED = 0;
        private static final int INVOKING = 1;
        private static final int FENCED = 2;

        private final Runnable fence;
        private final AtomicInteger state = new AtomicInteger(REGISTERED);

        private Registration(Runnable fence) {
            this.fence = fence;
        }

        /** Called only by the registering {@code registerAndInvoke} call, at most once. */
        boolean tryStartInvoking() {
            return state.compareAndSet(REGISTERED, INVOKING);
        }

        /**
         * Called only by {@link #close()}: best-effort blocks a not-yet-started invocation (CAS
         * {@code REGISTERED -> FENCED}; a no-op if the registering call already won {@code ->
         * INVOKING}, or in the impossible case this were called twice), then always runs the fence.
         */
        void fenceAndBlockInvocation() {
            state.compareAndSet(REGISTERED, FENCED);
            fence.run();
        }
    }
}
