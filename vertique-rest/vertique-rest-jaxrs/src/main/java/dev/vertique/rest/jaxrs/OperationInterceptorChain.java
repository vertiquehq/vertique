// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.async.Combinators;
import dev.vertique.rest.core.interceptor.OperationContext;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import io.vertx.core.Future;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes {@link OperationInterceptor} pipelines for JAX-RS resource method invocations.
 *
 * <p>Two distinct invocation patterns are supported:
 * <ul>
 *   <li><b>Async chains</b> — {@link #chainBeforeOperationInterceptors}, {@link #chainAfterOperationInterceptors},
 *       and {@link #chainRecoverOperationInterceptors} thread the (possibly updated) context or result
 *       through each interceptor sequentially, propagating failures.</li>
 *   <li><b>Sync fire-and-forget observers</b> — {@link #fireOnOperation}, {@link #fireOnSuccess},
 *       and {@link #fireOnError} notify all interceptors but swallow exceptions so that observers
 *       cannot affect the outcome of the request pipeline.</li>
 * </ul>
 *
 * <p>The async chains delegate to {@link Combinators#foldSequential} and
 * {@link Combinators#recoverFirstWins}; the sync observers delegate to
 * {@link Combinators#forEachSwallowSync}.
 */
@Slf4j
final class OperationInterceptorChain {

    private final List<OperationInterceptor> interceptors;

    /**
     * Creates a new chain backed by the given interceptor list.
     *
     * @param interceptors sorted list of operation interceptors; must not be {@code null}
     */
    OperationInterceptorChain(List<OperationInterceptor> interceptors) {
        this.interceptors = interceptors;
    }

    // --- Async chains ---

    /**
     * Chains {@link OperationInterceptor#beforeOperation} handlers sequentially, threading the
     * (possibly updated) {@link OperationContext} through each interceptor.
     *
     * @param opCtx the current operation context
     * @param index the index of the first interceptor to invoke
     * @return a {@link Future} completing with the final context after all interceptors have run
     */
    Future<OperationContext> chainBeforeOperationInterceptors(OperationContext opCtx, int index) {
        return Combinators.foldSequential(
                interceptors.subList(index, interceptors.size()), opCtx, (i, ctx) -> i.beforeOperation(ctx));
    }

    /**
     * Chains {@link OperationInterceptor#afterOperation} handlers sequentially, allowing each to
     * transform the result.
     *
     * @param opCtx  the current operation context
     * @param result the current result value (may be transformed by each interceptor)
     * @param index  the index of the first interceptor to invoke
     * @return a {@link Future} that completes with the final (possibly transformed) result
     */
    Future<Object> chainAfterOperationInterceptors(OperationContext opCtx, Object result, int index) {
        return Combinators.foldSequential(
                interceptors.subList(index, interceptors.size()), result, (i, r) -> i.afterOperation(opCtx, r));
    }

    /**
     * Chains {@link OperationInterceptor#recoverOperation} handlers. The first interceptor that
     * returns a succeeded {@link Future} wins; later interceptors are not invoked.
     *
     * @param opCtx the operation context
     * @param cause the current throwable
     * @param index the index of the first interceptor to invoke
     * @return a succeeded {@link Future} with a replacement result if recovered, or a failed
     *         {@link Future} with the final cause if no interceptor recovered
     */
    Future<Object> chainRecoverOperationInterceptors(OperationContext opCtx, Throwable cause, int index) {
        return Combinators.recoverFirstWins(
                interceptors.subList(index, interceptors.size()),
                cause,
                (i, e) -> i.recoverOperation(opCtx, e),
                t -> false);
    }

    // --- Sync fire-and-forget observers ---

    /**
     * Fires the sync {@link OperationInterceptor#onOperation} observer on all interceptors.
     * Exceptions are swallowed — sync observers must not affect the outcome.
     *
     * @param opCtx the current operation context
     */
    void fireOnOperation(OperationContext opCtx) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onOperation(opCtx),
                (i, e) -> log.warn(
                        "Sync observer {} threw in interceptor {}",
                        e.getMessage(),
                        i.getClass().getSimpleName(),
                        e));
    }

    /**
     * Fires the sync {@link OperationInterceptor#onSuccess} observer on all interceptors.
     * Exceptions are swallowed.
     *
     * @param opCtx  the operation context
     * @param result the operation result
     */
    void fireOnSuccess(OperationContext opCtx, Object result) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onSuccess(opCtx, result),
                (i, e) -> log.warn(
                        "Sync observer {} threw in interceptor {}",
                        e.getMessage(),
                        i.getClass().getSimpleName(),
                        e));
    }

    /**
     * Fires the sync {@link OperationInterceptor#onError} observer on all interceptors.
     * Exceptions are swallowed.
     *
     * @param opCtx the operation context
     * @param cause the operation failure
     */
    void fireOnError(OperationContext opCtx, Throwable cause) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onError(opCtx, cause),
                (i, e) -> log.warn(
                        "Sync observer {} threw in interceptor {}",
                        e.getMessage(),
                        i.getClass().getSimpleName(),
                        e));
    }
}
