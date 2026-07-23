// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * ROOT-scoped {@link Middleware} that captures {@link RequestOrigin} pre-authentication for every
 * inbound request.
 *
 * <p>Runs at {@link #ORDER} ({@link CorrelationIngressMiddleware#ORDER} + 10), which means:
 * <ul>
 *   <li>After {@link CorrelationIngressMiddleware} — so the {@link
 *       dev.vertique.core.correlation.CorrelationContext} is already bound on the context when
 *       origin capture runs (FR-ID-CO-001).</li>
 *   <li>Before any authentication handler — so the captured origin is available to
 *       {@code CredentialRejectionReporter} on auth failures (AC-SE-3) and to
 *       {@code IdentityResolutionMiddleware} on success (AC-CO-1).</li>
 * </ul>
 *
 * <p>The captured {@link RequestOrigin} is stashed on the routing context under the well-known
 * key {@code RequestOrigin.class.getName()}. The downstream
 * {@link IdentityResolutionMiddleware} reads it and incorporates it into the resolved
 * {@link dev.vertique.security.SecurityContext}.
 *
 * <p>Contributed to the Dagger {@code Set<Middleware>} multibinding via
 * {@link AuthModule#originCaptureMiddleware(OriginCaptureMiddleware)}.
 */
@Singleton
public final class OriginCaptureMiddleware implements Middleware {

    /**
     * Execution order: {@link CorrelationIngressMiddleware#ORDER} + 10.
     *
     * <p>Runs immediately after correlation ingress so both the correlation context and the
     * request origin are bound before authentication handlers execute.
     */
    public static final int ORDER = CorrelationIngressMiddleware.ORDER + 10;

    private final RequestOriginCapturer capturer;

    /**
     * Creates a new {@link OriginCaptureMiddleware}.
     *
     * @param capturer the trusted-proxy-aware origin capturer; must not be {@code null}
     */
    @Inject
    public OriginCaptureMiddleware(RequestOriginCapturer capturer) {
        this.capturer = Objects.requireNonNull(capturer, "capturer");
    }

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@link #ORDER} ({@link CorrelationIngressMiddleware#ORDER} + 10)
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
     * Captures the {@link RequestOrigin} from the inbound request and stashes it on the routing
     * context under {@code RequestOrigin.class.getName()}, then calls {@code ctx.next()} to
     * continue the handler chain.
     *
     * @param ctx the current routing context; never {@code null}
     */
    @Override
    public void handle(RoutingContext ctx) {
        RequestOrigin origin = capturer.capture(ctx.request());
        ctx.put(RequestOrigin.class.getName(), origin);
        ctx.next();
    }
}
