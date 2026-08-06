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
     * <p>Written only in the terminal router-level failure handler and <em>consumed</em> by
     * {@link ErrorPipeline#mapToResponse} — read and removed from
     * {@link io.vertx.ext.web.RoutingContext#data()} exactly once as the failure is mapped, whichever
     * mapper produces the response. The removal does not depend on the status fallback firing: it
     * happens equally when a specific application mapper outranks the hint and the fallback never runs.
     * The hint therefore cannot outlive the mapping of the failure that produced it — a reroute raised
     * later on the same context finds no stale status to be steered by. It remains readable to
     * {@code ErrorInterceptor.beforeMapping}, which runs ahead of the mapping step.
     *
     * <p>One gap, pre-dating this key and tracked separately: a {@code RestExceptionMapper} translator
     * that <em>throws</em> skips the mapping step altogether, so nothing is consumed. Reaching a stale
     * read additionally requires a reroute out of the error chain, since the ordinary path terminates
     * the request with a bare 500.
     */
    static final String KEY = "dev.vertique.rest.jaxrs.failureStatus";

    private VertxFailureStatus() {}
}
