// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end Dagger wiring test for the config-backed authorization path, exercising
 * {@link AuthzConfigModule} and {@link SecurityAuthzModule} <strong>together</strong> in one
 * component (the combination the green build never exercised).
 *
 * <p>Verifies the full path:
 * <ul>
 *   <li>config ({@code authorization.policies} + {@code authorization.rolePolicies}) flows through
 *       {@link AuthzConfigModule} into the multibinding sets, reaches the {@link SecurityAuthzModule}
 *       engine, and an actor with the mapped ROLE is <strong>PERMITTED</strong> the configured action
 *       through the real {@link Authorizer};</li>
 *   <li>registry validation runs — a config policy over an unregistered action fails component
 *       creation (fail-fast);</li>
 *   <li>merged-catalogue role validation runs — a role mapping referencing a policy that is
 *       contributed <em>programmatically</em> (not in config) is accepted (FR — W2), while a role
 *       mapping referencing an entirely unknown policy fails component creation.</li>
 * </ul>
 *
 * <p>The built-in {@code authz.action.list} action (reserved by the core module's
 * {@code BuiltinAuthzActionContributor}) is used as the registered action under test, so no extra
 * action contributor is needed.
 */
class AuthzCombinedModulesTest {

    private static final ActionRef AUTHZ_ACTION_LIST = ActionRef.of("authz", "action", "list");
    private static final ResourceRef RESOURCE = new ResourceRef("action", "", Map.of());

    // --- end-to-end permit through the real Authorizer ---

    @Test
    @DisplayName("config policy + role mapping permits the mapped actor through the real Authorizer")
    void configDrivenPermit_throughRealAuthorizer() {
        JsonObject config = adminPolicyOverActionListConfig();

        TestComponent component = DaggerAuthzCombinedModulesTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(config))
                .build();

        AuthorizationDecision decision =
                await(component.authorizer().authorize(request(ctxWithRoles("admin"), AUTHZ_ACTION_LIST)));

