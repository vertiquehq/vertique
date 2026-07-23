// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.util.Objects;

/**
 * Authentication-handler wrapper that arms per-request deferral of credential-rejection events for an
 * OR route (multiple alternative {@code @SecurityRequirement}s composed as a Vert.x
 * {@code ChainAuthHandler.any()}).
 *
 * <p><strong>Why this exists.</strong> On an OR route each failed alternative's scheme handler reports
 * a credential rejection synchronously, before the chain advances to the next alternative. Without
 * deferral, a request that ultimately authenticates via a <em>later</em> alternative still records a
 * spurious rejection from an earlier alternative that failed. Wrapping the OR chain in this handler
 * sets a per-request flag (under {@link #DEFER_CONTEXT_KEY}) that the credential-rejection reporter
 * reads: while the flag is set, rejections are buffered and emitted only if the whole chain ultimately
 * fails (no alternative authenticated). Single-scheme routes are not wrapped, so their rejections
 * continue to emit immediately and unchanged.
 *
 * <p><strong>Why a wrapper {@link AuthenticationHandler} and not a plain handler.</strong> Vert.x
 * refuses to mount a {@code USER}-phase handler before an {@code AUTHENTICATION}-phase handler on a
 * route, so the deferral flag cannot be set by a plain handler placed ahead of the chain. Instead this
 * wrapper is itself an {@link AuthenticationHandler}: Vert.x assigns it the {@code AUTHENTICATION}
 * phase (its {@code instanceof AuthenticationHandler} check), and its {@link #handle(RoutingContext)}
 * sets the flag and then delegates to the wrapped chain. The wrapper is installed directly via
 * {@code route.handler(...)} and is never added to another chain, so it only needs to satisfy the bare
 * {@link AuthenticationHandler} contract — a single {@code handle(RoutingContext)} method.
 */
public final class DeferredCredentialRejectionAuthHandler implements AuthenticationHandler {

    /**
     * Routing-context key under which {@link #handle(RoutingContext)} sets {@link Boolean#TRUE} to arm
     * per-request deferral of credential-rejection emission. The credential-rejection reporter reads
     * this key to decide whether to buffer (OR route) or emit immediately (single-scheme route).
     */
    public static final String DEFER_CONTEXT_KEY = "vertique.security.deferCredentialRejection";

    private final AuthenticationHandler delegate;

    /**
     * Creates a wrapper around the given OR-chain authentication handler.
     *
     * @param delegate the OR chain ({@code ChainAuthHandler.any()}) to delegate authentication to;
     *                 must not be {@code null}
     */
    public DeferredCredentialRejectionAuthHandler(AuthenticationHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Arms per-request credential-rejection deferral by setting {@link #DEFER_CONTEXT_KEY} on the
     * routing context, then delegates request handling to the wrapped OR chain.
     *
     * @param ctx the routing context for the request
     */
    @Override
    public void handle(RoutingContext ctx) {
        ctx.put(DEFER_CONTEXT_KEY, Boolean.TRUE);
        delegate.handle(ctx);
    }
}
