// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The role and scope calculation applied to the {@link AuthorizationClaims} of a request's security
 * context.
 *
 * <p>The requirements arrive in {@link AuthorizationRequest#context()} under {@value #CTX_REQUIRED_ROLES},
 * {@value #CTX_REQUIRED_SCOPES} and {@value #CTX_REQUIRE_ALL_SCOPES}:
 *
 * <ul>
 *   <li><strong>Roles</strong> are checked against {@link AuthorityKind#ROLE} claims with OR
 *       semantics: any one required role is sufficient.</li>
 *   <li><strong>Scopes</strong> are checked against the union of {@link AuthorityKind#SCOPE} and
 *       {@link AuthorityKind#PERMISSION} claims, with AND semantics when
 *       {@value #CTX_REQUIRE_ALL_SCOPES} is {@code true} and OR semantics otherwise.</li>
 *   <li>When both are present, both must be satisfied. An absent or empty requirement is no
 *       constraint.</li>
 * </ul>
 *
 * <p>This is a pure calculation: it neither authenticates nor emits events. The enforcement layer
 * owns both. The key spellings and reason codes are part of the contract between the enforcement
 * layer and this policy, so they are shared constants. This class is framework-internal: use
 * {@link AuthorizationPolicy} to supply custom evaluation.
 */
@Slf4j
public final class ClaimAuthorizationPolicy implements AuthorizationPolicy {

    /** Context key carrying a {@code List<String>} of required roles; absent or empty means none. */
    public static final String CTX_REQUIRED_ROLES = "requiredRoles";

    /** Context key carrying a {@code List<String>} of required scopes; absent or empty means none. */
    public static final String CTX_REQUIRED_SCOPES = "requiredScopes";

    /**
     * Context key carrying a {@code Boolean}: {@code true} requires every listed scope, {@code false}
     * any one of them.
     */
    public static final String CTX_REQUIRE_ALL_SCOPES = "requireAllScopes";

    /** Reason code when every required constraint is satisfied. */
    public static final String REASON_PERMITTED = "PERMITTED";

    /** Reason code when the caller lacks every required role. */
    public static final String REASON_ROLE_MISSING = "ROLE_MISSING";

    /** Reason code when the caller lacks at least one required scope in AND mode. */
    public static final String REASON_SCOPE_MISSING = "SCOPE_MISSING";

    /** Reason code when the caller lacks every required scope in OR mode. */
    public static final String REASON_SCOPE_INSUFFICIENT = "SCOPE_INSUFFICIENT";

    /** Creates the policy. It holds no state. */
    public ClaimAuthorizationPolicy() {}

    @Override
    @SuppressWarnings("unchecked")
    public AuthorizationDecision decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        AuthorizationClaims authorizationClaims = request.securityContext().authorization();
        Map<String, Object> ctx = request.context();

        List<String> requiredRoles =
                ctx.containsKey(CTX_REQUIRED_ROLES) ? (List<String>) ctx.get(CTX_REQUIRED_ROLES) : List.of();

        List<String> requiredScopes =
                ctx.containsKey(CTX_REQUIRED_SCOPES) ? (List<String>) ctx.get(CTX_REQUIRED_SCOPES) : List.of();

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
        // PERMISSION claims count as scopes, matching the documented @Authorized(scopes) behavior.
        if (!requiredScopes.isEmpty()) {
            Set<String> grantedScopes = new HashSet<>(authorizationClaims.valuesOf(AuthorityKind.SCOPE));
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
