// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.ResourceRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClaimAuthorizationPolicy}: role OR semantics, scope AND and OR semantics,
 * the {@code SCOPE} plus {@code PERMISSION} union, reason codes, and null-request rejection.
 */
class ClaimAuthorizationPolicyTest {

    private final ClaimAuthorizationPolicy policy = new ClaimAuthorizationPolicy();

    private static AuthorityClaim claim(AuthorityKind kind, String value) {
        return new AuthorityClaim(kind, value, "", "", "", Map.of());
    }

    private static AuthorizationRequest request(
            Set<AuthorityClaim> claims, List<String> roles, List<String> scopes, Boolean requireAll) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authorization()).thenReturn(new AuthorizationClaims(claims, Map.of()));
        when(ctx.origin()).thenReturn(Optional.empty());
        Map<String, Object> policyCtx = new HashMap<>();
        if (roles != null) {
            policyCtx.put(ClaimAuthorizationPolicy.CTX_REQUIRED_ROLES, roles);
        }
        if (scopes != null) {
            policyCtx.put(ClaimAuthorizationPolicy.CTX_REQUIRED_SCOPES, scopes);
        }
        if (requireAll != null) {
            policyCtx.put(ClaimAuthorizationPolicy.CTX_REQUIRE_ALL_SCOPES, requireAll);
        }
        return new AuthorizationRequest(ctx, "GET", new ResourceRef("route", "/r", Map.of()), policyCtx);
    }

    @Test
    @DisplayName("no constraints permit")
    void permitsWhenNothingIsRequired() {
        AuthorizationDecision decision = policy.decide(request(Set.of(), null, null, null));

        assertTrue(decision.permitted());
        assertEquals(ClaimAuthorizationPolicy.REASON_PERMITTED, decision.reasonCode());
    }

    @Test
    @DisplayName("empty required lists are no constraint")
    void treatsEmptyListsAsNoConstraint() {
        assertTrue(policy.decide(request(Set.of(), List.of(), List.of(), true)).permitted());
    }

    @Test
    @DisplayName("any one required role is sufficient")
    void permitsWhenOneRequiredRoleIsHeld() {
        Set<AuthorityClaim> claims = Set.of(claim(AuthorityKind.ROLE, "editor"));

        assertTrue(policy.decide(request(claims, List.of("admin", "editor"), null, null))
                .permitted());
    }

    @Test
    @DisplayName("a missing role denies with ROLE_MISSING, and a PERMISSION claim is not a role")
    void deniesWhenNoRequiredRoleIsHeld() {
        Set<AuthorityClaim> claims = Set.of(claim(AuthorityKind.PERMISSION, "admin"));

        AuthorizationDecision decision = policy.decide(request(claims, List.of("admin"), null, null));

        assertFalse(decision.permitted());
        assertEquals(ClaimAuthorizationPolicy.REASON_ROLE_MISSING, decision.reasonCode());
    }

    @Test
    @DisplayName("all required scopes are needed in AND mode, drawn from SCOPE and PERMISSION together")
    void requiresEveryScopeInAndModeFromTheUnion() {
        Set<AuthorityClaim> claims =
                Set.of(claim(AuthorityKind.SCOPE, "read"), claim(AuthorityKind.PERMISSION, "write"));

        assertTrue(policy.decide(request(claims, null, List.of("read", "write"), true))
                .permitted());

        AuthorizationDecision partial = policy.decide(request(claims, null, List.of("read", "delete"), true));
        assertFalse(partial.permitted());
        assertEquals(ClaimAuthorizationPolicy.REASON_SCOPE_MISSING, partial.reasonCode());
    }

    @Test
    @DisplayName("one required scope is enough in OR mode, otherwise SCOPE_INSUFFICIENT")
    void requiresAnyScopeInOrMode() {
        Set<AuthorityClaim> claims = Set.of(claim(AuthorityKind.PERMISSION, "read"));

        assertTrue(policy.decide(request(claims, null, List.of("read", "write"), false))
                .permitted());
        assertTrue(policy.decide(request(claims, null, List.of("read", "write"), null))
                .permitted());

        AuthorizationDecision none = policy.decide(request(claims, null, List.of("write"), false));
        assertFalse(none.permitted());
        assertEquals(ClaimAuthorizationPolicy.REASON_SCOPE_INSUFFICIENT, none.reasonCode());
    }

    @Test
    @DisplayName("roles are checked before scopes, and both must hold")
    void checksRolesBeforeScopes() {
        Set<AuthorityClaim> scopeOnly = Set.of(claim(AuthorityKind.SCOPE, "read"));

        AuthorizationDecision decision = policy.decide(request(scopeOnly, List.of("admin"), List.of("read"), true));

        assertFalse(decision.permitted());
        assertEquals(ClaimAuthorizationPolicy.REASON_ROLE_MISSING, decision.reasonCode());
    }

    @Test
    @DisplayName("a null request is rejected")
    void rejectsANullRequest() {
        assertThrows(NullPointerException.class, () -> policy.decide(null));
    }
}
