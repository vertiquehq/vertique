// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Scoped, ordered request handlers for the REST HTTP stack.
 *
 * <p>{@link dev.vertique.rest.core.middleware.Middleware} is the core interface: a
 * {@link io.vertx.core.Handler}{@code <RoutingContext>} that extends
 * {@link dev.vertique.core.extension.OrderedExtension}. Middlewares are sorted by
 * {@link dev.vertique.core.extension.OrderedExtension#comparator()} (phase → priority → orderKey)
 * and scoped to {@code ROOT} (all requests) or {@code API} (OpenAPI-validated routes). Every
 * implementation must declare an explicit {@link dev.vertique.rest.core.middleware.Middleware#priority()}.
 * Implementations are contributed via Dagger {@code Set<Middleware>} multibinding and mounted
 * automatically by {@link dev.vertique.rest.core.router.HttpVerticle}.
 *
 * <p>Built-in middlewares include {@link dev.vertique.rest.core.middleware.ContextualLoggingMiddleware}
 * (MDC enrichment), {@link dev.vertique.rest.core.middleware.DefaultHeadersMiddleware}
 * (configurable security headers), and
 * {@link dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware}
 * (rejects requests with unsupported Content-Type headers).
 */
package dev.vertique.rest.core.middleware;
