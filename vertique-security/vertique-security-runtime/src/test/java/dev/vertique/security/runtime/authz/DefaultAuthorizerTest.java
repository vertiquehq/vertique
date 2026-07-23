// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultAuthorizer}.
 *
 * <p>The default engine is a pure function of (actor roles, requested action): it reads ROLE claims
 * off the {@link SecurityContext}, maps them to policy names via {@link RolePolicyResolver}, resolves
 * those names against the {@link PolicyDefinitionSource} catalogue, and permits iff some
 * {@link Effect#ALLOW} statement's {@link ActionPattern} matches the action. These tests pin the
 * exact {@link AuthzReasonCodes} reason code produced on each decision path, that the engine
 * <strong>never emits</strong> (it only returns a completed {@link Future}), and that it
 * <strong>fails closed</strong> (a resolver exception or a null context yields a deny, not a thrown
 * exception). Fakes implement the SPIs directly; the real {@link DefaultActionRegistry} is used.
 */
class DefaultAuthorizerTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "", Map.of());

    // --- permit ---

    @Test
    @DisplayName("permits when a role maps to a policy that allows the action")
    void permit_roleHasPolicyAllowingAction() {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ),
                source(allowPolicy("admin-policy", CMS_CONTENT_READ)),
                resolver(Map.of("admin", Set.of("admin-policy"))));

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertTrue(decision.permitted());
        assertEquals(AuthzReasonCodes.PERMITTED, decision.reasonCode());
    }

    // --- deny paths, each pinned to a specific reason code ---

    @Test
    @DisplayName("denies ROLE_POLICY_MISSING when the actor's role maps to no policy")
    void deny_roleHasNoPolicyMapping() {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ),
                source(allowPolicy("admin-policy", CMS_CONTENT_READ)),
                resolver(Map.of("admin", Set.of("admin-policy"))));

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles("viewer"), CMS_CONTENT_READ)));

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.ROLE_POLICY_MISSING, decision.reasonCode());
    }

    @Test
    @DisplayName("denies ACTION_NOT_ALLOWED when a mapped policy exists but no statement allows the action")
    void deny_policyExistsButNoMatchingStatement() {
        ActionRef cmsContentWrite = ActionRef.of("cms", "content", "write");
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ, cmsContentWrite),
                // policy only allows write, request asks for read
                source(allowPolicy("admin-policy", cmsContentWrite)),
                resolver(Map.of("admin", Set.of("admin-policy"))));

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.ACTION_NOT_ALLOWED, decision.reasonCode());
    }

    @Test
    @DisplayName("denies ACTION_NOT_REGISTERED when the requested action is not in the registry")
    void deny_actionNotRegistered() {
        ActionRef unregistered = ActionRef.of("cms", "content", "read");
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(ActionRef.of("cms", "content", "write")), // registry has a different action
                source(allowPolicy("admin-policy", ActionRef.of("cms", "content", "write"))),
                resolver(Map.of("admin", Set.of("admin-policy"))));

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles("admin"), unregistered)));

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.ACTION_NOT_REGISTERED, decision.reasonCode());
    }

    @Test
    @DisplayName("denies ROLE_MISSING when the security context carries no ROLE claims")
    void deny_noRolesOnSecurityContext() {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ),
                source(allowPolicy("admin-policy", CMS_CONTENT_READ)),
                resolver(Map.of("admin", Set.of("admin-policy"))));

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles(), CMS_CONTENT_READ)));

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.ROLE_MISSING, decision.reasonCode());
    }

    @Test
    @DisplayName("denies POLICY_NOT_FOUND when a mapped policy name is absent from the source")
    void deny_policyNotFound_byName() {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ),
                // source has no policy named "ghost-policy"
                source(allowPolicy("admin-policy", CMS_CONTENT_READ)),
                resolver(Map.of("admin", Set.of("ghost-policy"))));

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.POLICY_NOT_FOUND, decision.reasonCode());
    }

    // --- fail-closed ---

    @Test
    @DisplayName("fails closed with INTERNAL_AUTHZ_ERROR when the resolver throws (no exception propagated)")
    void deny_internalError_closedSafely() {
        RolePolicyResolver throwing = roles -> {
            throw new RuntimeException("boom");
        };
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ), source(allowPolicy("p", CMS_CONTENT_READ)), throwing);

        AuthorizationDecision decision = await(authorizer.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR, decision.reasonCode());
    }

    @Test
    @DisplayName("fails closed (denied) when the security context is null (convenience overload)")
    void failClosed_nullSecurityContext_denied() {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ),
                source(allowPolicy("admin-policy", CMS_CONTENT_READ)),
                resolver(Map.of("admin", Set.of("admin-policy"))));

        // The convenience overload is the public entry point that can receive a null context before
        // any AuthorizationRequest is built (the record's compact ctor forbids a null context). The
        // engine must fail closed rather than throw.
        AuthorizationDecision decision = await(authorizer.authorize(null, CMS_CONTENT_READ, RESOURCE));

        assertFalse(decision.permitted());
    }

    // --- convenience overload ---

    @Test
    @DisplayName("action-only overload delegates with action == ActionRef.value()")
    void convenienceOverload_matchesBothFormsWith_actionRef() {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(
                registryWith(CMS_CONTENT_READ),
                source(allowPolicy("admin-policy", CMS_CONTENT_READ)),
                resolver(Map.of("admin", Set.of("admin-policy"))));
        SecurityContext ctx = ctxWithRoles("admin");

        AuthorizationDecision viaOverload = await(authorizer.authorize(ctx, CMS_CONTENT_READ, RESOURCE));
        AuthorizationDecision viaRequest = await(authorizer.authorize(request(ctx, CMS_CONTENT_READ)));

        assertEquals(viaRequest.permitted(), viaOverload.permitted());
        assertEquals(viaRequest.reasonCode(), viaOverload.reasonCode());
        assertTrue(viaOverload.permitted());
        assertEquals(AuthzReasonCodes.PERMITTED, viaOverload.reasonCode());
    }

    // --- helpers ---

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        // The engine returns an already-completed future; read it synchronously.
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    private static AuthorizationRequest request(SecurityContext ctx, ActionRef action) {
        return new AuthorizationRequest(ctx, action.value(), RESOURCE, Map.of());
    }

    private static DefaultActionRegistry registryWith(ActionRef... refs) {
        List<ActionDefinition> defs =
                java.util.Arrays.stream(refs).map(ActionDefinition::new).toList();
        return new DefaultActionRegistry(Set.of(new FixedActionContributor(defs)));
    }

    private static PolicyDefinition allowPolicy(String name, ActionRef... allowed) {
        Set<ActionPattern> patterns = java.util.Arrays.stream(allowed)
                .map(r -> new ActionPattern(r.value()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PolicyDefinition(name, List.of(new PolicyStatement(Effect.ALLOW, patterns)));
    }

    private static PolicyDefinitionSource source(PolicyDefinition... policies) {
        List<PolicyDefinition> list = List.of(policies);
        return () -> list;
    }

    private static RolePolicyResolver resolver(Map<String, Set<String>> mapping) {
        return roles -> roles.stream()
                .flatMap(role -> mapping.getOrDefault(role, Set.of()).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Builds a {@link SecurityContext} whose authorization claims hold exactly the given ROLE values. */
    private static SecurityContext ctxWithRoles(String... roles) {
        Set<AuthorityClaim> claims = java.util.Arrays.stream(roles)
                .map(r -> new AuthorityClaim(AuthorityKind.ROLE, r, "", "", "test", Map.of()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        AuthorizationClaims authz = new AuthorizationClaims(claims, Map.of());
        return new StubSecurityContext(authz);
    }

    /** Contributor returning a fixed list of definitions. */
    private record FixedActionContributor(List<ActionDefinition> definitions) implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return definitions;
        }
    }

    /** Minimal {@link SecurityContext} stub exposing a controllable {@link AuthorizationClaims}. */
    private record StubSecurityContext(AuthorizationClaims authz) implements SecurityContext {
        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return authz;
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }
}
