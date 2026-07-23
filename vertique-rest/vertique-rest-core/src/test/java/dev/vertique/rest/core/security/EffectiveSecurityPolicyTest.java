// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EffectiveSecurityPolicy#fold}, the registration-time computation that folds a
 * single-scheme {@link SecurityRequirementSet}'s scopes into the operation's effective
 * {@link SecurityPolicy} so the existing {@code VertxProviderDecisionPoint}/{@code SecurityPolicyEnforcer}
 * authorizes them (SH-4 / finding C2).
 *
 * <p>Contract pinned:
 *
 * <ul>
 *   <li>A single-scheme set with scopes {@code S} promotes the base policy to a
 *       {@link SecurityPolicy.Constrained} requiring scopes {@code S} with {@code requireAllScopes=true}
 *       (OpenAPI AND-semantics), preserving any existing {@code @RolesAllowed} roles. There are never
 *       existing required <em>scopes</em> on the base policy — SH-2's both-scopes guard rejects that
 *       at startup — so the fold only ever adds scopes, never merges two scope sources.</li>
 *   <li>A scopeless set (or no set) leaves the base policy untouched (the same instance is returned).</li>
 *   <li>Blanket policies ({@code PermitAll}/{@code DenyAll}) are returned unchanged — a scoped set
 *       cannot co-occur with them past SH-2 validation, so the fold defensively leaves them alone.</li>
 * </ul>
 */
class EffectiveSecurityPolicyTest {

    private static final SecurityRequirement BEARER_WRITE = new SecurityRequirement("bearerAuth", List.of("write"));
    private static final SecurityRequirement BEARER_SCOPELESS = new SecurityRequirement("bearerAuth", List.of());

    private static SecurityRequirementSet scopedSet() {
        return new SecurityRequirementSet(List.of(BEARER_WRITE));
    }

    private static SecurityRequirementSet scopelessSet() {
        return new SecurityRequirementSet(List.of(BEARER_SCOPELESS));
    }

    @Nested
    @DisplayName("scoped single-scheme set folds scopes into Constrained(requireAll=true)")
    class ScopedSet {

        @Test
        @DisplayName("role-only Constrained → Constrained(roles, [write], requireAll=true)")
        void roleOnlyConstrained_foldsScopesPreservingRoles() {
            // given a role-only Constrained base policy and a single scoped set
            SecurityPolicy base = new SecurityPolicy.Constrained(List.of("admin"), List.of(), false);

            // when the effective policy is computed
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopedSet()));

