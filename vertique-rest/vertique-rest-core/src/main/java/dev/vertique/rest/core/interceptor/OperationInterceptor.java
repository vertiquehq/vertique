// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;

/**
 * SPI for cross-cutting logic applied around individual JAX-RS operation invocations.
 *
 * <p>The interface provides callbacks split into two categories:
 *
 * <h3>Sync observers (fire-and-forget, cannot affect outcome)</h3>
 * <ul>
 *   <li>{@link #onOperation} — called before the operation method is invoked</li>
 *   <li>{@link #onSuccess} — called after a successful invocation with the raw result object</li>
 *   <li>{@link #onError} — called when the operation throws or returns a failed future</li>
 * </ul>
 *
 * <h3>Async handlers (can affect outcome)</h3>
 * <ul>
 *   <li>{@link #beforeOperation} — returns a (possibly modified) context; a failed
 *       {@link Future} short-circuits the operation</li>
 *   <li>{@link #afterOperation} — called after a successful invocation; may transform the
 *       result by returning a different object</li>
 *   <li>{@link #recoverOperation} — called on operation failure; a succeeded {@link Future}
 *       with a replacement result recovers the operation; a failed {@link Future} propagates
 *       the error</li>
 * </ul>
 *
 * <p>All methods have default no-op implementations. Interceptors are sorted by the
 * {@link OrderedExtension} ordering contract — phase first, then {@link #priority()} ascending,
 * then {@link #orderKey()} as a stable tie-break (lower value runs first within a phase).
 *
 * <p><strong>Design note on {@link #recoverOperation}:</strong> Unlike
 * {@link dev.vertique.kafka.interceptor.KafkaConsumerInterceptor#recoverError} (which returns
 * {@code Future<Void>}) or
 * {@link dev.vertique.rest.client.interceptor.RestClientInterceptor#recoverRequest} (which returns
 * a new context for retry), this method returns {@code Future<Object>} — an optional replacement
 * result. This is appropriate because operation interceptors deal with typed resource method
 * results, and recovery often means substituting a default or cached value rather than
 * re-executing the operation.
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Operation-level tracing — {@code beforeOperation} (start span) + {@code onSuccess} /
 *       {@code onError} (end span)</li>
 *   <li>Per-operation audit logging — {@code onSuccess} + {@code onError}</li>
 *   <li>Context enrichment — {@code beforeOperation} with
 *       {@link OperationContext#withAttribute}</li>
 *   <li>Cache-on-read — {@code afterOperation} to populate a cache</li>
 *   <li>Fallback result on failure — {@code recoverOperation} returning a default value</li>
 * </ul>
 *
 * <p>Register interceptors via Dagger multibinding ({@code @IntoSet}).
 *
 * @see OrderedExtension
 */
public interface OperationInterceptor extends OrderedExtension {

    // --- Sync observers ---

    /**
     * Synchronous observer called before the operation method is invoked (after all
     * {@link #beforeOperation} async handlers have completed). Suitable for structured logging.
     *
     * <p>Exceptions thrown here are swallowed — use {@link #beforeOperation} to modify the
     * context or short-circuit the operation.
     *
     * @param ctx the operation context
     */
    default void onOperation(OperationContext ctx) {}

    /**
     * Synchronous observer called after a successful operation invocation. The {@code result}
     * is the raw value returned by the resource method (after any {@link #afterOperation}
     * transformation). Suitable for audit logging or success metrics.
     *
     * <p>Exceptions thrown here are swallowed.
     *
     * @param ctx    the operation context
     * @param result the raw operation result
     */
    default void onSuccess(OperationContext ctx, Object result) {}

    /**
     * Synchronous observer called when the operation fails. Cannot affect the error outcome —
     * use {@link #recoverOperation} for that.
     *
     * <p>Exceptions thrown here are swallowed.
     *
     * @param ctx   the operation context
     * @param cause the operation failure
     */
    default void onError(OperationContext ctx, Throwable cause) {}

    // --- Async handlers ---

    /**
     * Async handler called before the operation method is invoked. Implementations may return
     * a modified copy of the context (using {@link OperationContext#withAttribute}) to enrich
     * it with tracing spans, authorization data, or other per-operation metadata.
     *
     * <p>Each interceptor in the chain receives the context returned by the previous interceptor.
     * A failed {@link Future} short-circuits the operation and routes to the error pipeline.
     *
     * @param ctx the current operation context (immutable, copy-on-write)
     * @return a {@link Future} containing the (possibly updated) context; a failed future
     *         short-circuits the operation
     */
    default Future<OperationContext> beforeOperation(OperationContext ctx) {
        return Future.succeededFuture(ctx);
    }

    /**
     * Async handler called after a successful operation invocation. Implementations may return
     * a different (transformed) result object. The transformed result is passed to subsequent
     * interceptors in the chain and ultimately serialized as the response body.
     *
     * <p>A failed {@link Future} routes the operation to the error pipeline (as if the
     * operation itself had failed).
     *
     * @param ctx    the operation context
     * @param result the raw operation result
     * @return a {@link Future} containing the (possibly transformed) result; a failed future
     *         propagates as an operation failure
     */
    default Future<Object> afterOperation(OperationContext ctx, Object result) {
        return Future.succeededFuture(result);
    }

    /**
     * Async recovery handler called when the operation fails. Implementations may return a
     * replacement result to recover from the failure and produce a normal response.
     *
     * <p>Return a succeeded {@link Future} with a replacement result to indicate recovery —
     * the pipeline will treat the response as successful using the returned value.
     * Return a failed {@link Future} (the default) to decline recovery and propagate the error.
     *
     * <p>Interceptors are tried in {@link dev.vertique.core.extension.OrderedExtension} order
     * (phase → priority → orderKey); the first interceptor that returns a succeeded future wins.
     * Later interceptors are not invoked once recovery is accepted.
     *
     * <p>Example — return a cached value on failure:
     * <pre>{@code
     * public Future<Object> recoverOperation(OperationContext ctx, Throwable cause) {
     *     return cache.get(ctx.operationId())
     *         .<Object>map(cached -> cached)
     *         .orElse(Future.failedFuture(cause));
     * }
     * }</pre>
     *
     * @param ctx   the operation context
     * @param cause the operation failure
     * @return a succeeded {@link Future} with a replacement result to recover, or a failed
     *         {@link Future} to propagate the error
     */
    default Future<Object> recoverOperation(OperationContext ctx, Throwable cause) {
        return Future.failedFuture(cause);
    }
}
