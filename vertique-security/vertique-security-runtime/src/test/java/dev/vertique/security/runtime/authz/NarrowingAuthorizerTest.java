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
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.RequirementDescriptor;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NarrowingAuthorizer}.
 *
 * <p>Pins two load-bearing properties of the narrower composition machinery: with no narrowers
 * installed the decorator is byte-identical to the base {@link Authorizer} it wraps, and the
 * no-widen guard — a narrower may only turn a permit into a deny (or annotate an existing deny),
 * never a deny into a permit.
 */
class NarrowingAuthorizerTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "", Map.of());

    // --- empty-set byte-identical corpus ---

    @Test
    @DisplayName("with no narrowers, decisions are byte-identical to the base authorizer across a permit+deny corpus")
    void emptySetIsByteIdentical() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ);
        PolicyDefinitionSource source = source(allowPolicy("admin-policy", CMS_CONTENT_READ));
        RolePolicyResolver resolver = resolver(Map.of("admin", Set.of("admin-policy")));
        DefaultAuthorizer base = new DefaultAuthorizer(registry, source, resolver);
        NarrowingAuthorizer wrapped = new NarrowingAuthorizer(base, Set.of());

        // permit case
        SecurityContext admin = ctxWithRoles("admin");
        AuthorizationRequest permitRequest = request(admin, CMS_CONTENT_READ);
        assertEquals(await(base.authorize(permitRequest)), await(wrapped.authorize(permitRequest)));
        assertEquals(
                await(base.authorize(admin, CMS_CONTENT_READ, RESOURCE)),
                await(wrapped.authorize(admin, CMS_CONTENT_READ, RESOURCE)));

        // deny case (role maps to no policy)
        SecurityContext viewer = ctxWithRoles("viewer");
        AuthorizationRequest denyRequest = request(viewer, CMS_CONTENT_READ);
        assertEquals(await(base.authorize(denyRequest)), await(wrapped.authorize(denyRequest)));
        assertEquals(
                await(base.authorize(viewer, CMS_CONTENT_READ, RESOURCE)),
                await(wrapped.authorize(viewer, CMS_CONTENT_READ, RESOURCE)));

        // fail-closed null-context case
        assertEquals(
                await(base.authorize(null, CMS_CONTENT_READ, RESOURCE)),
                await(wrapped.authorize(null, CMS_CONTENT_READ, RESOURCE)));
    }

    // --- no-widen guard ---

    @Test
    @DisplayName("a narrower cannot widen a base DENY to PERMIT — the base deny survives")
    void noWidenGuard() {
        AuthorizationDecision baseDeny = AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING);
        Authorizer base = fixedDecisionAuthorizer(baseDeny);
        AuthorizationNarrower widenAttempt = passthroughNarrower(
                0,
                "widen-attempt",
                (req, current) -> Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
        NarrowingAuthorizer wrapped = new NarrowingAuthorizer(base, Set.of(widenAttempt));

        AuthorizationDecision result = await(wrapped.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertFalse(result.permitted(), "a widen attempt must be discarded; the base deny must survive");
        assertEquals(baseDeny.reasonCode(), result.reasonCode());
    }

    @Test
    @DisplayName("a narrower may narrow a base PERMIT to DENY")
    void narrowerCanNarrowPermitToDeny() {
        Authorizer base = fixedDecisionAuthorizer(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        AuthorizationNarrower narrower = passthroughNarrower(
                0, "narrow-to-deny", (req, current) -> Future.succeededFuture(AuthorizationDecision.deny("NARROWED")));
        NarrowingAuthorizer wrapped = new NarrowingAuthorizer(base, Set.of(narrower));

        AuthorizationDecision result = await(wrapped.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertFalse(result.permitted());
        assertEquals("NARROWED", result.reasonCode());
    }

    @Test
    @DisplayName("a narrower that returns its input unchanged does not alter the base decision")
    void passthroughNarrowerLeavesDecisionUnchanged() {
        AuthorizationDecision basePermit = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);
        Authorizer base = fixedDecisionAuthorizer(basePermit);
        AuthorizationNarrower noop = passthroughNarrower(0, "noop", (req, current) -> Future.succeededFuture(current));
        NarrowingAuthorizer wrapped = new NarrowingAuthorizer(base, Set.of(noop));

        AuthorizationDecision result = await(wrapped.authorize(request(ctxWithRoles("admin"), CMS_CONTENT_READ)));

        assertEquals(basePermit, result);
    }

    // --- helpers ---

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    private static AuthorizationRequest request(SecurityContext ctx, ActionRef action) {
        return new AuthorizationRequest(ctx, action.value(), RESOURCE, Map.of());
    }

    private static Authorizer fixedDecisionAuthorizer(AuthorizationDecision decision) {
        return new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest req) {
                Objects.requireNonNull(req, "req");
                return Future.succeededFuture(decision);
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                Objects.requireNonNull(action, "action");
                Objects.requireNonNull(resource, "resource");
                return Future.succeededFuture(decision);
            }
        };
    }

    private static AuthorizationNarrower passthroughNarrower(
            int priority,
            String orderKey,
            BiFunction<AuthorizationRequest, AuthorizationDecision, Future<AuthorizationDecision>> narrowFn) {
        return new FunctionalNarrower(priority, orderKey, narrowFn);
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

    private static PolicyDefinitionSource source(PolicyDefinition... policies) {
        List<PolicyDefinition> list = List.of(policies);
        return () -> list;
    }

    private static RolePolicyResolver resolver(Map<String, Set<String>> mapping) {
        return roles -> roles.stream()
                .flatMap(role -> mapping.getOrDefault(role, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

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

    /** Configurable {@link AuthorizationNarrower} test double with a fixed priority/orderKey. */
    private record FunctionalNarrower(
            int priority,
            String orderKey,
            BiFunction<AuthorizationRequest, AuthorizationDecision, Future<AuthorizationDecision>> narrowFn)
            implements AuthorizationNarrower {

        @Override
        public Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base) {
            return narrowFn.apply(request, base);
        }

        @Override
        public Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action) {
            return Optional.empty();
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
