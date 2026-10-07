// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.runtime.authz.ClaimAuthorizationPolicy;
import io.vertx.core.Future;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Set;

/**
 * Default {@link AuthorizationDecisionPoint} that evaluates authorization directly against the
 * {@link AuthorizationClaims} held by the request's {@link dev.vertique.security.SecurityContext}.
 *
 * <p>The role/scope calculation is delegated to {@link ClaimAuthorizationPolicy}; see its Javadoc for
 * the OR and AND semantics and for how {@code PERMISSION} claims count as scopes. The requirements
 * arrive in {@link AuthorizationRequest#context()} under the keys {@value #CTX_REQUIRED_ROLES},
 * {@value #CTX_REQUIRED_SCOPES} and {@value #CTX_REQUIRE_ALL_SCOPES}.
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
 * {@link dev.vertique.security.events.AuthorizationDecisionEvent} per authorization attempt.
 *
 * @see AuthorizationDecisionPoint
 * @see SyncPolicyDecisionPoint
 */
@Singleton
public final class VertxProviderDecisionPoint implements AuthorizationDecisionPoint {

    // --- Context map keys for policy requirements ---

    /**
     * Key in {@link AuthorizationRequest#context()} carrying {@code List<String>} of required roles.
     * An absent or empty value means no role constraint.
     */
    static final String CTX_REQUIRED_ROLES = ClaimAuthorizationPolicy.CTX_REQUIRED_ROLES;

    /**
     * Key in {@link AuthorizationRequest#context()} carrying {@code List<String>} of required scopes.
     * An absent or empty value means no scope constraint.
     */
    static final String CTX_REQUIRED_SCOPES = ClaimAuthorizationPolicy.CTX_REQUIRED_SCOPES;

    /**
     * Key in {@link AuthorizationRequest#context()} carrying {@code Boolean} — {@code true} to
     * require ALL listed scopes (AND semantics), {@code false} for any one (OR semantics).
     */
    static final String CTX_REQUIRE_ALL_SCOPES = ClaimAuthorizationPolicy.CTX_REQUIRE_ALL_SCOPES;

    // --- Reason codes ---

    /** Reason code emitted when all required authorization constraints are satisfied. */
    static final String REASON_PERMITTED = ClaimAuthorizationPolicy.REASON_PERMITTED;

    /** Reason code emitted when the caller lacks at least one required role. */
    static final String REASON_ROLE_MISSING = ClaimAuthorizationPolicy.REASON_ROLE_MISSING;

    /** Reason code emitted when the caller lacks at least one required scope (AND mode). */
    static final String REASON_SCOPE_MISSING = ClaimAuthorizationPolicy.REASON_SCOPE_MISSING;

    /** Reason code emitted when the caller lacks any of the required scopes (OR mode). */
    static final String REASON_SCOPE_INSUFFICIENT = ClaimAuthorizationPolicy.REASON_SCOPE_INSUFFICIENT;

    // --- Fields ---

    private static final ClaimAuthorizationPolicy CLAIMS = new ClaimAuthorizationPolicy();

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
     * Evaluates authorization against the role/scope claims in the request's
     * {@link dev.vertique.security.SecurityContext} by delegating to {@link ClaimAuthorizationPolicy}.
     *
     * <p>Pure evaluator: this method emits no event. The enforcement layer
     * ({@link SecurityPolicyEnforcer}) owns emission.
     *
     * @param request the authorization request; must not be {@code null}
     * @return a {@link Future} completing with the authorization decision; never fails
     */
    @Override
    public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        return Future.succeededFuture(CLAIMS.decide(request));
    }
}
