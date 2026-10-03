// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.fallthrough;

import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import io.vertx.ext.web.RoutingContext;

/**
 * A {@link Middleware} at the default path that counts every request it sees into the counter of its
 * scope and passes the request on.
 */
public final class CountingMiddleware implements Middleware {

    private final MiddlewareScope scope;
    private final FallThroughCounters counters;

    /**
     * Creates a counting middleware.
     *
     * @param scope    the middleware's scope, which also selects its counter
     * @param counters the counters to increment
     */
    public CountingMiddleware(MiddlewareScope scope, FallThroughCounters counters) {
        this.scope = scope;
        this.counters = counters;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public MiddlewareScope scope() {
        return scope;
    }

    @Override
    public String orderKey() {
        return CountingMiddleware.class.getName() + ":" + scope;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (scope == MiddlewareScope.API) {
            counters.countApiMiddleware();
        } else {
            counters.countRootMiddleware();
        }
        ctx.next();
    }
}
