// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.logging.MDCContexts;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * ROOT-scoped middleware that owns the per-request {@link ContextHolder.Scope} lifecycle.
 *
 * <p>This middleware runs first (order = {@link #ORDER} = {@code Integer.MIN_VALUE}) and registers
 * exactly one {@code ctx.addEndHandler} per request. Because Vert.x Web 5.1.2 fires end handlers
 * in reverse registration order, this middleware's end handler fires <em>last</em> — after audit,
 * logging finalization, and every other end handler — so holder-bound values remain accessible to
 * downstream end handlers until the very end of the request.
 *
 * <p>Other middlewares that need to release per-request resources should obtain the {@link Handle}
 * from this middleware via {@link #fromRoutingContext(RoutingContext)} and register their cleanup
 * there. No middleware should call {@code ctx.addEndHandler} for context cleanup directly.
 *
 * <p>Contributes:
 * <ul>
 *   <li>{@link Handle#onClose(ContextHolder.Scope)} — registers a {@link ContextHolder.Scope} that
 *       is closed in LIFO order during cleanup.
 *   <li>{@link Handle#onCloseRun(Runnable)} — registers a {@link Runnable} cleanup in LIFO order;
 *       the entry point to use for a lambda or method reference.
 *   <li>{@link Handle#onClose(Runnable)} — as above, for a value already declared {@link Runnable}.
 *   <li>{@link Handle#afterClose(Runnable)} — registers a {@link Runnable} that runs after all
 *       {@code onClose} registrations complete, in FIFO order.
 *   <li>{@link Handle#completeNow()} — idempotent explicit completion, required by the WebSocket
 *       upgrade path where Vert.x's {@code completeHandshake()} bypasses the response end handler.
 * </ul>
 *
 * <p>Late registration (calling {@code onClose} or {@code afterClose} after {@code completeNow()}
 * or the end handler fires) throws {@link IllegalStateException} immediately so leaks are loud.
 */
@Slf4j
@Singleton
public final class RequestContextLifecycle implements Middleware {

    /**
     * Execution order constant. {@code Integer.MIN_VALUE} guarantees this middleware sorts ahead
     * of every other root middleware in the {@link HttpVerticle} ordering pass.
     */
    public static final int ORDER = Integer.MIN_VALUE;

    /** Routing context key used to store the per-request {@link Handle}. */
    private static final String KEY = "dev.vertique.requestContextLifecycle";

    /** Constructs the middleware. Intended for Dagger constructor injection. */
    @Inject
    public RequestContextLifecycle() {}

    /**
     * Returns {@link ExtensionPhase#SYSTEM_FIRST} — the per-request context lifecycle must
     * initialize before ALL application middleware. {@code SYSTEM_FIRST} enforces this independent
     * of priority, preserving the documented "ahead of every other root middleware" invariant.
     *
     * @return {@link ExtensionPhase#SYSTEM_FIRST}
     */
    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@link #ORDER} ({@code Integer.MIN_VALUE})
     */
    @Override
    public int priority() {
        return ORDER;
    }

    /** {@inheritDoc} Returns {@link MiddlewareScope#ROOT} — runs for every request. */
    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    /**
     * Creates a fresh {@link Handle}, stores it in the routing context, registers the end handler
     * that drives cleanup, and delegates to the next handler.
     *
     * @param ctx the current routing context; must not be {@code null}
     */
    @Override
    public void handle(RoutingContext ctx) {
        Handle handle = new Handle();
        ctx.put(KEY, handle);
        // Register first → fires last under Vert.x Web 5.1.2 reverse end-handler order.
        // This ensures all downstream end handlers (audit emit, log finalization) still see
        // holder-bound context values when they run.
        ctx.addEndHandler(v -> handle.closeAll());
        ctx.next();
    }

    /**
     * Retrieves the {@link Handle} that was stored in the routing context by this middleware.
     *
     * @param ctx the current routing context; must not be {@code null}
     * @return the per-request handle
     * @throws IllegalStateException if this middleware has not run for the given context (i.e. the
     *     handle is absent — typically a misconfiguration)
     */
    public static Handle fromRoutingContext(RoutingContext ctx) {
        Handle h = ctx.get(KEY);
        if (h == null) {
            throw new IllegalStateException("RequestContextLifecycle has not run for this request — "
                    + "ensure it is contributed to the root middleware set");
        }
        return h;
    }

    // --- Per-request handle ---

    /**
     * Per-request lifecycle handle that collects cleanup registrations and drives their execution
     * at the end of the request.
     *
     * <p>{@link #onClose} registrations are executed in LIFO (last-in, first-out) order so that
     * nested resource bindings unwind correctly. {@link #afterClose} tasks run after every
     * {@link #onClose} registration completes, in FIFO (first-in, first-out) order.
     *
     * <p>Each cleanup entry runs in its own {@code try/catch}; a throwing cleanup is logged at
     * {@code WARN} and swallowed so that one bad scope does not block the rest.
     *
     * <p>Once {@link #completeNow()} or the end handler fires, subsequent calls to
     * {@link #onClose} or {@link #afterClose} throw {@link IllegalStateException} immediately.
     * Calling {@link #completeNow()} a second time (or having the end handler fire after
     * {@link #completeNow()} already ran) is a no-op.
     */
    public static final class Handle {

        /** Registrations collected by {@link #onClose}. Stored as a LIFO deque. */
        private final Deque<Object> onCloseRegistrations = new ArrayDeque<>();

        /** Tasks collected by {@link #afterClose}. Stored in insertion order (FIFO). */
        private final List<Runnable> afterCloseRegistrations = new ArrayList<>();

        /** Guards against double-close and late registration. */
        private boolean closed = false;

        /**
         * Registers a {@link ContextHolder.Scope} to be closed during request cleanup.
         *
         * <p>Registrations are executed in LIFO order relative to all other {@code onClose}
         * calls (both {@code Scope} and {@code Runnable} overloads).
         *
         * @param scope the scope to close; must not be {@code null}
         * @throws NullPointerException  if {@code scope} is {@code null}
         * @throws IllegalStateException if the lifecycle has already completed
         */
        public void onClose(ContextHolder.Scope scope) {
            Objects.requireNonNull(scope, "scope");
            guardOpen();
            onCloseRegistrations.push(scope);
        }

        /**
         * Registers a {@link Runnable} cleanup to be run during request cleanup.
         *
         * <p>Registrations are executed in LIFO order relative to all other {@code onClose}
         * calls (both {@code Scope} and {@code Runnable} overloads).
         *
         * <p><strong>Prefer {@link #onCloseRun(Runnable)} for a lambda or method reference.</strong>
         * This method is overloaded with {@link #onClose(ContextHolder.Scope)}, and both parameter
         * types are functional interfaces taking no arguments, so the compiler cannot pick between
         * them for an implicitly-typed argument. Use this overload only when you already hold a
         * value of declared type {@link Runnable} (or write an explicit {@code (Runnable)} cast).
         *
         * @param cleanup the cleanup runnable; must not be {@code null}
         * @throws NullPointerException  if {@code cleanup} is {@code null}
         * @throws IllegalStateException if the lifecycle has already completed
         */
        public void onClose(Runnable cleanup) {
            onCloseRun(cleanup);
        }

        /**
         * Registers a {@link Runnable} cleanup to be run during request cleanup — the
         * lambda-friendly entry point.
         *
         * <p>Behaviorally identical to {@link #onClose(Runnable)}, which delegates here. It exists
         * under a distinct name because {@code onClose} is overloaded on two no-argument functional
         * interfaces ({@link Runnable} and {@link ContextHolder.Scope}), which makes
         * {@code onClose(() -> ...)} ambiguous and therefore uncompilable. Choose between the three
         * entry points as follows:
         *
         * <ul>
         *   <li>{@link #onClose(ContextHolder.Scope)} — you already hold a {@link ContextHolder.Scope}
         *       (typically returned by {@code ContextHolder.bind(...)}).
         *   <li>{@code onCloseRun} — you are writing the cleanup inline as a lambda or method
         *       reference.
         *   <li>{@link #onClose(Runnable)} — you already hold a value declared as {@link Runnable}.
         * </ul>
         *
         * <p>Registrations are executed in LIFO order relative to all other {@code onClose} and
         * {@code onCloseRun} calls.
         *
         * @param cleanup the cleanup runnable; must not be {@code null}
         * @throws NullPointerException  if {@code cleanup} is {@code null}
         * @throws IllegalStateException if the lifecycle has already completed
         */
        public void onCloseRun(Runnable cleanup) {
            Objects.requireNonNull(cleanup, "cleanup");
            guardOpen();
            onCloseRegistrations.push(cleanup);
        }

        /**
         * Registers a {@link Runnable} task to be executed after all {@link #onClose} registrations
         * have completed.
         *
         * <p>Tasks are executed in FIFO (registration) order.
         *
         * @param task the task to run after all {@code onClose} registrations; must not be
         *     {@code null}
         * @throws NullPointerException  if {@code task} is {@code null}
         * @throws IllegalStateException if the lifecycle has already completed
         */
        public void afterClose(Runnable task) {
            Objects.requireNonNull(task, "task");
            guardOpen();
            afterCloseRegistrations.add(task);
        }

        /**
         * Convenience: binds the given MDC entries via {@link MDCContexts#bindAll(Map)} and
         * registers the returned scope with {@link #onClose(ContextHolder.Scope)}. A {@code null}
         * or empty map is a no-op; no duplicated-context check fires. Otherwise this method
         * propagates {@link MDCContexts#bindAll}'s behavior, including its fail-fast write guard.
         *
         * @param entries the MDC entries to bind for the remainder of this request; may be
         *                {@code null} or empty
         * @throws IllegalStateException if {@code entries} is non-empty and the caller is outside a
         *                               duplicated Vert.x context, or if this lifecycle has already
         *                               completed
         */
        public void bindMdc(Map<String, String> entries) {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            // Guard before binding so a late call (after closeAll has fired) does not leak the
            // newly-installed scope. With the bind-first ordering, MDCContexts.bindAll would
            // mutate the holder, then onClose's guardOpen() would throw, leaving the scope
            // unregistered and the MDC keys bound until something else cleared them.
            guardOpen();
            onClose(MDCContexts.bindAll(entries));
        }

        /**
         * Explicitly drives the request lifecycle to completion. Idempotent: subsequent calls after
         * the first are no-ops, as is the end handler firing after this method returns.
         *
         * <p>Required by the WebSocket upgrade path, where Vert.x's {@code completeHandshake()}
         * bypasses the normal response end handler.
         */
        public void completeNow() {
            closeAll();
        }

        /**
         * Throws {@link IllegalStateException} if the lifecycle has already completed.
         *
         * @throws IllegalStateException if {@link #closed} is {@code true}
         */
        private void guardOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "RequestContextLifecycle already completed; cannot register onClose or afterClose");
            }
        }

        /**
         * Drives the cleanup sequence. Idempotent: the second call is a no-op.
         *
         * <ol>
         *   <li>Sets {@link #closed} to {@code true} so late registrations fail fast.
         *   <li>Pops and closes/runs each {@link #onCloseRegistrations} entry (LIFO). Each entry
         *       runs in its own {@code try/catch}; failures are logged at WARN.
         *   <li>Runs each {@link #afterCloseRegistrations} task (FIFO). Each task runs in its own
         *       {@code try/catch}; failures are logged at WARN.
         * </ol>
         */
        void closeAll() {
            if (closed) {
                return;
            }
            closed = true;

            // --- Phase 1: onClose registrations (LIFO) ---
            while (!onCloseRegistrations.isEmpty()) {
                Object reg = onCloseRegistrations.pop();
                try {
                    if (reg instanceof ContextHolder.Scope s) {
                        s.close();
                    } else {
                        ((Runnable) reg).run();
                    }
                } catch (RuntimeException e) {
                    log.warn("Request-scoped cleanup failed", e);
                }
            }

            // --- Phase 2: afterClose tasks (FIFO) ---
            for (Runnable task : afterCloseRegistrations) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("Request-scoped afterClose task failed", e);
                }
            }
            afterCloseRegistrations.clear();
        }
    }
}
