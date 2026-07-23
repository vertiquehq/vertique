// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import io.vertx.ext.web.RoutingContext;
import java.util.Map;

/**
 * ROOT-scoped middleware that enriches MDC with per-request HTTP bookkeeping
 * ({@code method} and {@code path}).
 *
 * <p>Since the correlation refactor (PR1), request-id resolution, the {@code X-Request-Id}
 * response header, and the {@code requestId} MDC entry are owned by
 * {@code CorrelationIngressMiddleware} in {@code vertique-rest-core/correlation}. This middleware
 * no longer reads or writes any of those values; it focuses on the {@code method} and
 * {@code path} MDC keys which remain useful even outside the correlation feature.
 *
 * <p>MDC cleanup is owned by {@link RequestContextLifecycle}: this middleware calls
 * {@link RequestContextLifecycle.Handle#bindMdc(Map)} so the bound keys are restored to their
 * pre-request values when the lifecycle closes.
 *
 * <p>Order: {@code 0} — runs after {@link RequestContextLifecycle} ({@link Integer#MIN_VALUE})
 * and after {@code CorrelationIngressMiddleware} ({@link RequestContextLifecycle#ORDER} + 10,
 * which is &lt; 0), so the lifecycle handle and the correlation binding are both available when
 * this middleware fires.
 */
public class ContextualLoggingMiddleware implements Middleware {

    /**
     * Execution priority. Must be greater than {@link RequestContextLifecycle#ORDER} so the
     * lifecycle handle is available; {@code CorrelationIngressMiddleware} also runs before this
     * middleware, so the live correlation context is bound for any handlers downstream.
     */
    public static final int ORDER = 0;

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@link #ORDER} ({@code 0}) — APPLICATION phase, after correlation ingress
     */
    @Override
    public int priority() {
        return ORDER;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    /**
     * Binds HTTP method and path into MDC for the request lifetime via the lifecycle handle.
     *
     * @param ctx the current routing context; must not be {@code null}
     */
    @Override
    public void handle(RoutingContext ctx) {
        RequestContextLifecycle.fromRoutingContext(ctx)
                .bindMdc(Map.of(
                        MdcKeys.METHOD, ctx.request().method().name(),
                        MdcKeys.PATH, ctx.request().path()));

        ctx.next();
    }
}
