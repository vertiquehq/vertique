// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationGrantValidator;
import dev.vertique.security.DelegationReasonCodes;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationIntrospector;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.InMemoryDelegationGrantValidator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityAuthzModule} Dagger wiring.
 *
 * <p>Verifies the Dagger graph produced by {@link SecurityAuthzModule}:
 * <ul>
 *   <li>The {@link BuiltinAuthzActionContributor} is bound into the {@link ActionRegistry}, which
 *       therefore contains the built-in framework actions {@code authz.action.list} and
 *       {@code authz.action.introspect}.</li>
 *   <li>The Dagger component can be created without throwing (startup ordering: registry is built
 *       before {@link Authorizer} and {@link AuthorizationIntrospector}).</li>
 *   <li>The opt-in {@link DelegationEnforcementModule} — installed alongside this module by
 *       {@code DelegationEnforcementComponent} below — contributes {@link DelegationEnforcementNarrower}
 *       into the {@code Set<AuthorizationNarrower>} multibinding this module declares, and that
 *       contribution actually folds into the wired {@link Authorizer} (not merely present in the
 *       set).</li>
 * </ul>
 *
 * <p>These tests use a minimal Dagger {@link TestComponent} that includes only
 * {@link SecurityAuthzModule} so that the bindings introduced in this slice are exercised in
 * isolation, without any framework REST or auth dependencies.
 */
class SecurityAuthzModuleTest {

    // --- test Dagger component ---

    /**
     * Provides a bare {@link Vertx} instance for the test components below — {@link
     * SecurityAuthzModule#authorizer} now needs {@link Vertx} to bound every wrapped {@code
     * PrincipalAuthorityResolver} with a timeout, even when no resolver is actually installed.
     */
    @Module
    interface TestVertxModule {
        /**
         * Provides a bare {@link Vertx} instance.
         *
         * @return a new {@link Vertx} instance; never {@code null}
         */
        @Provides
        @Singleton
        static Vertx vertx() {
            return Vertx.vertx();
        }
    }

    /** Minimal component including only {@link SecurityAuthzModule}. */
    @Singleton
    @Component(modules = {SecurityAuthzModule.class, TestVertxModule.class})
    interface TestComponent {

        /** Exposes the {@link ActionRegistry} built by the module. */
        ActionRegistry actionRegistry();

        /** Exposes the {@link Authorizer} built by the module. */
        Authorizer authorizer();

        /** Exposes the {@link AuthorizationIntrospector} built by the module. */
        AuthorizationIntrospector authorizationIntrospector();
    }

    // --- built-in action registration ---

    @Test
    @DisplayName("ActionRegistry contains authz.action.list contributed by the built-in contributor")
    void builtinContributor_providesAuthzActionList() {
        TestComponent component = DaggerSecurityAuthzModuleTest_TestComponent.create();
        ActionRegistry registry = component.actionRegistry();

        ActionRef actionList = ActionRef.of("authz", "action", "list");
        assertTrue(
                registry.contains(actionList),
                "ActionRegistry must contain authz.action.list from BuiltinAuthzActionContributor");
    }

    @Test
    @DisplayName("ActionRegistry contains authz.action.introspect contributed by the built-in contributor")
    void builtinContributor_providesAuthzActionIntrospect() {
        TestComponent component = DaggerSecurityAuthzModuleTest_TestComponent.create();
        ActionRegistry registry = component.actionRegistry();

        ActionRef actionIntrospect = ActionRef.of("authz", "action", "introspect");
        assertTrue(
                registry.contains(actionIntrospect),
                "ActionRegistry must contain authz.action.introspect from BuiltinAuthzActionContributor");
    }

    @Test
    @DisplayName("ActionRegistry contains exactly the two built-in actions when no external contributors")
    void builtinContributor_exactlyTwoActions() {
        TestComponent component = DaggerSecurityAuthzModuleTest_TestComponent.create();
        ActionRegistry registry = component.actionRegistry();

        Collection<ActionDefinition> actions = registry.actions();
        assertTrue(actions.size() >= 2, "ActionRegistry must contain at least 2 built-in actions");
    }

