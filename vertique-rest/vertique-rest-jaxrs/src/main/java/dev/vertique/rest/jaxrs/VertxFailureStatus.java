// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import io.vertx.ext.web.RoutingContext;

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
     * {@link io.vertx.ext.web.RoutingContext#data()} when the mapping step begins, before
     * {@link RestExceptionMapper#translate} runs, whichever mapper would produce the response. The
     * removal does not depend on the status fallback firing or on translate succeeding: it happens
     * equally when a specific application mapper outranks the hint, when the fallback never runs, and
     * when a translator throws. A reroute raised later on the same context therefore finds no stale
     * status to be steered by. It remains readable to {@code ErrorInterceptor.beforeMapping}, which
     * runs ahead of the mapping step.
     */
    static final String KEY = "dev.vertique.rest.jaxrs.failureStatus";

    /**
     * First-seen {@link RoutingContext#failure()} reference for the current failure chain, recorded by
     * {@link #observeFailurePair} so a later {@code fail(int)} that rewrites {@code statusCode} without
     * replacing the cause can be detected. Package-private; not a published contract.
     */
    static final String OBSERVED_FAILURE_KEY = KEY + ".observedFailure";

    /**
     * First-seen {@link RoutingContext#statusCode()} that accompanied {@link #OBSERVED_FAILURE_KEY}.
     * When {@code fail(int)} rewrites the status for the same failure reference, this original status
     * is kept so the terminal handler does not stash the rewritten one. Package-private; not a
     * published contract.
     */
    static final String OBSERVED_STATUS_KEY = KEY + ".observedStatus";

    private VertxFailureStatus() {}

    /**
     * Records the {@code (failure, statusCode)} pair first observed on this failure chain, using only
     * the public {@link RoutingContext} API.
     *
     * <p>Vert.x {@code RoutingContext.fail(int)} sets {@code statusCode} without clearing
     * {@code failure}, then restarts the failure chain. A caller that does
     * {@code fail(500, serverError)} followed by {@code fail(404)} would otherwise reach the terminal
     * handler as {@code statusCode = 404} with {@code failure = serverError}, and the 4xx stash branch
     * would render a genuine server error as a 404. This method keeps the status that accompanied the
     * failure when the same failure reference is seen again with a different status.
     *
     * <p>Assumption recorded here: status and cause always originate from the same {@code fail(...)}
     * call. A rewrite that happens entirely upstream of the first observation (a route-level failure
     * handler that calls {@code fail(int)} before {@code next()} reaches this observer) is still
     * invisible — the framework itself never double-fails.
     *
     * @param ctx the failing routing context
     */
    static void observeFailurePair(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        int status = ctx.statusCode();
        if (!ctx.data().containsKey(OBSERVED_STATUS_KEY)) {
            ctx.data().put(OBSERVED_FAILURE_KEY, failure);
            ctx.data().put(OBSERVED_STATUS_KEY, status);
            return;
        }
        Object observedFailure = ctx.data().get(OBSERVED_FAILURE_KEY);
        int observedStatus = (Integer) ctx.data().get(OBSERVED_STATUS_KEY);
        if (observedFailure == failure && observedStatus != status) {
            // fail(int) rewrote status without replacing failure — keep the original pair.
            return;
        }
        if (observedFailure != failure) {
            // A new fail(status, throwable) / fail(Throwable) — refresh the authoritative pair.
            ctx.data().put(OBSERVED_FAILURE_KEY, failure);
            ctx.data().put(OBSERVED_STATUS_KEY, status);
        }
    }

    /**
     * Returns the 4xx status that should be stashed as the Vert.x failure-status hint for a
     * non-{@code HttpException} cause, or {@code null} when none should be recorded.
     *
     * <p>Prefers the status first observed alongside the current {@link RoutingContext#failure()} when
     * {@link #observeFailurePair} saw the pair before a {@code fail(int)} rewrite. Falls back to
     * {@link RoutingContext#statusCode()} when observation agrees or was never needed. A 5xx is never
     * returned — the same exclusion as the terminal stash branch.
     *
     * @param ctx the failing routing context
     * @return a 4xx to stash, or {@code null}
     */
    static Integer clientErrorStatusForCause(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        Object observedStatus = ctx.data().get(OBSERVED_STATUS_KEY);
        if (ctx.data().get(OBSERVED_FAILURE_KEY) == failure && observedStatus instanceof Integer status) {
            return status >= 400 && status < 500 ? status : null;
        }
        int status = ctx.statusCode();
        return status >= 400 && status < 500 ? status : null;
    }
}
