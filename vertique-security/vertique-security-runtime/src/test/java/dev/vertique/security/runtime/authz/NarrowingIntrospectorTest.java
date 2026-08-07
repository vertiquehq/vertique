// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationAssurance;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationGrantValidator;
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
import dev.vertique.security.runtime.InMemoryDelegationGrantValidator;
import io.vertx.core.Future;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NarrowingIntrospector}.
 *
 * <p>Pins that with no narrowers installed the decorator is byte-identical to the base
 * {@link dev.vertique.security.authz.AuthorizationIntrospector} it wraps: {@code allowedActions} is
 * unchanged and {@code capabilities} mirrors it with every requirement empty.
 */
class NarrowingIntrospectorTest {

    private static final ActionRef CMS_CONTENT_READ = ActionRef.of("cms", "content", "read");
    private static final ActionRef CMS_CONTENT_WRITE = ActionRef.of("cms", "content", "write");

    @Test
    @DisplayName("with no narrowers, allowedActions is unchanged and capabilities mirrors it with empty requirements")
    void emptySetIsByteIdentical() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ, CMS_CONTENT_WRITE);
        PolicyDefinitionSource source = source(allowPolicy("editor-policy", CMS_CONTENT_READ));
        RolePolicyResolver resolver = resolver(Map.of("editor", Set.of("editor-policy")));
        SecurityContext ctx = ctxWithRoles("editor");

        DefaultAuthorizationIntrospector base = new DefaultAuthorizationIntrospector(registry, source, resolver);
        NarrowingIntrospector wrapped = new NarrowingIntrospector(base, Set.of());

        Set<ActionRef> baseAllowed = base.allowedActions(ctx);
        Set<ActionRef> wrappedAllowed = wrapped.allowedActions(ctx);
        assertEquals(baseAllowed, wrappedAllowed, "allowedActions must be unchanged with no narrowers");

        Set<ActionCapability> expectedCapabilities = baseAllowed.stream()
                .map(action -> new ActionCapability(action, Set.of()))
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(expectedCapabilities, wrapped.capabilities(ctx));

        // Pinned equivalence: allowedActions(ctx) == capabilities(ctx).map(action)
        assertEquals(
                wrappedAllowed,
                wrapped.capabilities(ctx).stream()
                        .map(ActionCapability::action)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    @Test
    @DisplayName("capabilities reports the assurance requirement itself, not live satisfaction — a low-assurance "
            + "and a high-assurance ctx see the identical annotation for the same gated action")
    void capabilitiesReportRequirementNotSatisfaction() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ, CMS_CONTENT_WRITE);
        PolicyDefinitionSource source = source(allowPolicy("editor-policy", CMS_CONTENT_READ, CMS_CONTENT_WRITE));
        RolePolicyResolver resolver = resolver(Map.of("editor", Set.of("editor-policy")));

        AssuranceRequirement requirement = new AssuranceRequirement(3, Duration.ofMinutes(5));
        AssuranceRequirementConfig config =
                AssuranceRequirementConfig.fromJson(Map.of(CMS_CONTENT_WRITE.value(), requirement));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuthorizationNarrower narrower = new AssuranceRequirementNarrower(config, clock);

        DefaultAuthorizer baseAuthorizer = new DefaultAuthorizer(registry, source, resolver);
        DefaultAuthorizationIntrospector baseIntrospector =
                new DefaultAuthorizationIntrospector(registry, source, resolver);
        NarrowingAuthorizer authorizer = new NarrowingAuthorizer(baseAuthorizer, Set.of(narrower));
        NarrowingIntrospector introspector = new NarrowingIntrospector(baseIntrospector, Set.of(narrower));

        SecurityContext lowAssuranceCtx = ctxWithRolesAndAssurance("editor", Optional.empty());
        SecurityContext highAssuranceCtx = ctxWithRolesAndAssurance(
                "editor",
                Optional.of(new AuthenticationAssurance(
                        Optional.empty(), new LinkedHashSet<>(), Optional.of(NOW), Optional.of(5))));

        RequirementDescriptor expected = new RequirementDescriptor("ASSURANCE", "minProviderLevel=3, maxAgeMs=300000");
        assertEquals(
                Set.of(expected),
                capabilityFor(introspector, lowAssuranceCtx, CMS_CONTENT_WRITE).requirements(),
                "the low-assurance ctx must still see the requirement annotation");
        assertEquals(
                Set.of(expected),
                capabilityFor(introspector, highAssuranceCtx, CMS_CONTENT_WRITE).requirements(),
                "the high-assurance ctx must see the identical requirement annotation, not a satisfied/unsatisfied "
                        + "flavor of it");

        // The annotation is identical while the actual authorize()-time outcomes differ, proving
        // capabilities() reports the requirement, not live satisfaction.
        assertFalse(
                await(authorizer.authorize(lowAssuranceCtx, CMS_CONTENT_WRITE, RESOURCE))
                        .permitted(),
                "the low-assurance ctx must actually be denied at authorize-time");
        assertTrue(
                await(authorizer.authorize(highAssuranceCtx, CMS_CONTENT_WRITE, RESOURCE))
                        .permitted(),
                "the high-assurance ctx must actually be permitted at authorize-time");
    }

    @Test
    @DisplayName("capabilities() collects requirements from every narrower that reports one for the same action, "
            + "not just the first")
    void capabilityCarriesAllApplicableRequirements() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_WRITE);
        PolicyDefinitionSource source = source(allowPolicy("editor-policy", CMS_CONTENT_WRITE));
        RolePolicyResolver resolver = resolver(Map.of("editor", Set.of("editor-policy")));

        AssuranceRequirement assurance = new AssuranceRequirement(3, Duration.ofMinutes(5));
        AssuranceRequirementConfig assuranceConfig =
                AssuranceRequirementConfig.fromJson(Map.of(CMS_CONTENT_WRITE.value(), assurance));
        AuthorizationNarrower assuranceNarrower =
                new AssuranceRequirementNarrower(assuranceConfig, Clock.fixed(NOW, ZoneOffset.UTC));

        DelegationGrantValidator validator = new InMemoryDelegationGrantValidator(List.of());
        AuthorizationNarrower delegationNarrower = new DelegationEnforcementNarrower(validator);

        DefaultAuthorizationIntrospector baseIntrospector =
                new DefaultAuthorizationIntrospector(registry, source, resolver);
        NarrowingIntrospector introspector =
                new NarrowingIntrospector(baseIntrospector, Set.of(assuranceNarrower, delegationNarrower));

        SecurityContext ctx = delegatedCtxWithRoles("editor");

        Set<RequirementDescriptor> requirements =
                capabilityFor(introspector, ctx, CMS_CONTENT_WRITE).requirements();

        Set<String> kinds =
                requirements.stream().map(RequirementDescriptor::kind).collect(Collectors.toSet());
        assertEquals(
                Set.of("delegation", "ASSURANCE"),
                kinds,
                "capability must carry both the delegation and the assurance narrower's requirements, "
                        + "not just the first one folded");
    }

    @Test
    @DisplayName("allowedActions() and capabilities() both throw for a reconstructed SecurityContext")
    void reconstructedContextIntrospectionUnsupported() {
        DefaultActionRegistry registry = registryWith(CMS_CONTENT_READ);
        PolicyDefinitionSource source = source(allowPolicy("editor-policy", CMS_CONTENT_READ));
        RolePolicyResolver resolver = resolver(Map.of("editor", Set.of("editor-policy")));

        DefaultAuthorizationIntrospector base = new DefaultAuthorizationIntrospector(registry, source, resolver);
        NarrowingIntrospector introspector = new NarrowingIntrospector(base, Set.of());

        SecurityContext reconstructed = reconstructedCtxWithRoles("editor");

        assertThrows(
                ReconstructedContextIntrospectionUnsupportedException.class,
                () -> introspector.allowedActions(reconstructed),
                "allowedActions() must reject a reconstructed context");
        assertThrows(
                ReconstructedContextIntrospectionUnsupportedException.class,
                () -> introspector.capabilities(reconstructed),
                "capabilities() must reject a reconstructed context");
    }

    // --- helpers ---

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "doc-1", Map.of());

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    private static ActionCapability capabilityFor(
            NarrowingIntrospector introspector, SecurityContext ctx, ActionRef action) {
        return introspector.capabilities(ctx).stream()
                .filter(c -> c.action().equals(action))
                .findFirst()
                .orElseThrow();
    }

    private static SecurityContext ctxWithRolesAndAssurance(String role, Optional<AuthenticationAssurance> assurance) {
        Set<AuthorityClaim> claims = Set.of(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of()));
        AuthorizationClaims authz = new AuthorizationClaims(claims, Map.of());
        return new AssuranceStubSecurityContext(authz, assurance);
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
     * Builds a delegated {@link SecurityContext} (subject + non-deferred delegation present) carrying
     * the given roles, used by {@link #capabilityCarriesAllApplicableRequirements()} to exercise
     * {@link DelegationEnforcementNarrower#requirementFor}.
     */
    private static SecurityContext delegatedCtxWithRoles(String... roles) {
        Set<AuthorityClaim> claims = Arrays.stream(roles)
                .map(r -> new AuthorityClaim(AuthorityKind.ROLE, r, "", "", "test", Map.of()))
                .collect(Collectors.toUnmodifiableSet());
        AuthorizationClaims authz = new AuthorizationClaims(claims, Map.of());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        PrincipalRef subject = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
        SecurityIdentity identity = new SecurityIdentity(
                actor,
                Optional.of(subject),
                Optional.of(new DelegationContext("on-behalf-of", "grant-1", Optional.empty(), Map.of())),
                Optional.empty());
        return new DelegatedStubSecurityContext(identity, authz);
    }

    /**
     * Builds a reconstructed {@link SecurityContext} (via {@link SecurityContexts#assembleReconstructed})
     * carrying the given roles, used by {@link #reconstructedContextIntrospectionUnsupported()}.
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

    /**
     * Minimal {@link SecurityContext} stub carrying a controllable delegated {@link SecurityIdentity}
     * and {@link AuthorizationClaims}, used by {@link #capabilityCarriesAllApplicableRequirements()}.
     */
    private record DelegatedStubSecurityContext(SecurityIdentity identity, AuthorizationClaims authz)
            implements SecurityContext {
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

    /**
     * Minimal {@link SecurityContext} stub exposing a controllable {@link AuthorizationClaims} and
     * {@link AuthenticationAssurance}, used by {@link #capabilitiesReportRequirementNotSatisfaction()}.
     */
    private record AssuranceStubSecurityContext(AuthorizationClaims authz, Optional<AuthenticationAssurance> assurance)
            implements SecurityContext {
        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(DefaultAuthMethod.none(), List.of(), assurance, Optional.empty(), Map.of());
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
