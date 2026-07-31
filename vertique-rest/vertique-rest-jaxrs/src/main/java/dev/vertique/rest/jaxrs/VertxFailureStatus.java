// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

/**
 * Internal handoff between the router-level failure handler and the error pipeline: the status the
 * Vert.x layer authoritatively decided for a failure, carried on the routing context so the mapping
 * step can reconcile it with the mapper's own status.
 *
 * <p>Package-private on purpose. Only {@link JaxRsRouterMount} writes the key and only
 * {@link ErrorPipeline} reads it; it is not part of any published extension contract.
 */
final class VertxFailureStatus {

    /**
     * Routing-context data key carrying the terminally observed authoritative Vert.x failure status:
     * either an unwrapped {@link io.vertx.ext.web.handler.HttpException}'s status, or a 4xx the
     * Vert.x layer set alongside a non-{@code HttpException} cause. A non-{@code HttpException} 5xx
     * is deliberately NOT carried — {@code RoutingContext.fail(Throwable)} synthesises a 500 that
     * cannot be distinguished from a deliberate {@code fail(500, cause)}.
     *
     * <p>Written only in the terminal router-level failure handler, so no reroute can observe it.
     */
    static final String KEY = "dev.vertique.rest.vertxStatusCode";

    private VertxFailureStatus() {}
}
