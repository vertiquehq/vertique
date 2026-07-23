// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import java.util.List;

/**
 * Folds a single-scheme {@link SecurityRequirementSet}'s scopes into an operation's effective
 * {@link SecurityPolicy} so the existing authorization path enforces them.
 *
 * <p>An OpenAPI {@code @SecurityRequirement(name="x", scopes={"write"})} declares not just
 * authentication but a scope requirement. Authentication is installed as the route's auth handler; the
 * <em>scopes</em> are authorized by the same mechanism that enforces {@code @Authorized(scopes=…)}:
 * {@link SecurityPolicy.Constrained#requiredScopes()} evaluated by the framework decision point. This
 * helper computes the operation's effective policy by promoting the base (annotation-derived) policy to
 * a {@link SecurityPolicy.Constrained} that requires the set's scopes — there is exactly <em>one</em>
 * scope-enforcement path and one {@code 403}.
 *
 * <p><strong>Supported shape only (SH-2 guarantees).</strong> The fold engages only for a single
 * single-scheme set whose scope list is non-empty. Multi-scheme sets, scoped-OR (more than one set with
 * any scoped set), and the both-scopes case ({@code @Authorized(scopes)} together with set scopes) are
 * rejected at startup by {@link #enforceSupportedShape}, so this helper never folds them and never has
 * to merge two scope sources. Consequently the base policy never already carries required scopes, and
 * the fold only ever <em>adds</em> the set's scopes.
 *
 * <p><strong>Single source of truth for the fail-closed matrix.</strong> {@link #enforceSupportedShape}
 * is the authoritative implementation of ADR-0124's V1 fail-closed matrix. The rest-jaxrs
 * {@code JaxRsRouteRegistrar} calls it unconditionally for every operation (independent of whether the
 * optional {@code DefaultSecurityPolicyValidator} is wired), and that validator delegates to it too, so
 * the matrix is enforced from exactly one place. Placing it here — next to {@link #fold} — keeps the
 * gate and the fold that depends on it in the one type that already references both
 * {@link SecurityPolicy} and {@link SecurityRequirementSet}.
 */
public final class EffectiveSecurityPolicy {

    private EffectiveSecurityPolicy() {}

    /**
     * Fails startup ({@link RestConfigurationException}) when the operation declares a security shape
     * whose V1 enforcement is deferred — ADR-0124's fail-closed matrix. This is the single source of
     * truth for the matrix: the rest-jaxrs route registrar calls it for every operation and the
     * rest-security {@code DefaultSecurityPolicyValidator} delegates to it.
     *
     * <p>Three deferred cases are rejected, in order, each with a message naming the operationId, the
     * unsupported case, and the workaround:
     *
     * <ol>
     *   <li><strong>Multi-scheme (AND) set</strong> — any set with more than one scheme
     *       ({@code combine()} AND-groups), which would need {@code ChainAuthHandler.all()} composition.</li>
     *   <li><strong>Scoped-OR</strong> — more than one OR alternative where any set carries scopes; the
     *       matched alternative is not tracked, so per-alternative scopes cannot be enforced.</li>
     *   <li><strong>Both-scopes</strong> — the base {@link SecurityPolicy} declares required scopes
     *       (via {@code @Authorized}) <em>and</em> any set carries scopes; the two scope sources cannot
     *       be merged unambiguously. Roles via {@code @RolesAllowed}/{@code @Authorized} alongside
     *       {@code @SecurityRequirement} scopes is permitted (distinct authority kinds).</li>
     * </ol>
     *
     * @param operationId the operationId named in the diagnostic; must not be {@code null}
     * @param sets        the operation's effective security requirement sets (the OR alternatives);
     *                    must not be {@code null}
     * @param policy      the operation's resolved base security policy; must not be {@code null}
     * @throws RestConfigurationException if the shape is one of the three deferred cases
     */
    public static void enforceSupportedShape(
            String operationId, List<SecurityRequirementSet> sets, SecurityPolicy policy) {
        // Case 1: any multi-scheme (AND) set — combine() AND-groups are deferred.
        boolean anyMultiScheme = sets.stream().anyMatch(set -> !set.isSingleScheme());
        if (anyMultiScheme) {
            throw new RestConfigurationException(String.format(
                    "Operation '%s' declares a multi-scheme @SecurityRequirement (combine() AND-group), "
                            + "which is not yet supported. Declare a single scheme per requirement.",
                    operationId));
        }

        boolean anySetHasScopes = sets.stream().anyMatch(SecurityRequirementSet::hasScopes);

        // Case 2: more than one OR alternative AND any set has scopes — scoped-OR is deferred.
        if (sets.size() > 1 && anySetHasScopes) {
            throw new RestConfigurationException(String.format(
                    "Operation '%s' declares scopes on an OR alternative @SecurityRequirement, which is "
                            + "not yet supported. Scopes on OR alternatives are not yet supported; use a "
                            + "single scheme with scopes, or drop the scopes.",
                    operationId));
        }

        // Case 3: the policy declares required scopes (via @Authorized) AND any set has scopes — the
        // two scope sources cannot be merged unambiguously. Roles alongside set scopes is fine.
        if (anySetHasScopes && policyDeclaresScopes(policy)) {
            throw new RestConfigurationException(String.format(
                    "Operation '%s' declares scopes via both @Authorized and @SecurityRequirement, which "
                            + "is not yet supported. Declare scopes via @Authorized OR @SecurityRequirement, "
                            + "not both.",
                    operationId));
        }
    }

