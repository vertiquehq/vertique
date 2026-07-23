// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * Synchronous authorization policy SPI for pure-Java policies.
 *
 * <p>Implementations define authorization rules using in-memory logic, expression evaluation,
 * or static configuration. A policy receives a fully-populated {@link AuthorizationRequest} and
 * returns an {@link AuthorizationDecision} synchronously.
 *
 * <p>Apps that need async I/O during a decision (e.g., database lookups, remote PDP calls) should
 * bind a REST-layer {@code AuthorizationDecisionPoint} instead; the REST adapter wraps sync
 * policies into the async pipeline automatically.
 *
 * <p>This interface is a functional interface and may be implemented as a lambda:
 * <pre>{@code
 * AuthorizationPolicy adminOnly = req ->
 *     req.securityContext().hasRole("admin")
 *         ? AuthorizationDecision.permit("ADMIN_ROLE_PRESENT")
 *         : AuthorizationDecision.deny("ADMIN_ROLE_MISSING");
 * }</pre>
 */
@FunctionalInterface
public interface AuthorizationPolicy {

    /**
     * Evaluates the given authorization request and returns a decision.
     *
     * @param request the authorization request containing the security context, action, and resource;
     *                must not be {@code null}
     * @return an {@link AuthorizationDecision} — never {@code null}
     */
    AuthorizationDecision decide(AuthorizationRequest request);
}
