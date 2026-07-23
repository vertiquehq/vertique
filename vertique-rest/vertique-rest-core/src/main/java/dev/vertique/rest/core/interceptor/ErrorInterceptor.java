// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * SPI for cross-cutting logic applied to the server-side error mapping pipeline.
 *
 * <p>Unlike {@link RequestInterceptor} and {@link OperationInterceptor}, this interface provides
 * only async handlers — no sync observers. The error pipeline is purely transformative: each
 * stage either transforms the throwable or the response, and those transformations must be
 * composable as async {@link Future} chains.
 *
 * <h3>Async handlers (can affect outcome)</h3>
 * <ul>
 *   <li>{@link #beforeMapping} — transforms the {@link Throwable} before the framework's
 *       {@code RestExceptionMapper} and {@code ExceptionMapperRegistry} run; suitable for unwrapping,
 *       enriching, or reclassifying exceptions</li>
 *   <li>{@link #afterMapping} — transforms the {@link Response} produced by the exception mapper
 *       before it is serialized and sent; suitable for adding error-specific headers or
 *       enriching the problem detail body</li>
 * </ul>
 *
 * <p>All methods have default pass-through implementations. Interceptors are sorted by the
 * {@link OrderedExtension} ordering contract — phase first, then {@link #priority()} ascending,
 * then {@link #orderKey()} as a stable tie-break (lower value runs first within a phase).
 *
 * <p>The original throwable (before any {@link #beforeMapping} transformation) is available in
 * {@link RoutingContext#data()} under the key {@link RequestInterceptor#ORIGINAL_ERROR_KEY}
 * throughout the request lifecycle.
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Exception unwrapping — {@code beforeMapping} to unwrap framework wrappers before mapping</li>
 *   <li>Error enrichment — {@code beforeMapping} adding contextual data to the exception</li>
 *   <li>Custom error headers — {@code afterMapping} to add {@code X-Request-Id} or retry hints</li>
 *   <li>Error response normalization — {@code afterMapping} replacing vendor-specific bodies with
 *       RFC 9457 problem details</li>
 * </ul>
 *
 * <p>Register interceptors via Dagger multibinding ({@code @IntoSet}).
 *
 * @see OrderedExtension
 */
public interface ErrorInterceptor extends OrderedExtension {

    // --- Async handlers ---

    /**
     * Async handler called with the original {@link Throwable} before the framework's
     * {@code RestExceptionMapper} and {@code ExceptionMapperRegistry} translate it to an HTTP
     * {@link Response}.
     *
     * <p>Implementations may return a different (or the same) {@link Throwable} to reclassify,
     * unwrap, or enrich the error before it reaches the exception mapper registry.
     *
     * <p>Each interceptor in the chain receives the throwable returned by the previous
     * interceptor. A failed {@link Future} itself (a meta-failure) is logged and the original
     * throwable passed to the next interceptor unchanged.
     *
     * <p>The default implementation passes the throwable through unchanged.
     *
     * @param rc        the Vert.x {@link RoutingContext} for the failed request
     * @param throwable the current throwable to transform
     * @return a {@link Future} containing the (possibly transformed) throwable to pass to the
     *         next interceptor
     */
    default Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
        return Future.succeededFuture(throwable);
    }

    /**
     * Async handler called with the {@link Response} produced by the exception mapper, after the
     * framework's {@code ExceptionMapperRegistry} has mapped the throwable to an HTTP response
     * but before that response is serialized and sent to the client.
     *
     * <p>Implementations may add headers, change the status code, or replace the entity by
     * returning a new {@link Response} instance.
     *
     * <p>Each interceptor in the chain receives the response returned by the previous interceptor.
     * A failed {@link Future} is logged and the unmodified response passed to the next interceptor.
     *
     * <p>The default implementation passes the response through unchanged.
     *
     * @param rc       the Vert.x {@link RoutingContext} for the failed request
     * @param response the error {@link Response} produced by the exception mapper
     * @return a {@link Future} containing the (possibly transformed) {@link Response}
     */
    default Future<Response> afterMapping(RoutingContext rc, Response response) {
        return Future.succeededFuture(response);
    }
}
