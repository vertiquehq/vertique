// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Objects;

/**
 * Plain-{@link Router} implementation of the neutral {@link RouteRegistration} SPI handed to
 * {@link dev.vertique.rest.core.router.OperationHandlerContributor}s during per-operation route
 * registration.
 *
 * <p>It wraps the Vert.x routes created for the operation and exposes the operation's
 * transport-neutral {@link RestOperationDescriptor}. Contributors call {@link #addHandler} to append
 * handlers to every route of the operation; handlers run in registration order, after the validation gate (if any) and
 * before the terminal {@link ResourceMethodInvoker}. This replaces the transitional
 * {@code OpenApiRouteRegistration} adapter now that routing runs on a plain {@link Router}.
 */
final class PlainRouteRegistration implements RouteRegistration {

    private final OperationRoutes routes;
    private final RestOperationDescriptor operation;

    /**
     * Creates a registration wrapping the given Vert.x routes and exposing the given neutral descriptor.
     *
     * @param routes    the operation's routes, each of which receives every handler; must not be
     *                  {@code null}
     * @param operation the transport-neutral descriptor for this operation; must not be {@code null}
     */
    PlainRouteRegistration(OperationRoutes routes, RestOperationDescriptor operation) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public RouteRegistration addHandler(Handler<RoutingContext> handler) {
        routes.handler(handler);
        return this;
    }

    @Override
    public RestOperationDescriptor operation() {
        return operation;
    }
}
