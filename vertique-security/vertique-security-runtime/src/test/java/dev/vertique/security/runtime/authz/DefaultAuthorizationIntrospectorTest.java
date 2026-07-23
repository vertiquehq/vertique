// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultAuthorizationIntrospector}.
 *
 * <p>The introspector answers the inverse of a single {@link DefaultAuthorizer} decision: rather than
 * "is this one action permitted?", it returns "which of the registered actions are permitted for this
 * actor?". These tests pin that the returned set is exactly the subset the policies allow, that an
 * actor with no roles gets an empty set, that wildcard policies expand to every covered registered
 * action, and that the result is immutable.
 *
 * <p>The load-bearing test is {@link #allowedActions_agreesWithAuthorizer()} (AC-22): for the default
 * engine and a shared engine state, an action is in {@code allowedActions(ctx)} <strong>iff</strong>
 * {@link DefaultAuthorizer#authorize(SecurityContext, ActionRef, ResourceRef)} permits it. It asserts
 * this equivalence across the <em>whole</em> registry for several role/policy fixtures, so the
 * introspector and the authorizer cannot silently diverge.
 */
class DefaultAuthorizationIntrospectorTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");
    private static final ActionRef CMS_CONTENT_WRITE = ActionRef.of("cms", "content", "write");
    private static final ActionRef CMS_MEDIA_READ = ActionRef.of("cms", "media", "read");
    private static final ActionRef AUTHZ_ACTION_LIST = ActionRef.of("authz", "action", "list");

    /** A generic, instance-agnostic resource (empty id = "any instance of this type"). */
    private static final ResourceRef GENERIC_RESOURCE = new ResourceRef("any", "", Map.of());

    // --- allowed-subset behaviour ---

    @Test
    @DisplayName("returns only the registered actions the actor's policies allow")
    void allowedActions_returnsSubsetAllowedByRoles() {
        DefaultAuthorizationIntrospector introspector = new DefaultAuthorizationIntrospector(
                registryWith(CMS_CONTENT_READ, CMS_CONTENT_WRITE),
                source(allowPolicy("editor-policy", CMS_CONTENT_READ)),
                resolver(Map.of("editor", Set.of("editor-policy"))));

        Set<ActionRef> allowed = introspector.allowedActions(ctxWithRoles("editor"));

        assertEquals(Set.of(CMS_CONTENT_READ), allowed);
    }

    @Test
    @DisplayName("returns an empty set when the actor carries no ROLE claims")
    void allowedActions_noRoles_returnsEmpty() {
        DefaultAuthorizationIntrospector introspector = new DefaultAuthorizationIntrospector(
                registryWith(CMS_CONTENT_READ, CMS_CONTENT_WRITE),
                source(allowPolicy("editor-policy", CMS_CONTENT_READ)),
                resolver(Map.of("editor", Set.of("editor-policy"))));

        Set<ActionRef> allowed = introspector.allowedActions(ctxWithRoles());

        assertTrue(allowed.isEmpty(), "an actor with no roles must be allowed no actions");
    }

    @Test
    @DisplayName("expands a wildcard policy to every covered registered action")
    void allowedActions_wildcardPolicy_expandsAll() {
        DefaultAuthorizationIntrospector introspector = new DefaultAuthorizationIntrospector(
                registryWith(CMS_CONTENT_READ, CMS_CONTENT_WRITE, CMS_MEDIA_READ),
                source(wildcardPolicy("cms-admin-policy", "cms.*")),
                resolver(Map.of("admin", Set.of("cms-admin-policy"))));

        Set<ActionRef> allowed = introspector.allowedActions(ctxWithRoles("admin"));

        assertEquals(Set.of(CMS_CONTENT_READ, CMS_CONTENT_WRITE, CMS_MEDIA_READ), allowed);
    }

    // --- AC-22: agreement with the authorizer across the whole registry ---

    @Test
    @DisplayName("AC-22: for every registered action, allowedActions agrees with authorizer.authorize")
    void allowedActions_agreesWithAuthorizer() {
        // Several distinct role/policy fixtures, each exercised against the SAME full registry so the
        // equivalence is checked over every registered action, not just the allowed ones.
        ActionRegistry registry = registryWith(CMS_CONTENT_READ, CMS_CONTENT_WRITE, CMS_MEDIA_READ, AUTHZ_ACTION_LIST);

        // fixture 1: exact-match single action
        assertIntrospectorAgreesWithAuthorizer(
                registry,
                source(allowPolicy("reader-policy", CMS_CONTENT_READ)),
                resolver(Map.of("reader", Set.of("reader-policy"))),
                ctxWithRoles("reader"));

        // fixture 2: wildcard covering a subset of the registry
        assertIntrospectorAgreesWithAuthorizer(
                registry,
                source(wildcardPolicy("cms-policy", "cms.*")),
                resolver(Map.of("cms-admin", Set.of("cms-policy"))),
                ctxWithRoles("cms-admin"));

        // fixture 3: multiple roles, multiple policies, union of grants
        assertIntrospectorAgreesWithAuthorizer(
                registry,
                source(
                        allowPolicy("read-policy", CMS_CONTENT_READ, CMS_MEDIA_READ),
                        allowPolicy("authz-policy", AUTHZ_ACTION_LIST)),
                resolver(Map.of("reader", Set.of("read-policy"), "auditor", Set.of("authz-policy"))),
                ctxWithRoles("reader", "auditor"));

        // fixture 4: actor whose role maps to no policy → nothing allowed
        assertIntrospectorAgreesWithAuthorizer(
                registry,
                source(allowPolicy("reader-policy", CMS_CONTENT_READ)),
                resolver(Map.of("reader", Set.of("reader-policy"))),
                ctxWithRoles("stranger"));

        // fixture 5: actor with no roles at all → nothing allowed
        assertIntrospectorAgreesWithAuthorizer(
                registry,
                source(allowPolicy("reader-policy", CMS_CONTENT_READ)),
                resolver(Map.of("reader", Set.of("reader-policy"))),
                ctxWithRoles());
    }

    // --- immutability ---

    @Test
    @DisplayName("returns an immutable set")
    void allowedActions_returnsImmutableSet() {
        DefaultAuthorizationIntrospector introspector = new DefaultAuthorizationIntrospector(
                registryWith(CMS_CONTENT_READ),
                source(allowPolicy("editor-policy", CMS_CONTENT_READ)),
                resolver(Map.of("editor", Set.of("editor-policy"))));

        Set<ActionRef> allowed = introspector.allowedActions(ctxWithRoles("editor"));

        assertThrows(UnsupportedOperationException.class, () -> allowed.add(CMS_CONTENT_WRITE));
    }

    // --- helpers ---

    /**
     * Asserts the AC-22 equivalence for one fixture: built on the same engine state, the introspector's
     * allowed set must contain a registered action exactly when the authorizer permits that action.
     *
     * @param registry the shared action registry whose every action is checked
     * @param source   the policy source for this fixture
     * @param resolver the role→policy resolver for this fixture
     * @param ctx      the actor's security context for this fixture
     */
    private static void assertIntrospectorAgreesWithAuthorizer(
            ActionRegistry registry, PolicyDefinitionSource source, RolePolicyResolver resolver, SecurityContext ctx) {
        DefaultAuthorizer authorizer = new DefaultAuthorizer(registry, source, resolver);
        DefaultAuthorizationIntrospector introspector =
                new DefaultAuthorizationIntrospector(registry, source, resolver);

        Set<ActionRef> allowed = introspector.allowedActions(ctx);

        for (ActionDefinition def : registry.actions()) {
            ActionRef action = def.ref();
            boolean permittedByAuthorizer =
                    await(authorizer.authorize(ctx, action, GENERIC_RESOURCE)).permitted();
            boolean inAllowedSet = allowed.contains(action);
            assertEquals(
                    permittedByAuthorizer,
                    inAllowedSet,
                    "introspector/authorizer disagree on action " + action.value());
        }
    }

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        // The engine returns an already-completed future; read it synchronously.
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    private static DefaultActionRegistry registryWith(ActionRef... refs) {
        List<ActionDefinition> defs =
                Arrays.stream(refs).map(ActionDefinition::new).toList();
        return new DefaultActionRegistry(Set.of(new FixedActionContributor(defs)));
    }

    private static PolicyDefinition allowPolicy(String name, ActionRef... allowed) {
        Set<ActionPattern> patterns =
                Arrays.stream(allowed).map(r -> new ActionPattern(r.value())).collect(Collectors.toUnmodifiableSet());
        return new PolicyDefinition(name, List.of(new PolicyStatement(Effect.ALLOW, patterns)));
    }

    private static PolicyDefinition wildcardPolicy(String name, String pattern) {
        return new PolicyDefinition(
                name, List.of(new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern(pattern)))));
    }

    private static PolicyDefinitionSource source(PolicyDefinition... policies) {
        List<PolicyDefinition> list = List.of(policies);
        return () -> list;
    }

    private static RolePolicyResolver resolver(Map<String, Set<String>> mapping) {
        return roles -> roles.stream()
                .flatMap(role -> mapping.getOrDefault(role, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Builds a {@link SecurityContext} whose authorization claims hold exactly the given ROLE values. */
    private static SecurityContext ctxWithRoles(String... roles) {
        Set<AuthorityClaim> claims = Arrays.stream(roles)
                .map(r -> new AuthorityClaim(AuthorityKind.ROLE, r, "", "", "test", Map.of()))
                .collect(Collectors.toUnmodifiableSet());
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
