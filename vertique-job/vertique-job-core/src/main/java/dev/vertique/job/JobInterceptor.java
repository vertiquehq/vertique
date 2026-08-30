// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.eventbus.Result;
import dev.vertique.core.extension.OrderedExtension;
import java.time.Instant;

/**
 * SPI for cross-cutting logic applied around job dispatch.
 *
 * <p>All methods have default no-op implementations. Interceptors are ordered by the
 * {@link OrderedExtension} contract: phase ascending, then priority ascending, then
 * {@link #orderKey()} as a stable tie-break. Use {@link OrderedExtension#comparator()} at
 * sort sites.
 *
 * <p>Register interceptors via Dagger multibinding ({@code @IntoSet}) against
 * {@code Set<JobInterceptor>}.
 *
 * <p>This SPI intentionally remains synchronous and observation-only. Job dispatch owns the
 * completion boundary, so an asynchronous interceptor contract would require defining ordering,
 * timeout, failure, and context-propagation semantics for observer work. Keep asynchronous work
 * behind an explicitly managed application component instead of extending this lifecycle hook.
 *
 * <p>Common use cases:
 * <ul>
 *   <li>MDC context propagation (built into {@link dev.vertique.core.eventbus.DispatchEnvelope} dispatch,
 *       no interceptor needed — see {@code mdcContext} field)</li>
 *   <li>Distributed tracing — {@code onDispatch} + {@code onComplete}</li>
 *   <li>Metrics — {@code onComplete} with timing</li>
 *   <li>Audit logging — {@code onComplete} checking {@code result.isSuccess()}</li>
 * </ul>
 */
public interface JobInterceptor extends OrderedExtension {

    // --- Sync observers ---

    /**
     * Synchronous observer called when a job dispatch begins (after the body is received).
     * Suitable for setting up MDC context or recording dispatch metrics.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param ctx the dispatch context for this execution
     */
    default void onDispatch(JobDispatchContext ctx) {}

    /**
     * Synchronous observer called after every job dispatch (both success and failure).
     * Suitable for recording latency, clearing MDC context, or emitting audit events.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param ctx       the dispatch context for this execution
     * @param result    the dispatch result, or {@code null} if the reply body was not a
     *                    {@link dev.vertique.core.eventbus.Result} (e.g. unexpected format)
     * @param startTime when dispatch started
     * @param endTime   when dispatch completed
     */
    default void onComplete(JobDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {}
}
