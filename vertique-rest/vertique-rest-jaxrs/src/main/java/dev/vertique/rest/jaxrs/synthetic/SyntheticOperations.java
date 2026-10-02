// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

/**
 * INTERNAL: installs framework-owned synthetic operations through the resource security chain.
 *
 * <p>An installed route runs exactly the chain an equally annotated JAX-RS resource method gets:
 * the scheme's authentication handler, then every registered {@code OperationHandlerContributor} in
 * resource order with the same effective security policy, then the caller's terminal handler. The
 * contributors receive a descriptor that reports the synthetic security annotations the policy is
 * built from, the literal route path as its route template, no media types, and no required action.
 *
 * <p>The route ends every failure itself: a 4xx or 5xx answers with an {@code
 * application/problem+json} body {@code {"type":"about:blank","title":<reason phrase>,"status":<code>}}
 * and {@code Cache-Control: no-store}, and the failure never continues to a later route or mount.
 * This body deliberately bypasses the application's error pipeline.
 *
 * <p>The installed route also bypasses router lifecycle hooks, API-scoped middleware, request
 * interceptors, and mount customizers of JAX-RS mounts: none of those run for a synthetic
 * operation's route.
 *
 * <p>Public only for cross-module use by sibling framework modules (starting with the OpenAPI
 * documentation module); outside the maturity promise and not an application contract.
 */
public interface SyntheticOperations {

    /**
     * Installs one route answering {@code methods} at {@code path} on {@code router}: the
     * scheme's authentication handler, every registered {@code OperationHandlerContributor} in
     * resource order with the effective policy of an equally annotated resource method, then
     * {@code terminal}.
     *
     * <p>Every check runs before the router gains any route, so a rejected operation leaves the
     * router unchanged. A contributor, or Vert.x, rejecting the route while it is built fails the
     * installation the same way, after the partial route has been removed from the router again and
     * the operation id released, so a corrected installation of the same id can succeed. Scheme
     * handlers are configured once per router, on its first installation; an operation id may be
     * installed once per router.
     *
     * <p>The caller is responsible for three preconditions:
     * <ul>
     *   <li>Install before {@code router} serves requests.</li>
     *   <li>Failure handlers are consulted top-down in router order, so a router-wide failure handler
     *       added to {@code router} before this installation runs before the route's own failure
     *       handler, and answers the failure instead if it does not continue.</li>
     *   <li>The route claims only the listed {@code methods}: a request with any other method is not
     *       answered by it and falls through to later routes and mounts. A document route lists both
     *       {@link HttpMethod#GET} and {@link HttpMethod#HEAD}.</li>
     * </ul>
     *
     * @param router    the router to install the route on
     * @param path      the literal route path, also reported as the operation's route template
     * @param methods   the HTTP methods the route answers; the first is the operation's primary
     *                  method; never empty
     * @param operation the synthetic operation describing origin, id, scheme, application, and roles
     * @param terminal  the handler run after authentication and every contributor have passed
     * @throws RestConfigurationException message prefixed by operation.origin(), before any route
     *     exists, for every condition a resource route would fail on; also, with the failure as its
     *     cause, when a contributor or Vert.x rejects the route while it is built, after that route
     *     has been removed again
     * @throws NullPointerException     if any argument, or any element of {@code methods}, is
     *     {@code null}; the message names the argument
     * @throws IllegalArgumentException if {@code methods} is empty; the message names it
     */
    void install(
            Router router,
            String path,
            List<HttpMethod> methods,
            SyntheticOperation operation,
            Handler<RoutingContext> terminal);
}