            // then the scopes are folded in with AND-semantics and the roles are preserved
            SecurityPolicy.Constrained c =
                    assertInstanceOf(SecurityPolicy.Constrained.class, effective, "must remain Constrained");
            assertEquals(List.of("admin"), c.requiredRoles(), "existing roles must be preserved");
            assertEquals(List.of("write"), c.requiredScopes(), "the set's scopes must become required scopes");
            assertTrue(c.requireAllScopes(), "OpenAPI scopes are an AND → requireAllScopes must be true");
        }

        @Test
        @DisplayName("AuthenticatedOnly → Constrained([], [write], requireAll=true)")
        void authenticatedOnly_promotedToConstrainedWithScopes() {
            // given an auth-only base policy
            SecurityPolicy base = new SecurityPolicy.AuthenticatedOnly();

            // when the effective policy is computed
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopedSet()));

            // then it is promoted to a Constrained with no roles and the set's scopes
            SecurityPolicy.Constrained c = assertInstanceOf(
                    SecurityPolicy.Constrained.class, effective, "auth-only must promote to Constrained");
            assertEquals(List.of(), c.requiredRoles(), "no roles on an auth-only policy");
            assertEquals(List.of("write"), c.requiredScopes());
            assertTrue(c.requireAllScopes());
        }

        @Test
        @DisplayName("None → Constrained([], [write], requireAll=true)")
        void none_promotedToConstrainedWithScopes() {
            // given a None base policy (a scoped @SecurityRequirement with no Jakarta annotations)
            SecurityPolicy base = new SecurityPolicy.None();

            // when the effective policy is computed
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopedSet()));

            // then it is promoted to a Constrained carrying only the set's scopes
            SecurityPolicy.Constrained c =
                    assertInstanceOf(SecurityPolicy.Constrained.class, effective, "None must promote to Constrained");
            assertEquals(List.of(), c.requiredRoles());
            assertEquals(List.of("write"), c.requiredScopes());
            assertTrue(c.requireAllScopes());
        }
    }

    @Nested
    @DisplayName("scopeless / absent set leaves the policy unchanged")
    class Unchanged {

        @Test
        @DisplayName("scopeless single-scheme set → same policy instance")
        void scopelessSet_policyUnchanged() {
            // given any base policy and a scopeless set
            SecurityPolicy base = new SecurityPolicy.AuthenticatedOnly();

            // when the effective policy is computed
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopelessSet()));

            // then the policy is returned untouched (no scope gate engaged)
            assertSame(base, effective, "a scopeless set must not change the policy");
        }

        @Test
        @DisplayName("no sets → same policy instance")
        void noSets_policyUnchanged() {
            // given any base policy and no requirement sets (public operation)
            SecurityPolicy base = new SecurityPolicy.Constrained(List.of("admin"), List.of(), false);

            // when the effective policy is computed
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of());

            // then the policy is returned untouched
            assertSame(base, effective, "no requirement set must not change the policy");
        }

        @Test
        @DisplayName("PermitAll with a scoped set → unchanged (cannot co-occur past SH-2)")
        void permitAll_unchanged() {
            // given a blanket PermitAll policy
            SecurityPolicy base = new SecurityPolicy.PermitAll();

            // when the effective policy is computed against a scoped set
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopedSet()));

            // then the blanket policy is left alone (the fold only promotes None/AuthenticatedOnly/Constrained)
            assertSame(base, effective, "a blanket PermitAll must not be folded into a scope gate");
        }

        @Test
        @DisplayName("DenyAll with a scoped set → unchanged")
        void denyAll_unchanged() {
            // given a blanket DenyAll policy
            SecurityPolicy base = new SecurityPolicy.DenyAll();

            // when the effective policy is computed against a scoped set
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopedSet()));

            // then the blanket deny is left alone
            assertSame(base, effective, "a blanket DenyAll must not be folded into a scope gate");
        }
    }

    @Nested
    @DisplayName("fold never substitutes or loosens base scopes (both-scopes safety)")
    class FoldNeverSubstitutes {

        @Test
        @DisplayName("base Constrained WITH scopes + a scoped set → base scopes are NOT replaced")
        void baseWithScopes_scopedSet_doesNotReplaceBaseScopes() {
            // given a base policy that ALREADY declares required scopes (the both-scopes shape that the
            // upstream matrix gate rejects) and a scoped single-scheme set carrying DIFFERENT scopes
            SecurityPolicy base = new SecurityPolicy.Constrained(List.of("admin"), List.of("read"), true);

            // when the effective policy is computed against a set whose scope is "write"
            SecurityPolicy effective = EffectiveSecurityPolicy.fold(base, List.of(scopedSet()));

            // then the base's own scopes must NOT be silently replaced by the set's scopes — fold must
            // be safe in isolation even though the upstream gate makes this shape unreachable.
            SecurityPolicy.Constrained c =
                    assertInstanceOf(SecurityPolicy.Constrained.class, effective, "must remain Constrained");
            assertEquals(List.of("read"), c.requiredScopes(), "base scopes must not be replaced by the set's scopes");
            assertEquals(List.of("admin"), c.requiredRoles(), "base roles must be preserved");
        }
    }

    @Nested
    @DisplayName("enforceSupportedShape rejects the deferred V1 security shapes (fail-closed matrix)")
    class MatrixGate {

        private static final String OP = "matrixOp";

        private static SecurityRequirementSet scopelessSchemeSet(String scheme) {
            return new SecurityRequirementSet(List.of(new SecurityRequirement(scheme, List.of())));
        }

        private static SecurityRequirementSet scopedSchemeSet(String scheme, String scope) {
            return new SecurityRequirementSet(List.of(new SecurityRequirement(scheme, List.of(scope))));
        }

        @Test
        @DisplayName("multi-scheme AND set → RestConfigurationException naming the operationId")
        void multiSchemeSet_throws() {
            SecurityRequirementSet andSet = new SecurityRequirementSet(
                    List.of(new SecurityRequirement("jwt", List.of()), new SecurityRequirement("apiKey", List.of())));

            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class,
                    () -> EffectiveSecurityPolicy.enforceSupportedShape(
                            OP, List.of(andSet), new SecurityPolicy.None()));
            assertTrue(ex.getMessage().contains(OP), "message must name the operationId");
        }

        @Test
        @DisplayName("scoped-OR (>1 set, any set scoped) → RestConfigurationException")
        void scopedOr_throws() {
            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class,
                    () -> EffectiveSecurityPolicy.enforceSupportedShape(
                            OP,
                            List.of(scopedSchemeSet("bearerAuth", "write"), scopelessSchemeSet("apiKey")),
                            new SecurityPolicy.None()));
            assertTrue(ex.getMessage().contains(OP), "message must name the operationId");
        }

        @Test
        @DisplayName("both-scopes (@Authorized scopes + a scoped set) → RestConfigurationException")
        void bothScopes_throws() {
            SecurityPolicy base = new SecurityPolicy.Constrained(List.of(), List.of("write"), true);

            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class,
                    () -> EffectiveSecurityPolicy.enforceSupportedShape(
                            OP, List.of(scopedSchemeSet("oauth2", "read")), base));
            assertTrue(ex.getMessage().contains(OP), "message must name the operationId");
        }

        @Test
        @DisplayName("supported shapes pass: public, single-scheme scoped/scopeless, scopeless-OR, roles+scoped-set")
        void supportedShapes_pass() {
            // public
            assertDoesNotThrow(
                    () -> EffectiveSecurityPolicy.enforceSupportedShape(OP, List.of(), new SecurityPolicy.None()));
            // single-scheme scopeless
            assertDoesNotThrow(() -> EffectiveSecurityPolicy.enforceSupportedShape(
                    OP, List.of(scopelessSchemeSet("bearerAuth")), new SecurityPolicy.None()));
            // single-scheme scoped
            assertDoesNotThrow(() -> EffectiveSecurityPolicy.enforceSupportedShape(
                    OP, List.of(scopedSchemeSet("oauth2", "read")), new SecurityPolicy.None()));
            // scopeless OR
            assertDoesNotThrow(() -> EffectiveSecurityPolicy.enforceSupportedShape(
                    OP,
                    List.of(scopelessSchemeSet("bearerAuth"), scopelessSchemeSet("apiKey")),
                    new SecurityPolicy.None()));
            // roles via @Authorized alongside a scoped set — distinct authority kinds, allowed
            assertDoesNotThrow(() -> EffectiveSecurityPolicy.enforceSupportedShape(
                    OP,
                    List.of(scopedSchemeSet("oauth2", "read")),
                    new SecurityPolicy.Constrained(List.of("admin"), List.of(), false)));
        }
    }
}