    /**
     * Returns whether the security policy declares required scopes (as opposed to roles). Only the
     * {@link SecurityPolicy.Constrained} variant with a non-empty {@code requiredScopes} declares
     * scopes; all other variants declare none.
     *
     * @param policy the security policy to inspect
     * @return {@code true} if the policy declares one or more required scopes
     */
    private static boolean policyDeclaresScopes(SecurityPolicy policy) {
        return policy instanceof SecurityPolicy.Constrained c
                && !c.requiredScopes().isEmpty();
    }

    /**
     * Returns the operation's effective security policy, folding a single-scheme set's scopes into a
     * scope-enforcing {@link SecurityPolicy.Constrained} when present.
     *
     * <p>When the operation declares exactly one {@link SecurityRequirementSet} that is single-scheme
     * and whose scheme has a non-empty scope list, the returned policy requires those scopes with
     * {@code requireAllScopes=true} (OpenAPI AND-semantics), preserving any roles already on the base
     * policy:
     *
     * <ul>
     *   <li>{@link SecurityPolicy.None} / {@link SecurityPolicy.AuthenticatedOnly} → promoted to
     *       {@code Constrained([], scopes, true)};</li>
     *   <li>{@link SecurityPolicy.Constrained} with <em>no</em> required scopes →
     *       {@code Constrained(existingRoles, scopes, true)} (the normal case: the both-scopes guard
     *       ensures the base carries no scopes);</li>
     *   <li>{@link SecurityPolicy.Constrained} that <em>already</em> declares required scopes (the
     *       both-scopes shape, rejected upstream by {@link #enforceSupportedShape}) → returned
     *       <strong>unchanged</strong>: fold never silently replaces or loosens the base's own scopes.
     *       The upstream gate makes this unreachable, but fold is safe in isolation;</li>
     *   <li>{@link SecurityPolicy.PermitAll} / {@link SecurityPolicy.DenyAll} → returned unchanged (a
     *       scoped set cannot co-occur with a blanket policy past SH-2 validation; defensively left
     *       alone rather than overriding the operator's blanket intent).</li>
     * </ul>
     *
     * <p>For a scopeless set, no set, or anything other than the single scoped single-scheme shape, the
     * base {@code policy} is returned unchanged (the same instance) — no scope gate is engaged.
     *
     * @param policy the base security policy resolved from the operation's Jakarta annotations; must
     *               not be {@code null}
     * @param sets   the operation's effective security requirement sets (the OR alternatives); must not
     *               be {@code null}
     * @return the effective policy — the base policy with the single scoped set's scopes folded in, or
     *     the unchanged base policy when no scope fold applies; never {@code null}
     */
    public static SecurityPolicy fold(SecurityPolicy policy, List<SecurityRequirementSet> sets) {
        List<String> scopes = scopesToFold(sets);
        if (scopes.isEmpty()) {
            // No set, a scopeless set, or an unsupported shape (defended upstream by SH-2): no gate.
            return policy;
        }
        return switch (policy) {
            case SecurityPolicy.None ignored -> new SecurityPolicy.Constrained(List.of(), scopes, true);
            case SecurityPolicy.AuthenticatedOnly ignored -> new SecurityPolicy.Constrained(List.of(), scopes, true);
            // Fold the set's scopes onto the base ONLY when the base carries no scopes of its own. If the
            // base already declares scopes (the both-scopes shape, rejected upstream by
            // enforceSupportedShape), fold must NOT silently replace/loosen them: return the base
            // unchanged. The upstream gate makes this branch unreachable, but fold stays safe in isolation.
            case SecurityPolicy.Constrained c ->
                c.requiredScopes().isEmpty() ? new SecurityPolicy.Constrained(c.requiredRoles(), scopes, true) : policy;
            // A blanket PermitAll/DenyAll cannot co-occur with a scoped requirement past SH-2 validation;
            // leave the operator's blanket intent untouched rather than silently overriding it.
            case SecurityPolicy.PermitAll ignored -> policy;
            case SecurityPolicy.DenyAll ignored -> policy;
        };
    }

    /**
     * Returns the scopes to fold from the operation's requirement sets, or an empty list when the shape
     * is not the single scoped single-scheme set this helper supports.
     *
     * <p>Returns the single scheme's scopes only when there is exactly one set, that set is
     * single-scheme, and its scheme declares a non-empty scope list. Every other shape (no sets, a
     * scopeless set, or a shape SH-2 would have rejected) yields an empty list so {@link #fold} leaves
     * the policy unchanged.
     *
     * @param sets the operation's security requirement sets
     * @return the scopes to fold, or an empty list when no fold applies
     */
    private static List<String> scopesToFold(List<SecurityRequirementSet> sets) {
        if (sets.size() != 1) {
            return List.of();
        }
        SecurityRequirementSet set = sets.get(0);
        if (!set.isSingleScheme()) {
            return List.of();
        }
        SecurityRequirement scheme = set.schemes().get(0);
        // Return the scheme's scopes directly: the sole consumer (fold) passes them to
        // SecurityPolicy.Constrained, whose compact constructor takes a defensive List.copyOf, so no
        // caller can mutate the policy's scopes through the returned reference — a defensive copy here
        // would be redundant.
        return scheme.scopes();
    }
}
