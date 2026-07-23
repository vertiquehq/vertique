// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionCapability;
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
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.ReconstructedContextIntrospectionUnsupportedException;
import dev.vertique.security.authz.RequirementDescriptor;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Load-bearing conformance test pinning how {@link NarrowingAuthorizer} and
 * {@link NarrowingIntrospector} agree once an {@link AuthorizationNarrower} is installed.
 *
 * <p><strong>Scope: non-reconstructed contexts only.</strong> Every equivalence this test pins
 * ({@code narrow}-deny &hArr; {@code capabilities}-annotation) is asserted for a
 * <strong>non-reconstructed</strong> {@link SecurityContext} — one whose
 * {@link SecurityContext#reconstruction()} is empty. A <strong>reconstructed</strong> context is
 * outside that equivalence entirely: {@link NarrowingIntrospector} rejects introspection of one
 * outright with {@link ReconstructedContextIntrospectionUnsupportedException} (see
 * {@link #introspectionRejectsReconstructedContext()}), since a reconstructed context's current
 * authority is resolved live at authorize-time (Mode 2) rather than read off the context's currently
 * held claims — there is no stable capability set to annotate.
 *
 * <p><strong>Design note (annotate, not exclude).</strong> {@link NarrowingIntrospector#capabilities}
 * is an <em>annotation</em> over the base introspector's allowed-action set — a narrower can only
 * attach {@link RequirementDescriptor}s to a capability, it can never remove the capability from the
 * result (the Contract Appendix pins {@code allowedActions(ctx) == capabilities(ctx).map(action)},
 * which only holds if capabilities never filters). Introspection has no concrete
 * {@link ResourceRef} to evaluate a narrower's {@link AuthorizationNarrower#narrow} decision against,
 * so a non-empty requirement set means "this capability exists but is conditionally gated" — a caller
 * still calls {@link NarrowingAuthorizer#authorize} for the resource-specific verdict. This test pins
 * both halves of that relationship: the requirement annotation exactly mirrors
 * {@link AuthorizationNarrower#requirementFor}, and a narrower that denies a gated action at
 * authorize-time does so consistently with (not contradicted by) that annotation.
 */
class AgreementInvariantConformanceTest {

    private static final ActionRef GATED_ACTION = ActionRef.of("cms", "content", "delete");
    private static final ActionRef UNGATED_ACTION = ActionRef.of("cms", "content", "read");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "doc-1", Map.of());
    private static final RequirementDescriptor DELEGATION_REQUIREMENT =
            new RequirementDescriptor("DELEGATION_GRANT", "requires an active delegation grant");

    @Test
    @DisplayName("capabilities' requirement annotation matches requirementFor, and narrow-deny is consistent with it")
    void authorizerAndIntrospectorAgreeUnderNarrower() {
        // Base: both actions are permitted by the plain role/policy engine.
        DefaultActionRegistry registry = registryWith(GATED_ACTION, UNGATED_ACTION);
        PolicyDefinitionSource source = source(allowPolicy("admin-policy", GATED_ACTION, UNGATED_ACTION));
        RolePolicyResolver resolver = resolver(Map.of("admin", Set.of("admin-policy")));
        SecurityContext ctx = ctxWithRoles("admin");

        DefaultAuthorizer baseAuthorizer = new DefaultAuthorizer(registry, source, resolver);
        DefaultAuthorizationIntrospector baseIntrospector =
                new DefaultAuthorizationIntrospector(registry, source, resolver);

        AuthorizationNarrower narrower = gatedDelegationNarrower();

        NarrowingAuthorizer authorizer = new NarrowingAuthorizer(baseAuthorizer, Set.of(narrower));
        NarrowingIntrospector introspector = new NarrowingIntrospector(baseIntrospector, Set.of(narrower));

        Set<ActionCapability> capabilities = introspector.capabilities(ctx);
        Map<ActionRef, ActionCapability> byAction =
                capabilities.stream().collect(Collectors.toMap(ActionCapability::action, c -> c));

        // (1) capabilities' requirement annotation matches requirementFor for every action, for both
        //     the gated and the unconstrained action.
        for (ActionRef action : List.of(GATED_ACTION, UNGATED_ACTION)) {
            assertEquals(
                    asRequirementSet(narrower.requirementFor(ctx, action)),
                    byAction.get(action).requirements(),
                    "capabilities requirement annotation must match requirementFor for " + action.value());
        }

        // (2) the requirement is present exactly for the gated action.
        assertFalse(byAction.get(GATED_ACTION).requirements().isEmpty(), "gated action must carry a requirement");
        assertTrue(
                byAction.get(UNGATED_ACTION).requirements().isEmpty(),
                "unconstrained action must carry no requirement");

        // (3) pinned equivalence: allowedActions(ctx) == capabilities(ctx).map(action).
        assertEquals(byAction.keySet(), introspector.allowedActions(ctx));

        // (4) at authorize-time, the narrower denies the gated action (narrow() takes effect) while
        //     the unconstrained action is unaffected. The gated action REMAINS a member of
        //     allowedActions/capabilities per the annotate-only design above: the introspector
        //     surfaces "you have this capability, but it is gated" — the concrete, resource-specific
        //     enforcement of that gate happens here, at authorize-time.
        assertFalse(
                await(authorizer.authorize(ctx, GATED_ACTION, RESOURCE)).permitted(),
                "narrow() must deny the gated action");
        assertTrue(
                await(authorizer.authorize(ctx, UNGATED_ACTION, RESOURCE)).permitted(),
                "the unconstrained action is unaffected");
    }

    @Test
    @DisplayName("capabilities' requirement annotation matches requirementFor, and narrow-deny is consistent with "
            + "it, under the real AssuranceRequirementNarrower")
    void authorizerAndIntrospectorAgreeUnderAssuranceNarrower() {
        // Base: both actions are permitted by the plain role/policy engine.
        DefaultActionRegistry registry = registryWith(GATED_ACTION, UNGATED_ACTION);
        PolicyDefinitionSource source = source(allowPolicy("admin-policy", GATED_ACTION, UNGATED_ACTION));
        RolePolicyResolver resolver = resolver(Map.of("admin", Set.of("admin-policy")));
        // No AuthenticationAssurance at all — fails any configured minimum-assurance gate.
        SecurityContext ctx = ctxWithRoles("admin");

        DefaultAuthorizer baseAuthorizer = new DefaultAuthorizer(registry, source, resolver);
        DefaultAuthorizationIntrospector baseIntrospector =
                new DefaultAuthorizationIntrospector(registry, source, resolver);

        AuthorizationNarrower narrower = gatedAssuranceNarrower();

        NarrowingAuthorizer authorizer = new NarrowingAuthorizer(baseAuthorizer, Set.of(narrower));
        NarrowingIntrospector introspector = new NarrowingIntrospector(baseIntrospector, Set.of(narrower));

        Set<ActionCapability> capabilities = introspector.capabilities(ctx);
        Map<ActionRef, ActionCapability> byAction =
                capabilities.stream().collect(Collectors.toMap(ActionCapability::action, c -> c));

        // (1) capabilities' requirement annotation matches requirementFor for every action, for both
        //     the gated and the unconstrained action.
        for (ActionRef action : List.of(GATED_ACTION, UNGATED_ACTION)) {
            assertEquals(
                    asRequirementSet(narrower.requirementFor(ctx, action)),
                    byAction.get(action).requirements(),
                    "capabilities requirement annotation must match requirementFor for " + action.value());
        }

        // (2) the requirement is present exactly for the gated action.
        assertFalse(byAction.get(GATED_ACTION).requirements().isEmpty(), "gated action must carry a requirement");
        assertTrue(
                byAction.get(UNGATED_ACTION).requirements().isEmpty(),
                "unconstrained action must carry no requirement");

        // (3) pinned equivalence: allowedActions(ctx) == capabilities(ctx).map(action).
        assertEquals(byAction.keySet(), introspector.allowedActions(ctx));

        // (4) at authorize-time, the real assurance narrower denies the gated action for an
        //     unassured ctx (narrow() takes effect) while the unconstrained action is unaffected. The
        //     gated action REMAINS a member of allowedActions/capabilities per the annotate-only
        //     design above.
        assertFalse(
                await(authorizer.authorize(ctx, GATED_ACTION, RESOURCE)).permitted(),
                "narrow() must deny the gated action for a ctx with no AuthenticationAssurance");
        assertTrue(
                await(authorizer.authorize(ctx, UNGATED_ACTION, RESOURCE)).permitted(),
                "the unconstrained action is unaffected");
    }

    @Test
    @DisplayName("introspection is unsupported for a reconstructed context, even with a narrower installed")
    void introspectionRejectsReconstructedContext() {
        DefaultActionRegistry registry = registryWith(GATED_ACTION, UNGATED_ACTION);
        PolicyDefinitionSource source = source(allowPolicy("admin-policy", GATED_ACTION, UNGATED_ACTION));
        RolePolicyResolver resolver = resolver(Map.of("admin", Set.of("admin-policy")));

        DefaultAuthorizationIntrospector baseIntrospector =
                new DefaultAuthorizationIntrospector(registry, source, resolver);
        AuthorizationNarrower narrower = gatedDelegationNarrower();
        NarrowingIntrospector introspector = new NarrowingIntrospector(baseIntrospector, Set.of(narrower));

        SecurityContext reconstructed = reconstructedCtxWithRoles("admin");

        assertThrows(
                ReconstructedContextIntrospectionUnsupportedException.class,
                () -> introspector.allowedActions(reconstructed),
                "allowedActions() must reject a reconstructed context even with a narrower installed");
        assertThrows(
                ReconstructedContextIntrospectionUnsupportedException.class,
                () -> introspector.capabilities(reconstructed),
                "capabilities() must reject a reconstructed context even with a narrower installed");
    }

    /**
     * A real {@link AssuranceRequirementNarrower} gating {@link #GATED_ACTION} with a minimum
     * assurance requirement, used to prove {@link AgreementInvariantConformanceTest}'s invariant
     * holds for the concrete identity-002 narrower, not just the test-only stub above.
     *
     * @return an {@link AssuranceRequirementNarrower} over a fixed clock
     */
    private static AuthorizationNarrower gatedAssuranceNarrower() {
        AssuranceRequirementConfig config = AssuranceRequirementConfig.fromJson(
                Map.of(GATED_ACTION.value(), new AssuranceRequirement(1, Duration.ofMinutes(5))));
        return new AssuranceRequirementNarrower(
                config, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    // --- test narrower ---

    /**
     * A test-only {@link AuthorizationNarrower} proving the composition machinery: it reports a
     * {@link #DELEGATION_REQUIREMENT} for {@link #GATED_ACTION} only, and denies that same action at
     * authorize-time regardless of resource, passing every other action through unchanged.
     */
    private static AuthorizationNarrower gatedDelegationNarrower() {
        return new AuthorizationNarrower() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public String orderKey() {
                return "gated-delegation-narrower";
            }

            @Override
            public Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base) {
                if (request.action().equals(GATED_ACTION.value())) {
                    return Future.succeededFuture(AuthorizationDecision.deny("DELEGATION_REQUIRED"));
                }
                return Future.succeededFuture(base);
            }

            @Override
            public Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action) {
                return action.equals(GATED_ACTION) ? Optional.of(DELEGATION_REQUIREMENT) : Optional.empty();
            }
        };
    }

    // --- helpers ---

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
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

    /**
     * Builds a reconstructed {@link SecurityContext} (via {@link SecurityContexts#assembleReconstructed})
     * carrying the given roles, used by {@link #introspectionRejectsReconstructedContext()}.
     */
    private static SecurityContext reconstructedCtxWithRoles(String... roles) {
        Set<AuthorityClaim> claims = Arrays.stream(roles)
                .map(r -> new AuthorityClaim(AuthorityKind.ROLE, r, "", "", "test", Map.of()))
                .collect(Collectors.toUnmodifiableSet());
        AuthorizationClaims authz = new AuthorizationClaims(claims, Map.of());
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        ReconstructionMarker marker = new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY);
        return SecurityContexts.assembleReconstructed(identity, auth, authz, Optional.empty(), marker);
    }

    /**
     * Converts a single-narrower {@code requirementFor} result into the {@link Set} shape
     * {@link ActionCapability#requirements()} now carries, for comparison in these single-narrower
     * fixtures.
     *
     * @param requirement the optional requirement a single narrower reports
     * @return an empty set when {@code requirement} is empty, otherwise a singleton set of it
     */
    private static Set<RequirementDescriptor> asRequirementSet(Optional<RequirementDescriptor> requirement) {
        return requirement.map(Set::of).orElseGet(Set::of);
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
