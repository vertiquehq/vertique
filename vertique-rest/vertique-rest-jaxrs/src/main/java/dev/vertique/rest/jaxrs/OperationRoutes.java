// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import io.vertx.core.Handler;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Objects;

/**
 * The Vert.x routes one operation is served by, driven as one. A template without an alternation has
 * exactly one route; an operation whose constraint has alternatives that rank differently has one route
 * for each ranked alternative (see {@link RoutePathSpecificity#rankedVariants}). Every handler added
 * through this class is appended, as the same instance and in the same order, to every route, so each
 * route serves an identical chain.
 */
final class OperationRoutes {

    private final List<Route> routes;

    /**
     * Wraps the routes of one operation.
     *
     * @param routes the operation's routes; must not be empty
     */
    OperationRoutes(List<Route> routes) {
        Objects.requireNonNull(routes, "routes");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("an operation needs at least one route");
        }
        this.routes = List.copyOf(routes);
    }

    /**
     * Wraps a single route.
     *
     * @param route the operation's only route
     * @return the wrapper
     */
    static OperationRoutes of(Route route) {
        return new OperationRoutes(List.of(Objects.requireNonNull(route, "route")));
    }

    /**
     * Appends a request handler to every route.
     *
     * @param handler the handler, shared by all routes
     */
    void handler(Handler<RoutingContext> handler) {
        for (Route route : routes) {
            route.handler(handler);
        }
    }

    /**
     * Sets the failure handler on every route.
     *
     * @param handler the failure handler, shared by all routes
     */
    void failureHandler(Handler<RoutingContext> handler) {
        for (Route route : routes) {
            route.failureHandler(handler);
        }
    }
}
