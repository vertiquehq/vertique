// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import java.util.List;

/**
 * Test-only bridge to the route order {@code JaxRsRouteRegistrar} registers. It sits in the
 * registrar's package so a codegen test can read the package-private route entries the registrar
 * creates its Vert.x routes from, for operations described by either engine.
 */
public final class RouteEntriesProbe {

    private RouteEntriesProbe() {}

    /**
     * Returns the routes the given operations register, in registration order, one line each:
     * the HTTP method, the template the route registers with, and the operation id.
     *
     * @param metas the operations, as either engine describes them
     * @return one line per route, most specific first
     */
    public static List<String> registrationOrder(List<ResourceMethodMeta> metas) {
        return JaxRsRouteRegistrar.routeEntries(metas).stream()
                .map(entry -> entry.meta().httpMethod() + " " + entry.template() + " "
                        + entry.meta().operationId())
                .toList();
    }
}
