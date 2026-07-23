// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import java.util.List;

/**
 * Describes the security policy declared on a JAX-RS resource method via annotations.
 *
 * <p>Produced at startup from annotation scanning and passed directly to
 * {@link dev.vertique.rest.core.router.OperationHandlerContributor} and {@link SecurityPolicyValidator}
 * via {@link dev.vertique.rest.core.router.OperationRegistrationContext}. Consumers pattern-match on the
 * sealed variants to handle each policy type without null-checking raw annotation objects.
 *
 * <p>Variant precedence (set at scan time):
 *
 * <ol>
 *   <li>{@link DenyAll} — {@code @DenyAll} present (mutually exclusive with all others)
 *   <li>{@link PermitAll} — {@code @PermitAll} present (mutually exclusive with role/scope
 *       annotations)
 *   <li>{@link AuthenticatedOnly} — {@code @Authorized(scopes={})} alone (no roles, no scopes)
 *   <li>{@link Constrained} — {@code @RolesAllowed} and/or {@code @Authorized(scopes={"x",...})}
 *   <li>{@link None} — no security annotations present
 * </ol>
 *
 * <p>Conflicting combinations (e.g. {@code @DenyAll} + {@code @RolesAllowed}) are rejected at
 * startup with a {@link SecurityPolicyViolation}.
 */
public sealed interface SecurityPolicy
        permits SecurityPolicy.None,
                SecurityPolicy.DenyAll,
                SecurityPolicy.PermitAll,
                SecurityPolicy.AuthenticatedOnly,
                SecurityPolicy.Constrained {

    /** No security annotations present on the operation. */
    record None() implements SecurityPolicy {}

    /**
     * {@code @DenyAll} — always reject the request with 403. Mutually exclusive with all other
     * security annotations.
     */
    record DenyAll() implements SecurityPolicy {}

    /**
     * {@code @PermitAll} — no authorization handler added; OpenAPI security requirements still
     * apply. Mutually exclusive with {@code @RolesAllowed} and {@code @Authorized}.
     */
    record PermitAll() implements SecurityPolicy {}

    /**
     * {@code @Authorized(scopes={})} alone — any authenticated user is required; no role or scope
     * constraint.
     *
     * <p>This variant is distinct from {@link Constrained} to avoid the footgun of treating an
     * empty scope list as a no-op. Pattern matching forces contributors to handle auth-only
     * explicitly.
     */
    record AuthenticatedOnly() implements SecurityPolicy {}

    /**
     * Role/scope constraints from {@code @RolesAllowed} and/or
     * {@code @Authorized(scopes={"x",...})}.
     *
     * <p>Invariant: at least one of {@code requiredRoles} or {@code requiredScopes} is non-empty.
     *
     * <p>When both are present, AND semantics apply: the user must satisfy both role and scope
     * checks.
     *
     * @param requiredRoles    roles declared by {@code @RolesAllowed}; empty if not present
     * @param requiredScopes   scopes declared by {@code @Authorized}; empty if {@code @Authorized}
     *                         absent or only present with no scopes subsumed by role check
     * @param requireAllScopes {@code true} if {@code @Authorized.matchAll()} is set; {@code false}
     *                         if {@code @Authorized} is absent
     */
    record Constrained(List<String> requiredRoles, List<String> requiredScopes, boolean requireAllScopes)
            implements SecurityPolicy {
        public Constrained {
            requiredRoles = List.copyOf(requiredRoles);
            requiredScopes = List.copyOf(requiredScopes);
        }
    }

    /**
     * Returns {@code true} if this policy requires authentication or authorization.
     *
     * <p>{@link None} and {@link PermitAll} return {@code false}; all other variants return
     * {@code true}.
     *
     * @return {@code true} if the operation requires the caller to be authenticated or authorized
     */
    default boolean isRestrictive() {
        return switch (this) {
            case None ignored -> false;
            case PermitAll ignored -> false;
            case DenyAll ignored -> true;
            case AuthenticatedOnly ignored -> true;
            case Constrained ignored -> true;
        };
    }
}
