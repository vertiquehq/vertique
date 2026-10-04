// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.ChainAuthHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.impl.ChainAuthHandlerImpl;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>OR-composition {@link ChainAuthHandler} that always emits member {@code WWW-Authenticate}
 * challenges on a final 401 — including when the request carries
 * {@code X-Requested-With: XMLHttpRequest}.
 *
 * <p>vertx-web 5.1.x {@code AuthenticationHandlerImpl.processException} deliberately skips
 * {@link #setAuthenticateHeader} for XHR so browsers do not pop a credential dialog. RFC 9110
 * §11.6.1 still requires the challenge on that 401, and Vertique's multi-scheme OR routes must
 * surface every alternative's challenge (Bearer realms, Basic, …) so clients can discover
 * acceptable credentials. This subclass overrides the protected hook: on 401 it walks members via
 * {@link ChainAuthHandlerImpl#setAuthenticateHeader} then {@code ctx.fail(401, exception)}, and
 * never calls {@code super} for that status (which would skip XHR and, on a normal browser
 * request, append the challenges a second time).
 *
 * <p>Non-401 failures stay on {@code super}. A successful alternative never enters this method, so
 * a 200 cannot carry a failed member's challenge. Claims / application 401s after a member has
 * already authenticated also do not enter this method — those writers emit their own challenge.
 */
public final class AuthenticationOrChain extends ChainAuthHandlerImpl {

    /**
     * Creates an empty OR chain ({@code all = false}).
     *
     * @return a new chain; add members with {@link #add}
     */
    public static ChainAuthHandler any() {
        return new AuthenticationOrChain();
    }

    private AuthenticationOrChain() {
        super(false);
    }

    /**
     * On a 401, emits every member's authenticate header (XHR included) and fails the context.
     * Other statuses delegate to the Vert.x default.
     *
     * @param ctx       the routing context for the rejected request
     * @param exception the failure that ended the chain; may be {@code null}
     */
    @Override
    protected void processException(RoutingContext ctx, Throwable exception) {
        if (exception instanceof HttpException httpException && httpException.getStatusCode() == 401) {
            setAuthenticateHeader(ctx);
            ctx.fail(401, exception);
            return;
        }
        super.processException(ctx, exception);
    }
}
