// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import io.vertx.ext.web.RoutingContext;

/** An {@code API}-scoped middleware that passes every request on unchanged. */
public final class ApiAllowlistMiddleware implements Middleware {

    /** Creates the middleware. */
    public ApiAllowlistMiddleware() {}

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.API;
    }

    @Override
    public void handle(RoutingContext ctx) {
        ctx.next();
    }
}
