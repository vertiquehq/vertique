// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.async.Combinators;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
import io.vertx.core.Future;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes Kafka consumer interceptor pipelines: synchronous fire-and-forget observers and
 * asynchronous handler chains.
 *
 * <p>Observer methods ({@code onRecord}, {@code onSuccess}, {@code onError}) run all interceptors
 * in order, swallowing individual exceptions so that a failing interceptor does not disrupt
 * dispatch. Async chain methods ({@code runBeforeInterceptors}, {@code runAfterInterceptors},
 * {@code runRecoverError}) compose interceptor futures sequentially via
 * {@link dev.vertique.core.async.Combinators}.
 */
@Slf4j
final class KafkaConsumerInterceptorChain {

    private final String consumerName;
    private final List<KafkaConsumerInterceptor> interceptors;

    /**
     * Creates a new interceptor chain for the given consumer.
     *
     * @param consumerName the consumer name used in log messages
     * @param interceptors the ordered list of interceptors to run
     */
    KafkaConsumerInterceptorChain(String consumerName, List<KafkaConsumerInterceptor> interceptors) {
        this.consumerName = consumerName;
        this.interceptors = interceptors;
    }

    // --- Sync observers ---

    /**
     * Fires {@code onRecord} on every interceptor. Exceptions are swallowed and logged.
     *
     * @param ctx the dispatch context before any before-dispatch processing
     */
    void runOnRecordObservers(KafkaDispatchContext<Object> ctx) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onRecord(ctx),
                (i, e) -> log.warn("[{}] Interceptor onRecord threw exception", consumerName, e));
    }

    /**
     * Fires {@code onSuccess} on every interceptor after a successful dispatch. Exceptions are
     * swallowed and logged.
     *
     * @param ctx the dispatch context after successful dispatch
     */
    void runOnSuccessObservers(KafkaDispatchContext<Object> ctx) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onSuccess(ctx),
                (i, e) -> log.warn("[{}] Interceptor onSuccess threw exception", consumerName, e));
    }

    /**
     * Fires {@code onError} on every interceptor. Exceptions are swallowed and logged.
     *
     * @param ctx the dispatch context at the time of the error
     * @param error the dispatch error
     */
    void runOnErrorObservers(KafkaDispatchContext<Object> ctx, Throwable error) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onError(ctx, error),
                (i, e) -> log.warn("[{}] Interceptor onError threw exception", consumerName, e));
    }

    // --- Async chains ---

    /**
     * Runs all registered interceptors' {@code beforeDispatch} callbacks in
     * {@link dev.vertique.core.extension.OrderedExtension} order (phase → priority → orderKey),
     * chaining them sequentially via {@link Combinators#foldSequential}.
     *
     * <p>When the context is already filtered at a given step, that interceptor's
     * {@code beforeDispatch} is NOT called and the context passes through unchanged. A failing
     * interceptor short-circuits the remaining chain (fail-fast).
     *
     * @param initial the initial dispatch context
     * @return a future resolving to the final context after all interceptors have run
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Future<KafkaDispatchContext<Object>> runBeforeInterceptors(KafkaDispatchContext<Object> initial) {
        return Combinators.<KafkaConsumerInterceptor, KafkaDispatchContext<?>>foldSequential(
                        interceptors,
                        initial,
                        (i, ctx) -> ctx.filtered() ? Future.succeededFuture(ctx) : i.beforeDispatch(ctx))
                .map(ctx -> (KafkaDispatchContext<Object>) ctx);
    }

    /**
     * Runs all registered interceptors' {@code afterDispatch} callbacks asynchronously, chained in
     * {@link dev.vertique.core.extension.OrderedExtension} order (phase → priority → orderKey),
     * via {@link Combinators#foldSequential}.
     *
     * <p>Failures from individual interceptors are logged and do not abort the chain or
     * affect the commit strategy. The recover-and-continue policy lives in the per-step lambda.
     *
     * @param ctx the final dispatch context after successful dispatch
     * @return a future that completes when all post-dispatch callbacks have finished
     */
    Future<Void> runAfterInterceptors(KafkaDispatchContext<Object> ctx) {
        return Combinators.foldSequential(interceptors, (Void) null, (i, v) -> Future.<Void>succeededFuture()
                .compose(x -> i.afterDispatch(ctx))
                .recover(cause -> {
                    log.warn("[{}] Interceptor afterDispatch failed", consumerName, cause);
                    return Future.succeededFuture();
                }));
    }

    /**
     * Tries each interceptor's {@code recoverError} callback in
     * {@link dev.vertique.core.extension.OrderedExtension} order (phase → priority → orderKey),
     * via {@link Combinators#recoverFirstWins}.
     *
     * <p>The first interceptor that returns a succeeded future wins; later interceptors are not
     * invoked. If no interceptor recovers, the original error is propagated as a failed future.
     *
     * @param ctx the dispatch context at the time of the failure
     * @param error the original dispatch failure
     * @return a succeeded future if any interceptor handled the error, or a failed future carrying
     *     the original error
     */
    Future<Void> runRecoverError(KafkaDispatchContext<Object> ctx, Throwable error) {
        return Combinators.recoverFirstWins(interceptors, error, (i, e) -> i.recoverError(ctx, e), t -> false);
    }
}
