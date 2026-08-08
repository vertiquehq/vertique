// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import io.vertx.core.Future;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link AuthorizationDecisionPoint} that evaluates authorization directly against the
 * {@link AuthorizationClaims} held by the request's {@link dev.vertique.security.SecurityContext}.
 *
 * <p>This implementation preserves the v1 role/scope evaluation semantics previously handled by
 * Vert.x {@link io.vertx.ext.auth.authorization.AuthorizationHandler}:
 *
 * <ul>
 *   <li><strong>Roles</strong> — checked against {@link AuthorityKind#ROLE} claims with OR semantics:
 *       any one of the required roles is sufficient. The policy requirements are encoded in the
 *       {@link AuthorizationRequest#context()} map under the keys {@value #CTX_REQUIRED_ROLES},
 *       {@value #CTX_REQUIRED_SCOPES}, and {@value #CTX_REQUIRE_ALL_SCOPES}.</li>
 *   <li><strong>Scopes</strong> — checked against {@link AuthorityKind#SCOPE} claims with AND or OR
 *       semantics controlled by {@value #CTX_REQUIRE_ALL_SCOPES}.</li>
 *   <li><strong>Composite AND</strong> — when both roles and scopes are present the caller must
 *       satisfy both, mirroring the previous {@link io.vertx.ext.auth.authorization.AndAuthorization}
 *       composite.</li>
 * </ul>
 *
 * <p>The injected {@link io.vertx.ext.auth.authorization.AuthorizationProvider} set is superseded by
 * the identity-resolution import: when the application includes {@link VertxAuthorizationImportModule},
 * {@link IdentityResolutionMiddleware} runs the provider chain once per authenticated request and
 * merges the grants into the {@code AuthorizationClaims} this class evaluates. The field is retained
 * only for constructor compatibility and is never read; its removal is tracked as a next-major
 * cleanup. This implementation therefore evaluates decisions directly from
 * {@code AuthorizationClaims} — there is no async I/O in the happy path.
 *
 * <p>This is a <strong>pure evaluator</strong>: {@link #decide(AuthorizationRequest)} returns the
 * {@link AuthorizationDecision} and emits nothing. The enforcement layer
 * ({@link SecurityPolicyEnforcer}) emits exactly one
 * {@link dev.vertique.security.events.AuthorizationDecisionEvent} per authorization attempt
 * (ADR-0114).
 *
 * @see AuthorizationDecisionPoint
 * @see SyncPolicyDecisionPoint
 */
@Slf4j
@Singleton
public final class VertxProviderDecisionPoint implements AuthorizationDecisionPoint {

    // --- Context map keys for policy requirements ---

    /**
     * Key in {@link AuthorizationRequest#context()} carrying {@code List<String>} of required roles.
     * An absent or empty value means no role constraint.
     */
    static final String CTX_REQUIRED_ROLES = "requiredRoles";

    /**
     * Key in {@link AuthorizationRequest#context()} carrying {@code List<String>} of required scopes.
     * An absent or empty value means no scope constraint.
     */
    static final String CTX_REQUIRED_SCOPES = "requiredScopes";

    /**
     * Key in {@link AuthorizationRequest#context()} carrying {@code Boolean} — {@code true} to
     * require ALL listed scopes (AND semantics), {@code false} for any one (OR semantics).
     */
    static final String CTX_REQUIRE_ALL_SCOPES = "requireAllScopes";

    // --- Reason codes ---

    /** Reason code emitted when all required authorization constraints are satisfied. */
    static final String REASON_PERMITTED = "PERMITTED";

    /** Reason code emitted when the caller lacks at least one required role. */
    static final String REASON_ROLE_MISSING = "ROLE_MISSING";

    /** Reason code emitted when the caller lacks at least one required scope (AND mode). */
    static final String REASON_SCOPE_MISSING = "SCOPE_MISSING";

    /** Reason code emitted when the caller lacks any of the required scopes (OR mode). */
    static final String REASON_SCOPE_INSUFFICIENT = "SCOPE_INSUFFICIENT";

    // --- Fields ---

    private final Set<AuthorizationProvider> providers;

    /**
     * Creates a new decision point.
     *
     * @param providers set of Vert.x authorization providers; superseded by the opt-in
     *                  identity-resolution import ({@link VertxAuthorizationImportModule}) and never
     *                  read here — retained for constructor compatibility, with removal tracked as a
     *                  next-major cleanup; must not be {@code null}
     */
    @Inject
    public VertxProviderDecisionPoint(Set<AuthorizationProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    // --- AuthorizationDecisionPoint ---

    /**
     * Evaluates authorization against the role/scope claims in the request's {@link dev.vertique.security.SecurityContext}.
     *
     * <p>The evaluation steps:
     * <ol>
     *   <li>Extract required roles and scopes from {@link AuthorizationRequest#context()}.</li>
     *   <li>Check roles (OR semantics) against {@link AuthorityKind#ROLE} claims.</li>
     *   <li>Check scopes (AND or OR semantics per {@value #CTX_REQUIRE_ALL_SCOPES}) against
     *       {@link AuthorityKind#SCOPE} claims.</li>
     *   <li>Combine: if both role and scope constraints are present both must be satisfied.</li>
     * </ol>
     *
     * <p>Pure evaluator: this method emits no event. The enforcement layer
     * ({@link SecurityPolicyEnforcer}) owns emission (ADR-0114).
     *
     * @param request the authorization request; must not be {@code null}
     * @return a {@link Future} completing with the authorization decision; never fails
     */
    @Override
    public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        return Future.succeededFuture(evaluate(request));
    }

    // --- Private helpers ---

    /**
     * Evaluates the authorization decision synchronously against the security context's claims.
     *
     * @param request the authorization request
     * @return the resulting authorization decision
     */
    @SuppressWarnings("unchecked")
    private AuthorizationDecision evaluate(AuthorizationRequest request) {
        AuthorizationClaims authorizationClaims = request.securityContext().authorization();
        java.util.Map<String, Object> ctx = request.context();

        java.util.List<String> requiredRoles = ctx.containsKey(CTX_REQUIRED_ROLES)
                ? (java.util.List<String>) ctx.get(CTX_REQUIRED_ROLES)
                : java.util.List.of();

        java.util.List<String> requiredScopes = ctx.containsKey(CTX_REQUIRED_SCOPES)
                ? (java.util.List<String>) ctx.get(CTX_REQUIRED_SCOPES)
                : java.util.List.of();

        boolean requireAllScopes =
                ctx.containsKey(CTX_REQUIRE_ALL_SCOPES) && Boolean.TRUE.equals(ctx.get(CTX_REQUIRE_ALL_SCOPES));

        // Role check (OR semantics: any one role is sufficient)
        if (!requiredRoles.isEmpty()) {
            Set<String> grantedRoles = authorizationClaims.valuesOf(AuthorityKind.ROLE);
            boolean hasRole = requiredRoles.stream().anyMatch(grantedRoles::contains);
            if (!hasRole) {
                log.debug("Authorization denied: ROLE_MISSING; required={}, granted={}", requiredRoles, grantedRoles);
                return AuthorizationDecision.deny(REASON_ROLE_MISSING);
            }
        }

        // Scope check (AND or OR semantics per requireAllScopes).
        // Union SCOPE + PERMISSION claims: the old pipeline treated permissions as valid for
        // @Authorized(scopes = ...) checks, and existing tests rely on that behavior.
        if (!requiredScopes.isEmpty()) {
            Set<String> grantedScopes = new java.util.HashSet<>(authorizationClaims.valuesOf(AuthorityKind.SCOPE));
            grantedScopes.addAll(authorizationClaims.valuesOf(AuthorityKind.PERMISSION));
            if (requireAllScopes) {
                boolean hasAll = grantedScopes.containsAll(requiredScopes);
                if (!hasAll) {
                    log.debug(
                            "Authorization denied: SCOPE_MISSING; required={}, granted={}",
                            requiredScopes,
                            grantedScopes);
                    return AuthorizationDecision.deny(REASON_SCOPE_MISSING);
                }
            } else {
                boolean hasAny = requiredScopes.stream().anyMatch(grantedScopes::contains);
                if (!hasAny) {
                    log.debug(
                            "Authorization denied: SCOPE_INSUFFICIENT; required any of={}, granted={}",
                            requiredScopes,
                            grantedScopes);
                    return AuthorizationDecision.deny(REASON_SCOPE_INSUFFICIENT);
                }
            }
        }

        return AuthorizationDecision.permit(REASON_PERMITTED);
    }
}
