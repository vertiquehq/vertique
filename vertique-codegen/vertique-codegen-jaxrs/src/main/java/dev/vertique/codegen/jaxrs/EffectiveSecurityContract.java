// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.util.List;
import java.util.Set;

/**
 * Immutable compile-time representation of the resolved security policy for a JAX-RS resource
 * class or method.
 *
 * <p>Built by {@link EffectiveJaxRsContractResolver} using the same precedence rule as the
 * runtime {@code AnnotationSecurityPolicyResolver}: (1) direct annotations, (2) superclass chain,
 * (3) BFS-ordered interfaces. Conflict detection is performed at the resolver level; this record
 * always carries a valid (non-conflicting) security specification.
 *
 * <p>The {@code kinds} set drives conflict detection and {@code SecurityPolicy} precomputation.
 * The {@code rolesAllowed}, {@code authorizedScopes}, and {@code authorizedMatchAll} fields carry
 * the full {@code @Authorized}/{@code @RolesAllowed} payload needed to distinguish
 * {@code AuthenticatedOnly} from {@code Constrained} during descriptor emission.
 *
 * @param kinds             the set of security annotation kinds present; empty for unannotated
 * @param rolesAllowed      roles from {@code @RolesAllowed}, or empty list if absent
 * @param authorizedScopes  scopes from {@code @Authorized}, or empty list if absent
 * @param authorizedMatchAll whether {@code @Authorized.matchAll()} is {@code true}
 */
public record EffectiveSecurityContract(
        Set<SecurityKind> kinds, List<String> rolesAllowed, List<String> authorizedScopes, boolean authorizedMatchAll) {

    /**
     * Sentinel value representing the absence of any security annotation at a declaration level.
     */
    public static final EffectiveSecurityContract NONE =
            new EffectiveSecurityContract(Set.of(), List.of(), List.of(), false);

    /**
     * Returns {@code true} when no security annotation is present at this declaration level.
     *
     * @return {@code true} if {@code kinds} is empty
     */
    public boolean isEmpty() {
        return kinds.isEmpty();
    }

    /**
     * Security annotation kind discriminants for conflict detection and
     * {@code SecurityPolicy} precomputation.
     */
    public enum SecurityKind {
        /** {@code @jakarta.annotation.security.DenyAll}. */
        DENY_ALL,
        /** {@code @jakarta.annotation.security.PermitAll}. */
        PERMIT_ALL,
        /** {@code @jakarta.annotation.security.RolesAllowed}. */
        ROLES_ALLOWED,
        /** {@code @dev.vertique.rest.core.security.Authorized}. */
        AUTHORIZED
    }
}