    // --- startup ordering: registry built before authorizer ---

    @Test
    @DisplayName("component creates without exception (startup ordering: registry before authorizer)")
    void startupOrdering_registryBuiltBeforeAuthorizer() {
        // If startup ordering is wrong (e.g. Authorizer constructed before ActionRegistry), this will
        // throw an exception. Successful component creation proves the ordering constraint is met.
        TestComponent component = DaggerSecurityAuthzModuleTest_TestComponent.create();

        assertNotNull(component.actionRegistry(), "ActionRegistry must be non-null");
        assertNotNull(component.authorizer(), "Authorizer must be non-null");
        assertNotNull(component.authorizationIntrospector(), "AuthorizationIntrospector must be non-null");
    }

    // --- duplicate policy name across sources (FR-018) ---

    @Test
    @DisplayName("duplicate policy name across two sources fails component creation, naming both sources")
    void duplicatePolicyName_acrossSources_throwsNamingBothSources() {
        // Two distinct PolicyDefinitionSource classes both contribute a policy named "shared". The
        // merge must fail fast rather than silently last-wins. The merge runs lazily inside the
        // @Singleton AuthzResolution, so calling authorizer() is what forces it to build and throw.
        DuplicateNameComponent component = DaggerSecurityAuthzModuleTest_DuplicateNameComponent.create();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                component::authorizer,
                "merging two sources with the same policy name must fail fast");

