// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Auto-registered, scoped, ordered request handler.
 *
 * <p>Middlewares are contributed via Dagger {@code Set<Middleware>} multibinding and automatically
 * mounted on the appropriate router based on their {@link #scope()}. They are sorted using the
 * {@link OrderedExtension} contract — phase ascending, then {@link #priority()} ascending, then
 * {@link #orderKey()} ascending — so lower-priority values execute first within a phase. The
 * {@link #priority()} method is declared abstract here (no default) so every implementation must
 * choose an explicit value.
 *
 * <p>Framework-owned middlewares use the {@code SYSTEM_FIRST} phase (e.g.
 * {@link RequestContextLifecycle}) to guarantee they run before all application-phase middlewares
 * regardless of priority.
 */
public interface Middleware extends Handler<RoutingContext>, OrderedExtension {

    /**
     * Fine ordering priority within a phase. Lower values execute first. This method is mandatory —
     * every implementation must override it with an explicit value.
     *
     * @return the priority value for this middleware
     */
    @Override
    int priority();

    /**
     * Scope determines where the middleware is mounted.
     * ROOT = all requests, API = only OpenAPI-validated routes.
     *
     * @return the scope; defaults to {@link MiddlewareScope#ROOT}
     */
    default MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    /**
     * Path pattern for the middleware. Default matches all paths.
     *
     * @return the path pattern; defaults to {@code "/*"}
     */
    default String path() {
        return "/*";
    }
}
