// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.eventbus.Result;
import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;
import java.time.Instant;

/**
 * SPI for cross-cutting logic applied to the event bus service dispatch pipeline.
 *
 * <p>The interface provides callbacks split into two categories, following the same pattern as
 * {@link dev.vertique.kafka.interceptor.KafkaConsumerInterceptor}:
 *
 * <h3>Sync observers (fire-and-forget, cannot affect outcome)</h3>
 * <ul>
 *   <li>{@link #onDispatch} — called before dispatch, after any {@link #beforeDispatch} async
 *       handlers have run; suitable for structured logging or metrics</li>
 *   <li>{@link #onComplete} — called after every dispatch (both success and failure) with the
 *       full {@link Result} and timing, <em>before</em> recovery; suitable for handler-level latency
 *       recording and alerting</li>
 *   <li>{@link #onTerminalComplete} — called once with the terminal (post-recovery) outcome, while the
 *       dispatch-context scope is still open; suitable for terminal-outcome audit evidence</li>
 * </ul>
 *
 * <h3>Async handlers (can affect outcome)</h3>
 * <ul>
 *   <li>{@link #beforeDispatch} — returns a (possibly modified) context; a failed
 *       {@link Future} short-circuits dispatch and routes to error handling</li>
 *   <li>{@link #afterDispatch} — post-processing after dispatch (both success and failure);
 *       failures are logged but do not affect the outcome</li>
 *   <li>{@link #recoverError} — called on dispatch failure; a succeeded {@link Future} means
 *       the error is handled; a failed {@link Future} proceeds with the normal error behaviour</li>
 * </ul>
 *
 * <p>All methods have default no-op implementations. Interceptors are sorted by the
 * {@link OrderedExtension} ordering contract — phase first, then {@link #priority()} ascending,
 * then {@link #orderKey()} as a stable tie-break (lower value runs first within a phase).
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Distributed tracing — {@code beforeDispatch} (inject trace attributes) + {@code afterDispatch}</li>
 *   <li>Latency recording and SLA alerting — {@code onComplete} with timing parameters</li>
 *   <li>Structured logging — {@code onDispatch} + {@code onComplete}</li>
 *   <li>Contextual enrichment — {@code beforeDispatch} with {@code ctx.withAttribute(...)}</li>
 *   <li>Circuit-breaker integration — {@code recoverError} to intercept failures</li>
 *   <li>Audit evidence (terminal, post-recovery outcome) — {@code onTerminalComplete}</li>
 * </ul>
 *
 * <p>Register interceptors via Dagger multibinding ({@code @IntoSet}).
 *
 * @see OrderedExtension
 */
public interface ServiceInterceptor extends OrderedExtension {

    // --- Sync observers ---

    /**
     * Synchronous observer called before dispatch (after all {@link #beforeDispatch} async
     * handlers have completed). Suitable for structured logging or metrics.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. Use {@link #beforeDispatch} to modify the
     * context or short-circuit dispatch.
     *
     * @param ctx the dispatch context (read-only at this point; use {@link #beforeDispatch}
     *            to modify)
     */
    default void onDispatch(ServiceDispatchContext ctx) {}

    /**
     * Synchronous observer called after every dispatch, for both successful and failed
     * outcomes. Carries the full {@link Result} and wall-clock timing, enabling
     * unified latency recording and outcome-based alerting in a single callback.
     *
     * <p>Interceptors can distinguish outcomes via {@link Result#isSuccess()} /
     * {@link Result#isFailure()}.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param ctx       the dispatch context
     * @param result    the dispatch result (success or failure)
     * @param startTime the wall-clock instant at which the dispatch was initiated
     * @param endTime   the wall-clock instant at which the dispatch completed
     */
    default void onComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {}

    /**
     * Synchronous observer called once with the <strong>terminal</strong> service outcome, after the
     * {@link #recoverError} decision and before the dispatch-context scope closes. Unlike
     * {@link #onComplete} (which observes the handler outcome <em>before</em> recovery), this hook sees
     * the terminal post-recovery dispatch result (it reflects the recovery decision, not reply delivery):
     *
     * <ul>
     *   <li>handler success → the original success result;</li>
     *   <li>handler failure that a {@link #recoverError} interceptor recovered → {@code Result.success(null)};</li>
     *   <li>handler failure that no interceptor recovered → {@code Result.failure(finalCause)}.</li>
     * </ul>
     *
     * <p>The {@code endTime} is captured after the recovery decision, so the duration spans recovery.
     * The hook is <strong>guaranteed to run on the original dispatch Vert.x context while the dispatch-context
     * scope is still open</strong> — even if a user {@code afterDispatch}/{@code recoverError} future settles
     * on a foreign context — so holder context ({@code SecurityContext}, {@code CorrelationContext}) is
     * readable here. This makes the hook suitable for terminal-outcome observability.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param ctx       the dispatch context
     * @param result    the terminal dispatch result (success, recovered, or failed)
     * @param startTime the wall-clock instant at which the dispatch was initiated
     * @param endTime   the wall-clock instant at which the terminal outcome was reached (after recovery)
     */
    default void onTerminalComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {}

    // --- Async handlers ---

    /**
     * Async handler called before dispatch. Implementations may return a modified copy of the
     * context (using {@link ServiceDispatchContext#withAttribute}) to enrich the context.
     *
     * <p>Each interceptor in the chain receives the context returned by the previous interceptor,
     * so enrichments accumulate through the chain.
     *
     * <p>A failed {@link Future} short-circuits dispatch: the error enters the error-handling
     * pipeline and all subsequent interceptors are skipped.
     *
     * @param ctx the current dispatch context (immutable, copy-on-write)
     * @return a {@link Future} containing the (possibly updated) context to pass to the next
     *         interceptor; a failed future short-circuits dispatch
     */
    default Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext ctx) {
        return Future.succeededFuture(ctx);
    }

    /**
     * Async handler called after dispatch completes, for both successful and failed outcomes.
     * Suitable for async post-processing such as flushing metrics buffers or completing
     * distributed trace spans.
     *
     * <p>Failures returned from this handler are logged but do not affect the dispatch outcome
     * or any reply sent to the caller.
     *
     * @param ctx    the dispatch context
     * @param result the dispatch result (success or failure)
     * @return a {@link Future} that completes when post-processing is done; failures are logged
     */
    default Future<Void> afterDispatch(ServiceDispatchContext ctx, Result<?> result) {
        return Future.succeededFuture();
    }

    /**
     * Async recovery handler called when dispatch fails, before the normal error response is sent.
     *
     * <p>Return a succeeded {@link Future} to indicate the error has been handled — the dispatch
     * pipeline will treat the operation as recovered and no error response will be sent.
     * Return a failed {@link Future} (the default) to decline recovery and allow the error to
     * propagate to the caller.
     *
     * <p>Interceptors are tried in {@link dev.vertique.core.extension.OrderedExtension} order
     * (phase → priority → orderKey); the first interceptor that returns a succeeded future wins.
     * Later interceptors are not invoked once recovery is accepted.
     *
     * <p>Example — suppress validation errors silently:
     * <pre>{@code
     * public Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
     *     if (error instanceof BeanValidationException) {
     *         return Future.succeededFuture(); // handled, no error propagated
     *     }
     *     return Future.failedFuture(error); // not handled
     * }
     * }</pre>
     *
     * @param ctx   the dispatch context at the time of failure
     * @param error the original dispatch failure
     * @return a succeeded {@link Future} to mark the error as handled, or a failed
     *         {@link Future} to decline recovery
     */
    default Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
        return Future.failedFuture(error);
    }
}