        String message = ex.getMessage();
        assertTrue(message.contains("shared"), "message must name the duplicated policy: " + message);
        assertTrue(
                message.contains(FirstSharedSource.class.getName()),
                "message must name the first contributing source: " + message);
        assertTrue(
                message.contains(SecondSharedSource.class.getName()),
                "message must name the second contributing source: " + message);
    }

    @Test
    @DisplayName("two sources with distinct policy names merge without error")
    void distinctPolicyNames_acrossSources_mergeOk() {
        // Distinct names must NOT trip the duplicate check; component creation succeeds.
        DistinctNameComponent component = DaggerSecurityAuthzModuleTest_DistinctNameComponent.create();
        assertNotNull(component.authorizer(), "Authorizer must be built when policy names are distinct");
    }

    // --- duplicate-name test wiring ---

    /** A policy source contributing one ALLOW policy with the given name over {@code authz.action.list}. */
    private static PolicyDefinition sharedPolicy(String name) {
        return new PolicyDefinition(
                name,
                List.of(new PolicyStatement(Effect.ALLOW, java.util.Set.of(new ActionPattern("authz.action.list")))));
    }

    /** First test {@link PolicyDefinitionSource} contributing a policy named {@code "shared"}. */
    static final class FirstSharedSource implements PolicyDefinitionSource {
        @Override
        public Collection<PolicyDefinition> policies() {
            return List.of(sharedPolicy("shared"));
        }
    }

    /** Second test {@link PolicyDefinitionSource} also contributing a policy named {@code "shared"}. */
    static final class SecondSharedSource implements PolicyDefinitionSource {
        @Override
        public Collection<PolicyDefinition> policies() {
            return List.of(sharedPolicy("shared"));
        }
    }

    /** Second test {@link PolicyDefinitionSource} contributing a policy with a distinct name. */
    static final class DistinctSource implements PolicyDefinitionSource {
        @Override
        public Collection<PolicyDefinition> policies() {
            return List.of(sharedPolicy("distinct"));
        }
    }

    /** Module contributing two sources that share a policy name. */
    @Module
    interface DuplicateNameModule {
        /** Contributes the first shared-name source. */
        @Provides
        @IntoSet
        static PolicyDefinitionSource first() {
            return new FirstSharedSource();
        }

        /** Contributes the second shared-name source. */
        @Provides
        @IntoSet
        static PolicyDefinitionSource second() {
            return new SecondSharedSource();
        }
    }

    /** Module contributing two sources with distinct policy names. */
    @Module
    interface DistinctNameModule {
        /** Contributes a shared-name source (its single name is unique within this module). */
        @Provides
        @IntoSet
        static PolicyDefinitionSource first() {
            return new FirstSharedSource();
        }

        /** Contributes a source with a different policy name. */
        @Provides
        @IntoSet
        static PolicyDefinitionSource distinct() {
            return new DistinctSource();
        }
    }

    /** Component that should fail creation due to a duplicate policy name across sources. */
    @Singleton
    @Component(modules = {SecurityAuthzModule.class, DuplicateNameModule.class, TestVertxModule.class})
    interface DuplicateNameComponent {
        /** Forces the {@link Authorizer} (and therefore the merge) to be built. */
        Authorizer authorizer();
    }

    /** Component with two distinct policy names that should create successfully. */
    @Singleton
    @Component(modules = {SecurityAuthzModule.class, DistinctNameModule.class, TestVertxModule.class})
    interface DistinctNameComponent {
        /** Exposes the {@link Authorizer} so its construction (the merge) is exercised. */
        Authorizer authorizer();
    }

    // --- opt-in DelegationEnforcementModule wiring ---

    private static final ActionRef DELEGATION_ACTION = ActionRef.of("authz", "action", "list");
    private static final ResourceRef DELEGATION_RESOURCE = new ResourceRef("resource", "res-1", Map.of());
    private static final PrincipalRef DELEGATION_ACTOR = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
    private static final PrincipalRef DELEGATION_SUBJECT = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
    private static final String DELEGATION_GRANT_ID = "grant-missing";
    private static final String DELEGATION_ROLE = "delegation-test-role";
    private static final String DELEGATION_POLICY = "delegation-test-policy";

    @Test
    @DisplayName("installing DelegationEnforcementModule contributes DelegationEnforcementNarrower into the "
            + "Set<AuthorizationNarrower> multibinding SecurityAuthzModule declares")
    void delegationEnforcementModule_contributesNarrowerIntoSet() {
        DelegationEnforcementComponent component =
                DaggerSecurityAuthzModuleTest_DelegationEnforcementComponent.create();

        Set<AuthorizationNarrower> narrowers = component.authorizationNarrowers();

        assertTrue(
                narrowers.stream().anyMatch(n -> n instanceof DelegationEnforcementNarrower),
                "Set<AuthorizationNarrower> must contain DelegationEnforcementNarrower once "
                        + "DelegationEnforcementModule is installed");
    }

    @Test
    @DisplayName("a delegated context with base PERMIT and no matching grant is denied through the wired "
            + "Authorizer, proving the narrower actually folds into the ordered chain (not merely present "
            + "in the set)")
    void delegationEnforcementModule_narrowerIsLiveInWiredAuthorizer() {
        DelegationEnforcementComponent component =
                DaggerSecurityAuthzModuleTest_DelegationEnforcementComponent.create();
        Authorizer authorizer = component.authorizer();

        SecurityContext ctx = delegatedContextGrantingBaseAccess();
        AuthorizationRequest request =
                new AuthorizationRequest(ctx, DELEGATION_ACTION.value(), DELEGATION_RESOURCE, Map.of());

        AuthorizationDecision decision = await(authorizer.authorize(request));

        // GRANT_NOT_FOUND is a reason code the narrower's DelegationGrantValidator lookup produces —
        // DefaultAuthorizer/AuthzResolution never emit it. Seeing it here proves the base engine
        // permitted (via the role->policy wiring below) AND the narrower's grant lookup actually ran
        // and denied — i.e. the narrower is live in the ordered fold, not just present in the set.
        assertFalse(decision.permitted(), "the absent delegation grant must narrow the base permit to DENY");
        assertEquals(DelegationReasonCodes.GRANT_NOT_FOUND, decision.reasonCode());
    }

    /**
     * Builds a delegated {@link SecurityContext} whose actor's role grants base access to
     * {@link #DELEGATION_ACTION} via the role/policy wiring in {@link DelegationWiringSupportModule}.
     *
     * @return a delegated security context carrying a role the wired policy permits
     */
    private static SecurityContext delegatedContextGrantingBaseAccess() {
        SecurityIdentity identity = new SecurityIdentity(
                DELEGATION_ACTOR,
                Optional.of(DELEGATION_SUBJECT),
                Optional.of(new DelegationContext("on-behalf-of", DELEGATION_GRANT_ID, Optional.empty(), Map.of())),
                Optional.empty());
        AuthorizationClaims claims = new AuthorizationClaims(
                Set.of(new AuthorityClaim(AuthorityKind.ROLE, DELEGATION_ROLE, "", "", "", Map.of())), Map.of());
        return new DelegationWiringSecurityContext(identity, claims);
    }

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    /**
     * Module contributing the role/policy wiring that grants {@link #DELEGATION_ROLE} base access to
     * {@link #DELEGATION_ACTION}, plus the application-supplied {@link DelegationGrantValidator}
     * binding {@link DelegationEnforcementModule} requires but deliberately does not default.
     *
     * <p>Seeds {@link InMemoryDelegationGrantValidator} with no grants (the S1 fixture) — a lookup for
     * any grant id therefore denies with {@link DelegationReasonCodes#GRANT_NOT_FOUND}. This reuses the
     * existing S1/S2b fixtures rather than re-deriving the four-quadrant intersection matrix, which is
     * already pinned by {@link DelegationEnforcementNarrowerTest}.
     */
    @Module
    interface DelegationWiringSupportModule {

        /** Grants {@link #DELEGATION_POLICY} the {@link #DELEGATION_ACTION} tested above. */
        @Provides
        @IntoSet
        static PolicyDefinitionSource delegationTestPolicySource() {
            return new InMemoryPolicyDefinitionSource(List.of(new PolicyDefinition(
                    DELEGATION_POLICY,
                    List.of(new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern(DELEGATION_ACTION.value())))))));
        }

        /** Maps {@link #DELEGATION_ROLE} to {@link #DELEGATION_POLICY}. */
        @Provides
        @IntoSet
        static RolePolicyResolver delegationTestRoleResolver() {
            return new InMemoryRolePolicyResolver(Map.of(DELEGATION_ROLE, List.of(DELEGATION_POLICY)));
        }

        /**
         * Provides the application-required {@link DelegationGrantValidator} binding —
         * {@link DelegationEnforcementModule} deliberately supplies no default.
         *
         * @return an {@link InMemoryDelegationGrantValidator} seeded with no grants
         */
        @Provides
        @Singleton
        static DelegationGrantValidator delegationGrantValidator() {
            return new InMemoryDelegationGrantValidator(List.of());
        }
    }

    /** Component proving the opt-in {@link DelegationEnforcementModule} wires into this module's ordered fold. */
    @Singleton
    @Component(
            modules = {
                SecurityAuthzModule.class,
                DelegationEnforcementModule.class,
                DelegationWiringSupportModule.class,
                TestVertxModule.class
            })
    interface DelegationEnforcementComponent {

        /** Exposes the {@code Set<AuthorizationNarrower>} multibinding. */
        Set<AuthorizationNarrower> authorizationNarrowers();

        /** Exposes the narrowing-wrapped {@link Authorizer}. */
        Authorizer authorizer();
    }

    /** Minimal {@link SecurityContext} stub carrying a controllable identity and authorization claims. */
    private record DelegationWiringSecurityContext(SecurityIdentity identity, AuthorizationClaims authorization)
            implements SecurityContext {
        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }
}
