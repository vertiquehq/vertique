// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VertxProviderDecisionPoint}.
 *
 * <p>Since ADR-0114 the decision point is a <strong>pure evaluator</strong>: it returns a decision
 * and emits nothing. These tests verify:
 * <ul>
 *   <li>Permit case: role match (OR semantics) → permitted decision with {@code PERMITTED} reason</li>
 *   <li>Deny case: missing required role → denied with {@code ROLE_MISSING}</li>
 *   <li>Scope OR semantics: any one matching scope → permitted</li>
 *   <li>Scope AND semantics: all scopes required, partial match → denied with {@code SCOPE_MISSING}</li>
 *   <li>Scope AND semantics: all scopes required, full match → permitted</li>
 *   <li>Scope OR semantics with no match → denied with {@code SCOPE_INSUFFICIENT}</li>
 *   <li>Combined role AND scope check: must satisfy both</li>
 *   <li>{@code decide()} emits no event and never throws on a missing correlation</li>
 *   <li>Null {@code request} → {@link NullPointerException}</li>
 *   <li>Null constructor argument → {@link NullPointerException}</li>
 * </ul>
 */
class VertxProviderDecisionPointTest {

    // --- Fixtures ---

    private static AuthorityClaim roleClaim(String role) {
        return new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "", Map.of());
    }

    private static AuthorityClaim scopeClaim(String scope) {
        return new AuthorityClaim(AuthorityKind.SCOPE, scope, "", "", "", Map.of());
    }

    private static SecurityContext secCtxWith(Set<AuthorityClaim> claims) {
        SecurityContext ctx = mock(SecurityContext.class);
        AuthorizationClaims authzClaims = new AuthorizationClaims(claims, Map.of());
        when(ctx.authorization()).thenReturn(authzClaims);
        when(ctx.origin()).thenReturn(Optional.empty());
        return ctx;
    }

    private static AuthorizationRequest requestWithRoles(SecurityContext ctx, List<String> requiredRoles) {
        Map<String, Object> policyCtx =
                Map.of(VertxProviderDecisionPoint.CTX_REQUIRED_ROLES, List.copyOf(requiredRoles));
        return new AuthorizationRequest(ctx, "GET", new ResourceRef("route", "/api/test", Map.of()), policyCtx);
    }

    private static AuthorizationRequest requestWithScopes(
            SecurityContext ctx, List<String> requiredScopes, boolean requireAll) {
        Map<String, Object> policyCtx = Map.of(
                VertxProviderDecisionPoint.CTX_REQUIRED_SCOPES,
                List.copyOf(requiredScopes),
                VertxProviderDecisionPoint.CTX_REQUIRE_ALL_SCOPES,
                requireAll);
        return new AuthorizationRequest(ctx, "GET", new ResourceRef("route", "/api/test", Map.of()), policyCtx);
    }

    private static AuthorizationRequest requestWithRolesAndScopes(
            SecurityContext ctx, List<String> requiredRoles, List<String> requiredScopes, boolean requireAll) {
        Map<String, Object> policyCtx = Map.of(
                VertxProviderDecisionPoint.CTX_REQUIRED_ROLES,
                List.copyOf(requiredRoles),
                VertxProviderDecisionPoint.CTX_REQUIRED_SCOPES,
                List.copyOf(requiredScopes),
                VertxProviderDecisionPoint.CTX_REQUIRE_ALL_SCOPES,
                requireAll);
        return new AuthorizationRequest(ctx, "GET", new ResourceRef("route", "/api/test", Map.of()), policyCtx);
    }

    // --- Role checks ---

    @Nested
    @DisplayName("Role authorization checks")
    class RoleChecks {

        @Test
        @DisplayName("permit: caller has required role (OR semantics) → PERMITTED")
        void permitWhenRolePresent() {
            SecurityContext ctx = secCtxWith(Set.of(roleClaim("admin")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRoles(ctx, List.of("admin"));
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.succeeded(), "future must succeed");
            assertTrue(result.result().permitted(), "decision must be permitted");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_PERMITTED, result.result().reasonCode());
        }

        @Test
        @DisplayName("permit: caller has one of multiple required roles (OR semantics) → PERMITTED")
        void permitWhenOneOfMultipleRolesPresent() {
            SecurityContext ctx = secCtxWith(Set.of(roleClaim("manager")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRoles(ctx, List.of("admin", "manager"));
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.result().permitted(), "any matching role is sufficient");
        }

        @Test
        @DisplayName("deny: caller lacks required role → ROLE_MISSING")
        void denyWhenRoleMissing() {
            SecurityContext ctx = secCtxWith(Set.of(roleClaim("user")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRoles(ctx, List.of("admin"));
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.succeeded(), "future must succeed");
            assertFalse(result.result().permitted(), "decision must be denied");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_ROLE_MISSING,
                    result.result().reasonCode());
        }

        @Test
        @DisplayName("deny: caller has no roles at all → ROLE_MISSING")
        void denyWhenNoRolesGranted() {
            SecurityContext ctx = secCtxWith(Set.of());
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRoles(ctx, List.of("admin"));
            Future<AuthorizationDecision> result = dp.decide(request);

            assertFalse(result.result().permitted(), "no roles should be denied");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_ROLE_MISSING,
                    result.result().reasonCode());
        }
    }

    // --- Scope checks (OR semantics) ---

    @Nested
    @DisplayName("Scope authorization checks — OR semantics")
    class ScopeOrChecks {

        @Test
        @DisplayName("permit: caller has one of multiple required scopes (OR semantics) → PERMITTED")
        void permitWhenOneScopeMatchesInOrMode() {
            SecurityContext ctx = secCtxWith(Set.of(scopeClaim("read")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithScopes(ctx, List.of("read", "write"), false);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.result().permitted(), "any matching scope is sufficient in OR mode");
        }

        @Test
        @DisplayName("deny: caller has no matching scope (OR semantics) → SCOPE_INSUFFICIENT")
        void denyWhenNoScopeMatchesInOrMode() {
            SecurityContext ctx = secCtxWith(Set.of(scopeClaim("admin")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithScopes(ctx, List.of("read", "write"), false);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertFalse(result.result().permitted(), "no matching scope must be denied");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_SCOPE_INSUFFICIENT,
                    result.result().reasonCode());
        }
    }

    // --- Scope checks (AND semantics) ---

    @Nested
    @DisplayName("Scope authorization checks — AND semantics")
    class ScopeAndChecks {

        @Test
        @DisplayName("permit: caller has all required scopes (AND semantics) → PERMITTED")
        void permitWhenAllScopesMatchInAndMode() {
            SecurityContext ctx = secCtxWith(Set.of(scopeClaim("read"), scopeClaim("write")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithScopes(ctx, List.of("read", "write"), true);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.result().permitted(), "all scopes present must be permitted in AND mode");
        }

        @Test
        @DisplayName("deny: caller has only partial scopes (AND semantics) → SCOPE_MISSING")
        void denyWhenScopesPartialInAndMode() {
            SecurityContext ctx = secCtxWith(Set.of(scopeClaim("read")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithScopes(ctx, List.of("read", "write"), true);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertFalse(result.result().permitted(), "partial scope match must be denied in AND mode");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_SCOPE_MISSING,
                    result.result().reasonCode());
        }

        @Test
        @DisplayName("deny: caller has no scopes at all (AND semantics) → SCOPE_MISSING")
        void denyWhenNoScopesGrantedInAndMode() {
            SecurityContext ctx = secCtxWith(Set.of());
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithScopes(ctx, List.of("read", "write"), true);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertFalse(result.result().permitted(), "no scopes must be denied in AND mode");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_SCOPE_MISSING,
                    result.result().reasonCode());
        }
    }

    // --- Combined role AND scope ---

    @Nested
    @DisplayName("Combined role AND scope checks")
    class CombinedChecks {

        @Test
        @DisplayName("permit: caller satisfies both role and scope requirements → PERMITTED")
        void permitWhenBothRoleAndScopeSatisfied() {
            SecurityContext ctx = secCtxWith(Set.of(roleClaim("admin"), scopeClaim("write")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRolesAndScopes(ctx, List.of("admin"), List.of("write"), false);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.result().permitted(), "both constraints satisfied must be permitted");
        }

        @Test
        @DisplayName("deny: caller has role but not scope → SCOPE_INSUFFICIENT")
        void denyWhenRoleOkButScopeMissing() {
            SecurityContext ctx = secCtxWith(Set.of(roleClaim("admin")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRolesAndScopes(ctx, List.of("admin"), List.of("write"), false);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertFalse(result.result().permitted(), "missing scope must deny even when role is satisfied");
        }

        @Test
        @DisplayName("deny: caller has scope but not role → ROLE_MISSING")
        void denyWhenScopeOkButRoleMissing() {
            SecurityContext ctx = secCtxWith(Set.of(scopeClaim("write")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRolesAndScopes(ctx, List.of("admin"), List.of("write"), false);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertFalse(result.result().permitted(), "missing role must deny even when scope is satisfied");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_ROLE_MISSING,
                    result.result().reasonCode());
        }
    }

    // --- Pure evaluator: emits nothing (ADR-0114) ---

    @Nested
    @DisplayName("Pure evaluator: decide() emits no event (ADR-0114)")
    class PureEvaluator {

        @Test
        @DisplayName(
                "decide() returns a decision without any emitter dependency and never throws on missing correlation")
        void decideEmitsNothing() {
            // The decision point holds no emitter and no context holder — there is no path by which
            // it can emit. The only proof needed is that decide() completes synchronously and
            // returns the evaluated decision regardless of any ambient correlation state.
            SecurityContext ctx = secCtxWith(Set.of(roleClaim("admin")));
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            AuthorizationRequest request = requestWithRoles(ctx, List.of("admin"));
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.succeeded(), "future must succeed");
            assertTrue(result.result().permitted(), "decision must be the evaluated permit");
        }
    }

    // --- Constructor null rejection ---

    @Nested
    @DisplayName("Constructor null argument rejection")
    class ConstructorNullArguments {

        @Test
        @DisplayName("null providers throws NullPointerException")
        void nullProvidersThrows() {
            assertThrows(NullPointerException.class, () -> new VertxProviderDecisionPoint(null));
        }
    }

    // --- Null request ---

    @Nested
    @DisplayName("Null request rejection")
    class NullRequestRejection {

        @Test
        @DisplayName("null request throws NullPointerException")
        void nullRequestThrows() {
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            assertThrows(NullPointerException.class, () -> dp.decide(null));
        }
    }

    // --- No constraints → permitted ---

    @Nested
    @DisplayName("Empty constraint maps")
    class EmptyConstraints {

        @Test
        @DisplayName("no roles and no scopes in context → PERMITTED (no-op constraints)")
        void noConstraintsPermits() {
            SecurityContext ctx = secCtxWith(Set.of());
            VertxProviderDecisionPoint dp = new VertxProviderDecisionPoint(Set.of());

            // Empty context map = no constraints
            AuthorizationRequest request =
                    new AuthorizationRequest(ctx, "GET", new ResourceRef("route", "/", Map.of()), Map.of());
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.result().permitted(), "empty constraints must always permit");
            assertEquals(
                    VertxProviderDecisionPoint.REASON_PERMITTED, result.result().reasonCode());
        }
    }

    // --- AuthorizationProvider forwarded to VertxProviderDecisionPoint ---

    @Nested
    @DisplayName("AuthorizationProvider set injected for forward-compatibility")
    class ProviderInjection {

        @Test
        @DisplayName("AuthorizationProviders are accepted without error (no-op in current evaluation)")
        void providersAcceptedWithoutError() {
            AuthorizationProvider provider = mock(AuthorizationProvider.class);
            // Should not throw
            assertDoesNotThrow(() -> new VertxProviderDecisionPoint(Set.of(provider)));
        }
    }
}
