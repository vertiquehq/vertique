// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OperationHandlerContributor} that installs a {@link RouteAuthHandler} on an
 * <em>action-only</em> JAX-RS route — one whose resolved {@link SecurityPolicy} is
 * {@link SecurityPolicy.None} but which carries a {@code @RequiresAction} gate — so the caller is
 * authenticated before the action gate evaluates the identity.
 *
 * <h3>Why this contributor exists (silent-bypass fix)</h3>
 * REST authentication handlers are <strong>OpenAPI-security-scheme-driven</strong>: a
 * {@link dev.vertique.rest.core.security.SecuritySchemeHandler} installs the auth handler via
 * {@code RouterBuilder.security(scheme).httpHandler(...)}, so the handler runs only for a route that
 * declares an OpenAPI security requirement. An action-only route ({@code @RequiresAction} with no
 * Jakarta role/scope policy) declares <em>no</em> OpenAPI security requirement, so without this
 * contributor it would get <em>no</em> auth handler: no {@link dev.vertique.security.AuthenticationEvidence}
 * is appended, {@link IdentityResolutionMiddleware} resolves an anonymous identity, and the action
 * gate ({@link AuthorizationContributor} → {@link SecurityPolicyEnforcer}) would evaluate that
 * anonymous identity rather than the real caller — a valid bearer token would be silently ignored.
 *
 * <p>This contributor closes that gap by mirroring the WebSocket transport, where
 * {@code WebSocketEndpointRegistrar.installAuthenticationHandler} treats a present
 * {@code @RequiresAction} as also requiring authentication. Conceptually, {@code @RequiresAction}
 * implies "authentication required".
 *
 * <h3>Scope: only the {@code None} + action gap</h3>
 * The handler is installed <strong>only</strong> when the policy is {@link SecurityPolicy.None} and a
 * {@code @RequiresAction} is present. For {@link SecurityPolicy.AuthenticatedOnly} and
 * {@link SecurityPolicy.Constrained} the route declares an OpenAPI security requirement, so the
 * OpenAPI-driven {@code SecuritySchemeHandler} already installs the auth handler; adding a second one
 * here would double-authenticate. {@link SecurityPolicy.PermitAll}/{@link SecurityPolicy.DenyAll}
 * cannot occur with {@code @RequiresAction} (rejected at startup and compile time).
 *
 * <h3>Ordering</h3>
 * This contributor reports {@link #authenticationStage()} {@code true}, so the router installs its
 * handler right after the route's (absent) scheme authentication handlers and <em>before</em> the
 * {@code @Consumes} check and request validation. An unauthenticated caller of an action-only route is
 * therefore rejected with {@code 401} (and the scheme's {@code WWW-Authenticate} challenge) before any
 * request content is examined, exactly as on a route that declares a security scheme. Authorization
 * still runs after validation.
 *
 * <p>Within the authentication stage contributors run in priority order, so {@value #PRIORITY} places
 * this one before the JWT claims validator ({@code 50}, which also reports {@code true} and skips when
 * {@code ctx.user() == null}): were authentication to run after it, a custom
 * {@code JwtClaimsValidator} (tenant binding, token version/revocation, a required custom claim) would
 * be silently bypassed and a token it would reject let through. Identity resolution ({@link
 * IdentityResolutionContributor#PRIORITY}, 80) and authorization (100) run later, with the other
 * contributors, so the full chain is authentication → claims-validation → gates →
 * identity-resolution → authorization. {@code 40} leaves headroom below {@code 50} for an application
 * authentication-stage contributor to slot between authentication and claims validation.
 *
 * <h3>Fail-closed</h3>
 * When an action-only route needs authentication but no (or multiple ambiguous)
 * {@link RouteAuthHandler} bindings exist, this contributor throws {@link IllegalStateException} at
 * startup rather than registering a route that cannot authenticate (which would silently fall through
 * to an anonymous identity at the action gate). This complements the startup validation in
 * {@code JaxRsRouteRegistrar}, which already fails closed when the authz engine or the auth
 * enforcement runtime is absent.
 *
 * @see IdentityResolutionContributor
 * @see AuthorizationContributor
 * @see RouteAuthHandler
 */
@Slf4j
@Singleton
public final class ActionGateAuthenticationContributor implements OperationHandlerContributor {

    /**
     * Handler priority within the authentication stage. Before the JWT claims validator (priority
     * {@code 50}) so that on an action-only route authentication appends evidence and sets the user
     * <em>before</em> the claims check runs — the authentication → claims-validation order that a
     * normal authenticated route gets from Vert.x's OpenAPI security handler. {@code 40} keeps
     * headroom below {@code 50} for an application contributor to interpose if required.
     */
    public static final int PRIORITY = 40;

    private final Set<RouteAuthHandler> routeAuthHandlers;

    /**
     * Creates a new {@code ActionGateAuthenticationContributor}.
     *
     * @param routeAuthHandlers the set of route-level authentication handlers contributed via Dagger
     *                          multibinding; must not be {@code null}
     */
    @Inject
    public ActionGateAuthenticationContributor(Set<RouteAuthHandler> routeAuthHandlers) {
        this.routeAuthHandlers = Objects.requireNonNull(routeAuthHandlers, "routeAuthHandlers");
    }

    /**
     * Runs in the authentication stage, ahead of the request gates.
     *
     * @return {@code true}
     */
    @Override
    public boolean authenticationStage() {
        return true;
    }

    /**
     * Returns the priority for this contributor.
     *
     * @return {@value #PRIORITY}
     */
    @Override
    public int priority() {
        return PRIORITY;
    }

    /**
     * Installs the selected {@link RouteAuthHandler} on the route when it is an action-only route
     * ({@link SecurityPolicy.None} with a present {@code @RequiresAction}). Otherwise does nothing —
     * either the route has no action gate, or its policy already drives an OpenAPI security handler.
     *
     * @param context the operation registration context carrying the route, policy, and resolved
     *                {@code @RequiresAction}; must not be {@code null}
     * @throws IllegalStateException if the route needs authentication but no single
     *     {@link RouteAuthHandler} can be selected (none registered, or multiple without a selector)
     */
    @Override
    public void contribute(OperationRegistrationContext context) {
        if (!(context.securityPolicy() instanceof SecurityPolicy.None)
                || context.requiredAction().isEmpty()) {
            return;
        }

        RouteAuthHandler authHandler = selectAuthHandler(context.operationId());
        log.debug(
                "operationId={}: Adding action-gate authentication handler (scheme={}, requiredAction={})",
                context.operationId(),
                authHandler.schemeName(),
                context.requiredAction().map(a -> a.value()).orElse("none"));
        context.route().addHandler(authHandler.createHandler());
    }

    /**
     * Selects the single {@link RouteAuthHandler} to authenticate an action-only route. Mirrors the
     * WebSocket transport's selection: exactly one handler is required. With none registered, or more
     * than one (no per-route scheme selector exists for JAX-RS), startup fails closed rather than
     * leaving the action gate to evaluate an anonymous identity.
     *
     * @param operationId the operationId, used in the failure message
     * @return the single registered {@link RouteAuthHandler}; never {@code null}
     * @throws IllegalStateException if no handler, or more than one handler, is registered
     */
    private RouteAuthHandler selectAuthHandler(String operationId) {
        if (routeAuthHandlers.isEmpty()) {
            throw new IllegalStateException("Operation '" + operationId
                    + "' is an action-only @RequiresAction route requiring authentication, but no RouteAuthHandler"
                    + " is registered. Include an authentication module (e.g. JwtAuthModule) that contributes one.");
        }
        if (routeAuthHandlers.size() == 1) {
            return routeAuthHandlers.iterator().next();
        }
        List<String> schemes = routeAuthHandlers.stream()
                .map(RouteAuthHandler::schemeName)
                .sorted()
                .toList();
        throw new IllegalStateException("Operation '" + operationId
                + "' is an action-only @RequiresAction route requiring authentication, but multiple RouteAuthHandler"
                + " bindings are registered " + schemes
                + " and JAX-RS routes have no per-route scheme selector to disambiguate them.");
    }
}
