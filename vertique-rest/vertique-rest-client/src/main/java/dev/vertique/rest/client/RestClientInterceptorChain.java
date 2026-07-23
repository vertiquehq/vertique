// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.async.Combinators;
import dev.vertique.rest.client.interceptor.RestClientAttemptCompletion;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes interceptor pipelines for REST client requests: synchronous fire-and-forget observers
 * and asynchronous handler chains.
 *
 * <p>This class encapsulates all eight interceptor callback styles defined by
 * {@link RestClientInterceptor}:
 * <ul>
 *   <li>Sync observers: {@code onRequest}, {@code onResponse}, {@code onError},
 *       {@code onAttemptCompleted}</li>
 *   <li>Async chains: {@code beforeRequest}, {@code afterResponse}, {@code transformError},
 *       {@code recoverRequest}</li>
 * </ul>
 *
 * <p>All structural kernels (sequential fold, first-wins recovery, synchronous swallowing loop)
 * are delegated to {@link Combinators}; this class supplies only the per-interceptor step lambdas
 * and the site-specific failure handling (log level, message).
 */
@Slf4j
final class RestClientInterceptorChain {

    private final String clientName;
    private final List<RestClientInterceptor> interceptors;

    /**
     * Creates a new interceptor chain.
     *
     * @param clientName the logical REST client name, used in log messages
     * @param interceptors the interceptors to run, already sorted by {@link dev.vertique.core.extension.OrderedExtension#comparator()}
     */
    RestClientInterceptorChain(String clientName, List<RestClientInterceptor> interceptors) {
        this.clientName = clientName;
        this.interceptors = interceptors;
    }

    // --- Sync Observers ---

    /**
     * Fires sync {@link RestClientInterceptor#onRequest} observers, swallowing exceptions.
     *
     * @param ctx the request context
     */
    void fireOnRequest(RestClientRequestContext ctx) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onRequest(ctx),
                (i, e) -> log.debug("onRequest observer threw exception (ignored)", e));
    }

    /**
     * Fires sync {@link RestClientInterceptor#onResponse} observers, swallowing exceptions.
     *
     * @param req the request context
     * @param res the response context
     */
    void fireOnResponse(RestClientRequestContext req, RestClientResponseContext res) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onResponse(req, res),
                (i, e) -> log.debug("onResponse observer threw exception (ignored)", e));
    }

    /**
     * Fires sync {@link RestClientInterceptor#onError} observers, swallowing exceptions.
     *
     * @param req the request context
     * @param res the response context, or {@code null} for transport errors
     * @param error the failure
     */
    void fireOnError(RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onError(req, res, error),
                (i, e) -> log.debug("onError observer threw exception (ignored)", e));
    }

    /**
     * Fires sync {@link RestClientInterceptor#onAttemptCompleted} observers, swallowing exceptions.
     *
     * @param req        the request context for this attempt
     * @param completion the attempt's completion facts
     */
    void fireOnAttemptCompleted(RestClientRequestContext req, RestClientAttemptCompletion completion) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onAttemptCompleted(req, completion),
                (i, e) -> log.debug("onAttemptCompleted observer threw exception (ignored)", e));
    }

    // --- Async Chains ---

    /**
     * Runs the async {@link RestClientInterceptor#beforeRequest} chain. Each interceptor receives
     * the context returned by the previous interceptor, so header/body/URI changes accumulate
     * through the chain. Returns the final context after all interceptors have run.
     *
     * @param ctx the initial request context
     * @return a {@link Future} containing the final (possibly updated) context
     */
    Future<RestClientRequestContext> runBeforeInterceptors(RestClientRequestContext ctx) {
        return Combinators.foldSequential(interceptors, ctx, (i, c) -> i.beforeRequest(c));
    }

    /**
     * Runs the async {@link RestClientInterceptor#afterResponse} chain.
     *
     * @param req the request context
     * @param res the response context
     * @return a {@link Future} that completes when all interceptors have run
     */
    Future<Void> runAfterInterceptors(RestClientRequestContext req, RestClientResponseContext res) {
        return Combinators.foldSequential(interceptors, (Void) null, (i, v) -> i.afterResponse(req, res));
    }

    /**
     * Runs the async {@link RestClientInterceptor#transformError} chain. Each interceptor receives
     * the current throwable and may replace it; the last result is returned.
     *
     * @param req the request context
     * @param res the response context, or {@code null} for transport errors
     * @param error the initial error
     * @return a {@link Future} containing the (possibly transformed) throwable
     */
    Future<Throwable> runTransformError(
            RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
        return Combinators.foldSequential(interceptors, error, (i, t) -> i.transformError(req, res, t));
    }

    /**
     * Runs {@link RestClientInterceptor#recoverRequest} interceptors in priority order. The first
     * interceptor that returns a succeeded {@link Future} with a new
     * {@link RestClientRequestContext} wins — subsequent interceptors are skipped. If all
     * interceptors fail (return failed futures), the original error propagates.
     *
     * @param reqCtx the original request context
     * @param resCtx the response context, or {@code null} for transport-level errors
     * @param error the failure to recover from
     * @return a succeeded future with the recovery context, or a failed future if no recovery
     */
    Future<RestClientRequestContext> runRecoverInterceptors(
            RestClientRequestContext reqCtx, @Nullable RestClientResponseContext resCtx, Throwable error) {
        return Combinators.recoverFirstWins(
                interceptors, error, (i, e) -> i.recoverRequest(reqCtx, resCtx, e), t -> false);
    }
}
