// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Handle that cancels one pipeline execution from any thread.
 *
 * <p>Pass it to {@link ResiliencePipeline#execute(java.util.function.Supplier, ResilienceCancellation)}.
 * Cancelling ends the execution's own waiting states: a pending retry delay stops, a call queued in a
 * bulkhead leaves the queue, no further operation invocation starts and the execution's future fails
 * once, on the execution context, with the given reason. An operation future that is already in flight
 * is not cancelled and its late result is ignored; the owner of the operation resets its own work.
 *
 * <p>A cancelled execution never closes a circuit breaker. It counts as a failure when it was the
 * half-open trial, so the breaker re-opens, and as a success when the breaker is closed, which like any
 * success resets the consecutive-failure count. It is reported as an execution that completed with an
 * application failure.
 *
 * <p>The first {@link #cancel(Throwable)} wins; later calls do nothing. An instance is bound to the
 * executions it is passed to and is not reusable after it has been cancelled.
 */
public final class ResilienceCancellation {

    private final AtomicReference<Throwable> reason = new AtomicReference<>();
    private final CopyOnWriteArrayList<Consumer<Throwable>> listeners = new CopyOnWriteArrayList<>();

    private ResilienceCancellation() {}

    /**
     * Creates a handle that is not cancelled.
     *
     * @return a new cancellation, never null
     */
    public static ResilienceCancellation create() {
        return new ResilienceCancellation();
    }

    /**
     * Cancels the executions this handle was passed to. The first call wins and later calls do nothing.
     * May be called from any thread.
     *
     * @param reason the failure the cancelled executions complete with
     * @throws NullPointerException if {@code reason} is null
     */
    public void cancel(Throwable reason) {
        Objects.requireNonNull(reason, "reason");
        if (!this.reason.compareAndSet(null, reason)) {
            return;
        }
        // Each listener runs once: only the call that removes it runs it, never an unregistered one
        for (Consumer<Throwable> listener : List.copyOf(listeners)) {
            if (listeners.remove(listener)) {
                listener.accept(reason);
            }
        }
    }

    /**
     * Returns whether {@link #cancel(Throwable)} has been called.
     *
     * @return true after the first cancel
     */
    public boolean isCancelled() {
        return reason.get() != null;
    }

    Throwable reason() {
        return reason.get();
    }

    /**
     * Registers a one-shot listener that runs exactly once with the reason: immediately when already
     * cancelled, otherwise from the cancelling thread. Returns a handle that unregisters it.
     */
    Runnable onCancel(Consumer<Throwable> listener) {
        listeners.add(listener);
        Throwable current = reason.get();
        if (current != null && listeners.remove(listener)) {
            listener.accept(current);
        }
        return () -> listeners.remove(listener);
    }
}
