// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.RolePolicyResolver;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared, divergence-proof core of the default authorization engine: the role→policy→action
 * resolution that both {@link DefaultAuthorizer} and {@link DefaultAuthorizationIntrospector} run.
 *
 * <p>This helper owns the materialised policy catalogue (built once from a {@link PolicyDefinitionSource})
 * and the merged {@link RolePolicyResolver}, and exposes a single {@link #decide(SecurityContext, ActionRef)}
 * method that produces the permit/deny verdict for an action <em>already known to be registered</em>.
 * Keeping this logic in one place is the mechanism that guarantees the introspector and the authorizer
 * cannot diverge: the authorizer wraps {@code decide} with the action-registration check, and the
 * introspector calls {@code decide} once per registered action — so for every registered action the two
 * surfaces necessarily agree.
 *
 * <p><strong>Decision order</strong> (first matching rule wins), assuming the action is registered:
 * <ol>
 *   <li>no {@link AuthorityKind#ROLE} claims → deny {@link AuthzReasonCodes#ROLE_MISSING}</li>
 *   <li>roles map to no policy name → deny {@link AuthzReasonCodes#ROLE_POLICY_MISSING}</li>
 *   <li>a mapped policy name is absent from the catalogue → deny {@link AuthzReasonCodes#POLICY_NOT_FOUND}</li>
 *   <li>some {@link Effect#ALLOW} statement matches → permit {@link AuthzReasonCodes#PERMITTED}</li>
 *   <li>otherwise → deny {@link AuthzReasonCodes#ACTION_NOT_ALLOWED}</li>
 * </ol>
 *
 * <p>The whole evaluation is wrapped so any unexpected {@link RuntimeException} (e.g. a misbehaving
 * resolver) <strong>fails closed</strong> to a deny with {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}
 * rather than propagating.
 */
public final class AuthzResolution {

    private final Map<String, PolicyDefinition> policiesByName;
    private final RolePolicyResolver rolePolicyResolver;

    /**
     * Creates the shared resolution core from the (already merged) policy source and the merged
     * role→policy resolver.
     *
     * <p>The policy catalogue is materialised once at construction into an immutable name→policy map.
     * Callers are expected to pass a source whose policy names are already globally unique:
     * duplicate-name detection across contributed sources (naming both offenders) and the
     * registry/role-mapping startup validation are performed upstream in {@code SecurityAuthzModule}
     * before this core is built. Should a same-name pair slip through, {@link #indexByName} keeps the
     * last occurrence (last-wins) rather than failing here.
     *
     * <p>Provided as a {@code @Singleton} by the wiring module so the merged catalogue and resolver
     * are built exactly once and shared by {@link DefaultAuthorizer} and
     * {@link DefaultAuthorizationIntrospector} — the single source of truth that makes their verdicts
     * agree by construction.
     *
     * @param policyDefinitionSource the merged source of policy definitions; must not be {@code null}
     * @param rolePolicyResolver     the merged resolver mapping roles to policy names; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AuthzResolution(PolicyDefinitionSource policyDefinitionSource, RolePolicyResolver rolePolicyResolver) {
        Objects.requireNonNull(policyDefinitionSource, "policyDefinitionSource");
        this.rolePolicyResolver = Objects.requireNonNull(rolePolicyResolver, "rolePolicyResolver");
        this.policiesByName = indexByName(policyDefinitionSource.policies());
    }

    /**
     * Evaluates the permit/deny verdict for an action that is already known to be registered, failing
     * closed on any unexpected error.
     *
     * <p>Callers are responsible for the action-registration check: this method assumes {@code action}
     * is registered and only applies the role→policy→pattern resolution.
     *
     * @param ctx    the security context; never {@code null} (guaranteed by callers)
     * @param action the registered action being evaluated; never {@code null}
     * @return the {@link AuthorizationDecision}; never {@code null}
     */
    AuthorizationDecision decide(SecurityContext ctx, ActionRef action) {
        try {
            // (1) actor must carry at least one ROLE claim
            Set<String> roles = ctx.authorization().valuesOf(AuthorityKind.ROLE);
            if (roles.isEmpty()) {
                return AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING);
            }

            // (2) roles must map to at least one policy name
            Set<String> policyNames = rolePolicyResolver.policiesForRoles(roles);
            if (policyNames.isEmpty()) {
                return AuthorizationDecision.deny(AuthzReasonCodes.ROLE_POLICY_MISSING);
            }

            // (3) every mapped policy name must exist; (4) permit on first ALLOW match
            for (String policyName : policyNames) {
                PolicyDefinition policy = policiesByName.get(policyName);
                if (policy == null) {
                    return AuthorizationDecision.deny(AuthzReasonCodes.POLICY_NOT_FOUND);
                }
                if (allows(policy, action)) {
                    return AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);
                }
            }

            // (5) policies resolved but none allowed the action
            return AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED);
        } catch (RuntimeException e) {
            // Fail closed: an unexpected error never permits and never propagates.
            return AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        }
    }

    /**
     * Reports whether any {@link Effect#ALLOW} statement of the policy matches the action.
     *
     * @param policy the policy to test
     * @param action the action being requested
     * @return {@code true} if some ALLOW statement's pattern matches {@code action}
     */
    private static boolean allows(PolicyDefinition policy, ActionRef action) {
        for (PolicyStatement statement : policy.statements()) {
            if (statement.effect() == Effect.ALLOW
                    && statement.actions().stream().anyMatch(pattern -> pattern.matches(action))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Materialises the policy catalogue into a name→policy map.
     *
     * @param policies the contributed policies
     * @return an immutable name→policy map
     */
    private static Map<String, PolicyDefinition> indexByName(Collection<PolicyDefinition> policies) {
        Map<String, PolicyDefinition> byName = new LinkedHashMap<>();
        for (PolicyDefinition policy : policies) {
            byName.put(policy.name(), policy);
        }
        return Map.copyOf(byName);
    }
}
