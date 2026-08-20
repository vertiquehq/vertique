// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.authz.InvocationOrigin;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Owns the MCP mount's authentication-state admission boundary. */
final class McpIdentityEstablisher {
    private static final InvocationOrigin MCP_ORIGIN = InvocationOrigin.of(DispatchBoundary.MCP);

    private final boolean schemeConfigured;
    private final Handler<RoutingContext> optionalAuthentication;
    private final Handler<RoutingContext> identityResolution;

    McpIdentityEstablisher(
            McpServerConfig config,
            Set<RouteAuthHandler> routeAuthHandlers,
            IdentityResolutionMiddleware identityResolutionMiddleware) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(routeAuthHandlers, "routeAuthHandlers");
        Objects.requireNonNull(identityResolutionMiddleware, "identityResolutionMiddleware");
        this.schemeConfigured = config.enabled() && config.authenticationScheme() != null;
        optionalAuthentication =
                selectOptionalHandler(config, routeAuthHandlers).orElse(context -> context.next());
        identityResolution = identityResolutionMiddleware.handlerFor(MCP_ORIGIN);
    }

    /**
     * Admits the request into identity establishment.
     *
     * <p>When a scheme is configured (§4.7 stage 3), only clean routing authentication state is
     * admitted: a pre-existing {@code RoutingContext.user()} or authentication evidence fails closed
     * before the selected scheme runs. When no scheme is configured, the mount binds an MCP-owned
     * canonical anonymous context <em>without consulting</em> ambient Router user/evidence, so an
     * ambient routing user is not a rejection — it is simply ignored.
     *
     * @param context the request context inspected at the MCP trust boundary
     */
    void admit(RoutingContext context) {
        if (schemeConfigured && !hasCleanAuthenticationState(context)) {
            reject(context);
            return;
        }
        context.next();
    }

    /** Runs the selected optional authentication capability, or advances anonymous requests. */
    void authenticate(RoutingContext context) {
        optionalAuthentication.handle(context);
    }

    /**
     * Verifies that optional authentication either supplied both user and evidence or supplied neither.
     *
     * <p>This prevents a misbehaving optional handler from turning invalid credentials into an
     * anonymous request or from providing an unverified ambient user. The check applies only when a
     * scheme is configured: with no scheme the mount binds canonical anonymous without consulting
     * ambient Router user/evidence, so an ambient user left on the context is intentionally ignored
     * rather than treated as an inconsistent post-authentication state.
     */
    void verifyPostAuthenticationState(RoutingContext context) {
        if (!schemeConfigured) {
            context.next();
            return;
        }
        boolean hasUser = context.user() != null;
        boolean hasEvidence = !RestAuthenticationEvidence.get(context).isEmpty();
        if (hasUser != hasEvidence) {
            reject(context);
            return;
        }
        context.next();
    }

    /** Runs shared identity resolution with the explicit MCP invocation origin. */
    void resolveIdentity(RoutingContext context) {
        identityResolution.handle(context);
    }

    private static Optional<Handler<RoutingContext>> selectOptionalHandler(
            McpServerConfig config, Set<RouteAuthHandler> routeAuthHandlers) {
        if (!config.enabled() || config.authenticationScheme() == null) {
            return Optional.empty();
        }
        List<RouteAuthHandler> matches = routeAuthHandlers.stream()
                .filter(handler -> config.authenticationScheme().equals(handler.schemeName()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("MCP authentication scheme must select exactly one route auth handler");
        }
        Optional<Handler<RoutingContext>> handler = matches.getFirst().createOptionalHandler();
        if (handler == null || handler.isEmpty()) {
            throw new IllegalStateException("MCP authentication scheme does not support optional authentication");
        }
        return handler;
    }

    private static boolean hasCleanAuthenticationState(RoutingContext context) {
        return context.user() == null && RestAuthenticationEvidence.get(context).isEmpty();
    }

    private static void reject(RoutingContext context) {
        McpRequestDispatcher.completeAuthenticationRejection(context);
    }
}
