// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OperationHandlerContributor} that adds Vert.x authorization handlers to OpenAPI routes
 * based on the resolved {@link SecurityPolicy}.
 *
 * <p>Delegates authorization handler construction to {@link SecurityPolicyEnforcer} so the same
 * enforcement logic can be reused by other transports (e.g. WebSocket). Pattern-matches on the
 * sealed {@link SecurityPolicy} variants to build the appropriate handler:
 *
 * <ul>
 *   <li>{@link SecurityPolicy.DenyAll} — handler that always fails with 403
 *   <li>{@link SecurityPolicy.PermitAll} — no authorization handler (OpenAPI security still
 *       applies)
 *   <li>{@link SecurityPolicy.AuthenticatedOnly} — handler that requires a non-anonymous resolved
 *       {@code SecurityContext} (via {@code SecurityRuntime.current()}), not {@code ctx.user()}
 *   <li>{@link SecurityPolicy.Constrained} — role/scope enforcement delegated to the
 *       {@link AuthorizationDecisionPoint}, which evaluates the actor's {@code AuthorizationClaims};
 *       {@link SecurityPolicyEnforcer} emits one decision event per attempt (ADR-0114)
 *   <li>{@link SecurityPolicy.None} — no handler added
 * </ul>
 *
 * <p>Priority: 100 (runs after identity resolution at 80, which binds the
 * {@code SecurityContext} this contributor's handlers evaluate).
 */
@Slf4j
@Singleton
public class AuthorizationContributor implements OperationHandlerContributor {

    private final SecurityPolicyEnforcer enforcer;

    /**
     * Creates a new authorization contributor backed by the given enforcer.
     *
     * @param enforcer the enforcer that creates authorization handlers from security policies
     */
    @Inject
    public AuthorizationContributor(SecurityPolicyEnforcer enforcer) {
        this.enforcer = enforcer;
    }

    @Override
    public int priority() {
        return 100;
    }

    /**
     * Adds the appropriate authorization handler to the route based on the operation's security
     * policy AND-composed with any {@code @RequiresAction} gate. Delegates handler creation to
     * {@link SecurityPolicyEnforcer#createHandler(SecurityPolicy, java.util.Optional)}, passing the
     * resolved {@link OperationRegistrationContext#requiredAction()} so an action-only route
     * ({@link SecurityPolicy.None} with a present action) is enforced and a role/scope route composes
     * the action gate into the same single decision event (ADR-0113 / ADR-0114). The handler is added
     * only when the enforcer returns a non-{@code null} result.
     *
     * @param context the operation registration context carrying route, operation metadata, and the
     *                resolved {@code @RequiresAction} gate
     */
    @Override
    public void contribute(OperationRegistrationContext context) {
        SecurityPolicy policy = context.securityPolicy();
        Handler<RoutingContext> handler = enforcer.createHandler(policy, context.requiredAction());
        if (handler != null) {
            log.debug(
                    "operationId={}: Adding authorization handler for {} (requiredAction={})",
                    context.operationId(),
                    policy.getClass().getSimpleName(),
                    context.requiredAction().map(a -> a.value()).orElse("none"));
            context.route().addHandler(handler);
        } else {
            log.debug(
                    "operationId={}: No authorization handler for {}",
                    context.operationId(),
                    policy.getClass().getSimpleName());
        }
    }
}