        assertTrue(decision.permitted(), "actor with mapped admin role must be permitted authz.action.list");
        assertEquals(AuthzReasonCodes.PERMITTED, decision.reasonCode());
    }

    @Test
    @DisplayName("an actor without the mapped role is denied by the same wired Authorizer")
    void configDrivenDeny_actorWithoutRole() {
        JsonObject config = adminPolicyOverActionListConfig();

        TestComponent component = DaggerAuthzCombinedModulesTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(config))
                .build();

        AuthorizationDecision decision =
                await(component.authorizer().authorize(request(ctxWithRoles("viewer"), AUTHZ_ACTION_LIST)));

        assertFalse(decision.permitted(), "actor lacking the mapped role must be denied");
    }

    // --- fail-fast: registry validation runs in the combined graph ---

    @Test
    @DisplayName("config policy over an unregistered action fails component creation (registry validation)")
    void registryValidation_unregisteredAction_failsComponentCreation() {
        JsonObject config = new JsonObject("""
                {
                  "authorization": {
                    "policies": [
                      { "name": "bad-policy",
                        "statements": [ { "effect": "ALLOW", "actions": [ "cms.unknown.read" ] } ] }
                    ]
                  }
                }
                """);

        assertThrows(
                RuntimeException.class,
                () -> DaggerAuthzCombinedModulesTest_TestComponent.builder()
                        .testConfigModule(new TestConfigModule(config))
                        .build()
                        .authorizer(),
                "a config policy referencing an unregistered action must fail fast at startup");
    }

    // --- W2: role mapping validated against the MERGED catalogue ---

    @Test
    @DisplayName(
            "role mapping referencing a programmatically-contributed policy is accepted (merged-catalogue validation)")
    void roleMapping_referencingProgrammaticPolicy_isAccepted() {
        // The role mapping references "prog-policy", which is NOT in config — it is contributed
        // programmatically by ProgrammaticPolicyModule. Validation against the merged catalogue must
        // accept it, and the actor must be permitted.
        JsonObject config = new JsonObject("""
                { "authorization": { "rolePolicies": { "admin": [ "prog-policy" ] } } }
                """);

        ProgrammaticComponent component = DaggerAuthzCombinedModulesTest_ProgrammaticComponent.builder()
                .testConfigModule(new TestConfigModule(config))
                .build();

        AuthorizationDecision decision =
                await(component.authorizer().authorize(request(ctxWithRoles("admin"), AUTHZ_ACTION_LIST)));

        assertTrue(
                decision.permitted(),
                "role mapping to a programmatically-contributed policy must be accepted and permit the action");
    }

    @Test
    @DisplayName("role mapping referencing an unknown policy fails component creation")
    void roleMapping_referencingUnknownPolicy_failsComponentCreation() {
        JsonObject config = new JsonObject("""
                { "authorization": { "rolePolicies": { "admin": [ "ghost-policy" ] } } }
                """);

        assertThrows(
                RuntimeException.class,
                () -> DaggerAuthzCombinedModulesTest_TestComponent.builder()
                        .testConfigModule(new TestConfigModule(config))
                        .build()
                        .authorizer(),
                "a role mapping referencing a policy absent from the merged catalogue must fail fast");
    }

    // --- helpers ---

    /**
     * Config with an {@code admin → admin-policy} role mapping and an inline {@code admin-policy} that
     * ALLOWs the built-in {@code authz.action.list} action.
     *
     * @return the parsed config object
     */
    private static JsonObject adminPolicyOverActionListConfig() {
        return new JsonObject("""
                {
                  "authorization": {
                    "rolePolicies": { "admin": [ "admin-policy" ] },
                    "policies": [
                      { "name": "admin-policy",
                        "statements": [ { "effect": "ALLOW", "actions": [ "authz.action.list" ] } ] }
                    ]
                  }
                }
                """);
    }

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    private static AuthorizationRequest request(SecurityContext ctx, ActionRef action) {
        return new AuthorizationRequest(ctx, action.value(), RESOURCE, Map.of());
    }

    /** Builds a {@link SecurityContext} whose authorization claims hold exactly the given ROLE values. */
    private static SecurityContext ctxWithRoles(String... roles) {
        Set<AuthorityClaim> claims = java.util.Arrays.stream(roles)
                .map(r -> new AuthorityClaim(AuthorityKind.ROLE, r, "", "", "test", Map.of()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new StubSecurityContext(new AuthorizationClaims(claims, Map.of()));
    }

    // --- Dagger test wiring ---

    /** Provides the application config as the {@code @VertxConfig} binding for the test graph. */
    @Module
    static final class TestConfigModule {
        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        /**
         * Provides the application configuration as the {@code @VertxConfig} binding.
         *
         * @return the application configuration; never {@code null}
         */
        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }

    /**
     * Provides a bare {@link Vertx} instance for the combined test components below —
     * {@code SecurityAuthzModule#authorizer} now needs {@link Vertx} to bound every wrapped
     * {@code PrincipalAuthorityResolver} with a timeout, even when no resolver is installed.
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

    /** A programmatic policy source contributing {@code prog-policy} allowing {@code authz.action.list}. */
    @Module
    interface ProgrammaticPolicyModule {
        /**
         * Contributes a programmatic policy named {@code prog-policy} (not present in config).
         *
         * @return the programmatic source, added to the multibinding set
         */
        @Provides
        @IntoSet
        static PolicyDefinitionSource progSource() {
            PolicyDefinition policy = new PolicyDefinition(
                    "prog-policy",
                    List.of(new PolicyStatement(
                            dev.vertique.security.authz.Effect.ALLOW,
                            Set.of(new dev.vertique.security.authz.ActionPattern("authz.action.list")))));
            List<PolicyDefinition> policies = List.of(policy);
            return () -> policies;
        }
    }

    /** Combined component: config-backed authz wiring plus the core engine. */
    @Singleton
    @Component(
            modules = {
                AuthzConfigModule.class,
                ConfigParsingModule.class,
                SecurityAuthzModule.class,
                TestConfigModule.class,
                TestVertxModule.class
            })
    interface TestComponent {
        /** Exposes the real {@link Authorizer} built by the combined graph. */
        Authorizer authorizer();
    }

    /** Combined component that also contributes a programmatic policy source (for the W2 merged-catalogue test). */
    @Singleton
    @Component(
            modules = {
                AuthzConfigModule.class,
                ConfigParsingModule.class,
                SecurityAuthzModule.class,
                TestConfigModule.class,
                ProgrammaticPolicyModule.class,
                TestVertxModule.class
            })
    interface ProgrammaticComponent {
        /** Exposes the real {@link Authorizer} built by the combined graph. */
        Authorizer authorizer();
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
