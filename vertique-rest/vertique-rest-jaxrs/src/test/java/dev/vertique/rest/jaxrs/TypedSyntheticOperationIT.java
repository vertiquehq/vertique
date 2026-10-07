// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedPolicyComponents_Policies;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_Legacy;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithTwoRouteAuthHandlers;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithUnregisteredAction;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutAuthorizationEngine;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutAuthorizer;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutRouteAuthHandler;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutSchemeHandler;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutSecurityModules;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import dev.vertique.rest.jaxrs.synthetic.TypedPolicies;
import dev.vertique.rest.jaxrs.synthetic.TypedPolicyComponents;
import dev.vertique.rest.jaxrs.synthetic.TypedSyntheticObservations;
import dev.vertique.security.authz.AccessPolicy;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proofs that a synthetic operation created from a typed access policy is enforced by
 * the real contributor chain of the supported security composition, and that an operation the
 * composition cannot enforce is refused at install.
 *
 * <p>Every composition is built from the framework's own {@code RestModule}, {@code AuthModule} and
 * {@code SecurityModule}; only the bearer scheme handler (a test stand-in for a verified bearer
 * scheme that feeds the real identity middleware), the route authentication handler, the action
 * registry and a counting authorizer are fixtures. The authorizer is bound directly rather than
 * through {@code SecurityAuthzModule} so a composition can omit it while keeping an action registry.
 *
 * <p>The served matrix deploys the policy operations and requests each with the credentials that
 * allow and deny it, reading three observations per request: the status, how often the operation's
 * terminal handler ran, and how often the authorizer evaluated an action. An action-only policy
 * resolves to the same shape as on a manual route, so the real action-gate authentication contributor
 * requires exactly one route authentication handler; the refusal cases omit and duplicate it. Each
 * test builds a fresh deployment or composition, so one failure cannot mask another, and every
 * deployment closes its client, then its server, then the {@code Vertx} it owns.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class TypedSyntheticOperationIT {

    private static final String ORIGIN = "typed synthetic operation";
    private static final String SCHEME = "bearerAuth";
    private static final String APPLICATION = "typed";
    private static final String ROLE = TypedPolicies.ROLE;
    private static final String SCOPE = TypedPolicies.SCOPE;
    private static final String BLOCKED = "blocked";

    // --- Denied and unenforced operations ---

    @Test
    @DisplayName("A deny operation authenticates then denies without serving, and a restrictive operation "
            + "is refused where the security modules are missing")
    void shouldRefuseDeniedAndUnenforcedOperations() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving a deny operation behind the bearer scheme
            // When a caller without credentials and an authenticated caller request it
            Outcome anonymous = request(deployment, observed, "deny", null, null, null);
            Outcome authenticated = request(deployment, observed, "deny", "alice", ROLE, SCOPE);

            // Then authentication answers the first with 401, the deny answers the second with 403,
            // and neither is served a byte of the operation or reaches its terminal handler
            assertAll(
                    "deny",
                    () -> assertEquals(401, anonymous.status(), "anonymous status"),
                    () -> assertEquals(403, authenticated.status(), "authenticated status"),
                    () -> assertNeverServed(anonymous, "anonymous"),
                    () -> assertNeverServed(authenticated, "authenticated"));
        }

        // Given a composition with the bearer scheme handler but neither AuthModule nor SecurityModule
        SyntheticOperationInstaller unenforced =
                DaggerTypedSyntheticComponents_WithoutSecurityModules.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            // When restrictive operations are installed
            RestConfigurationException deny =
                    probe.refusal(unenforced, "/typed/deny", operation(TypedPolicies.Deny.class, SCHEME));
            RestConfigurationException roles =
                    probe.refusal(unenforced, "/typed/roles", operation(TypedPolicies.Roles.class, SCHEME));
            RestConfigurationException action =
                    probe.refusal(unenforced, "/typed/action", operation(TypedPolicies.Action.class, SCHEME));

            // Then each is refused before it is usable, for the missing enforcement, and leaves no route
            assertEquals(
                    List.of(RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE),
                    TypedSyntheticInstallProbe.routeViolationTypes(deny));
            assertEquals(
                    List.of(RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE),
                    TypedSyntheticInstallProbe.routeViolationTypes(roles));
            assertEquals(
                    List.of(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                    TypedSyntheticInstallProbe.routeViolationTypes(action));

            // And a public operation needing no enforcement installs there, so the refusals above come
            // from the missing enforcement and not from the composition
            assertEquals(1, probe.install(unenforced, "/typed/public", operation(TypedPolicies.Public.class, "")));
        }
    }

    // --- Allowed and denied outcomes over HTTP ---

    @Test
    @DisplayName("A public operation is served without authentication, ignoring any credential")
    void shouldServeAPublicOperationWithoutAuthentication() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving a public operation that names no scheme
            // When a caller without credentials and a caller with a credential no scheme would accept request it
            Outcome anonymous = request(deployment, observed, "public", null, null, null);
            Outcome unverifiable = request(deployment, observed, "public", " ", null, null);

            // Then both are served, because no authentication handler guards the operation, and no
            // action is evaluated
            assertAll(
                    "public",
                    () -> assertServed(anonymous, "public"),
                    () -> assertServed(unverifiable, "public"),
                    () -> assertEquals(0, anonymous.authorizations() + unverifiable.authorizations()));
            assertEquals(2, observed.terminalRuns("typed:public"));
        }
    }

    @Test
    @DisplayName("An authenticated policy operation serves only authenticated callers")
    void shouldServeAnAuthenticatedOperationOnlyToAuthenticatedCallers() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving an authenticated-only policy operation
            // When an anonymous and an authenticated caller request it
            Outcome anonymous = request(deployment, observed, "authenticated", null, null, null);
            Outcome authenticated = request(deployment, observed, "authenticated", "alice", null, null);

            // Then the anonymous caller is denied with 401 and never served, the authenticated caller is served once
            assertEquals(401, anonymous.status());
            assertNeverServed(anonymous, "anonymous");
            assertServed(authenticated, "authenticated");
        }
    }

    @Test
    @DisplayName("A roles policy operation denies callers without the role and serves callers with it")
    void shouldEnforceTheRolesOfAPolicy() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving a roles policy operation
            // When callers with no role, another role, and the required role request it
            Outcome anonymous = request(deployment, observed, "roles", null, null, null);
            Outcome noRole = request(deployment, observed, "roles", "alice", null, null);
            Outcome otherRole = request(deployment, observed, "roles", "alice", "reader", null);
            Outcome withRole = request(deployment, observed, "roles", "alice", "reader," + ROLE, null);

            // Then the first is denied with 401, the next two with 403, and only the last is served
            assertEquals(401, anonymous.status(), "anonymous");
            assertEquals(403, noRole.status(), "no role");
            assertEquals(403, otherRole.status(), "other role");
            assertAll(
                    "denied callers never reach the terminal handler",
                    () -> assertNeverServed(anonymous, "anonymous"),
                    () -> assertNeverServed(noRole, "no role"),
                    () -> assertNeverServed(otherRole, "other role"));
            assertServed(withRole, "roles");
        }
    }

    @Test
    @DisplayName("A scopes policy operation denies callers without the scope and serves callers with it")
    void shouldEnforceTheScopesOfAPolicy() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving a scopes policy operation
            // When callers with no scope, another scope, and the required scope request it
            Outcome anonymous = request(deployment, observed, "scopes", null, null, null);
            Outcome noScope = request(deployment, observed, "scopes", "alice", null, null);
            Outcome otherScope = request(deployment, observed, "scopes", "alice", null, "other.scope");
            Outcome withScope = request(deployment, observed, "scopes", "alice", null, "other.scope " + SCOPE);

            // Then the first is denied with 401, the next two with 403, and only the last is served
            assertEquals(401, anonymous.status(), "anonymous");
            assertEquals(403, noScope.status(), "no scope");
            assertEquals(403, otherScope.status(), "other scope");
            assertAll(
                    "denied callers never reach the terminal handler",
                    () -> assertNeverServed(anonymous, "anonymous"),
                    () -> assertNeverServed(noScope, "no scope"),
                    () -> assertNeverServed(otherScope, "other scope"));
            assertServed(withScope, "scopes");
        }
    }

    @Test
    @DisplayName("An action policy operation authenticates the caller and evaluates the action exactly once")
    void shouldEvaluateTheActionOfAnActionOnlyPolicyOnce() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving an action-only policy operation and an authorizer blocking one subject
            // When an anonymous caller, the blocked subject, and a permitted subject request it
            Outcome anonymous = request(deployment, observed, "action", null, null, null);
            Outcome blocked = request(deployment, observed, "action", BLOCKED, null, null);
            Outcome permitted = request(deployment, observed, "action", "alice", null, null);

            // Then the anonymous caller is denied with 401 before the action is evaluated, the blocked
            // subject is denied with 403 after one evaluation, and the permitted subject is served
            // after exactly one evaluation of the policy's action
            assertEquals(401, anonymous.status(), "anonymous");
            assertEquals(0, anonymous.authorizations(), "an unauthenticated caller never reaches the action gate");
            assertNeverServed(anonymous, "anonymous");
            assertEquals(403, blocked.status(), "blocked subject");
            assertEquals(1, blocked.authorizations(), "blocked subject evaluations");
            assertNeverServed(blocked, "blocked");
            assertServed(permitted, "action");
            assertEquals(1, permitted.authorizations(), "permitted subject evaluations");
            assertEquals(
                    List.of(TypedPolicies.ACTION, TypedPolicies.ACTION),
                    observed.authorizedActions(),
                    "the authorizer saw exactly the policy's action, once per reached request");
        }
    }

    @Test
    @DisplayName("A roles and action policy checks the role first and evaluates the action only when reached")
    void shouldEvaluateTheRoleBeforeTheActionOfARolesAndActionPolicy() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving a roles and action policy operation
            // When callers without credentials, without the role, with the role but blocked, and with
            // the role and permitted request it
            Outcome anonymous = request(deployment, observed, "roles-action", null, null, null);
            Outcome noRole = request(deployment, observed, "roles-action", "alice", "reader", null);
            Outcome blocked = request(deployment, observed, "roles-action", BLOCKED, ROLE, null);
            Outcome permitted = request(deployment, observed, "roles-action", "alice", ROLE, null);

            // Then the role gate denies before the action is evaluated, the action gate denies the
            // blocked subject after one evaluation, and only the permitted caller is served
            assertEquals(401, anonymous.status(), "anonymous");
            assertEquals(403, noRole.status(), "no role");
            assertEquals(403, blocked.status(), "blocked");
            assertAll(
                    "evaluations",
                    () -> assertEquals(0, anonymous.authorizations(), "anonymous"),
                    () -> assertEquals(0, noRole.authorizations(), "the action is not reached without the role"),
                    () -> assertEquals(1, blocked.authorizations(), "blocked"),
                    () -> assertEquals(1, permitted.authorizations(), "permitted"));
            assertAll(
                    "denied callers never reach the terminal handler",
                    () -> assertNeverServed(anonymous, "anonymous"),
                    () -> assertNeverServed(noRole, "no role"),
                    () -> assertNeverServed(blocked, "blocked"));
            assertServed(permitted, "roles-action");
        }
    }

    @Test
    @DisplayName("A combined policy checks role and scope first and evaluates the action only when reached")
    void shouldEvaluateRoleAndScopeBeforeTheActionOfACombinedPolicy() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving a policy requiring a role, a scope and an action
            // When callers lacking the scope, lacking the role, blocked on the action, and fully
            // entitled request it
            Outcome noScope = request(deployment, observed, "combined", "alice", ROLE, null);
            Outcome noRole = request(deployment, observed, "combined", "alice", "reader", SCOPE);
            Outcome blocked = request(deployment, observed, "combined", BLOCKED, ROLE, SCOPE);
            Outcome permitted = request(deployment, observed, "combined", "alice", ROLE, SCOPE);

            // Then the local predicates deny first with 403 and no action evaluation, the action gate
            // denies the blocked subject after one evaluation, and only the entitled caller is served
            assertEquals(403, noScope.status(), "no scope");
            assertEquals(403, noRole.status(), "no role");
            assertEquals(403, blocked.status(), "blocked");
            assertAll(
                    "evaluations",
                    () -> assertEquals(0, noScope.authorizations(), "no scope"),
                    () -> assertEquals(0, noRole.authorizations(), "no role"),
                    () -> assertEquals(1, blocked.authorizations(), "blocked"),
                    () -> assertEquals(1, permitted.authorizations(), "permitted"));
            assertAll(
                    "denied callers never reach the terminal handler",
                    () -> assertNeverServed(noScope, "no scope"),
                    () -> assertNeverServed(noRole, "no role"),
                    () -> assertNeverServed(blocked, "blocked"));
            assertServed(permitted, "combined");
        }
    }

    @Test
    @DisplayName("Every restrictive policy operation answers 401 before authorization when no credential is sent")
    void shouldAuthenticateBeforeAuthorizingEveryRestrictiveOperation() throws Exception {
        TypedPolicyComponents.Policies served = DaggerTypedPolicyComponents_Policies.create();
        TypedSyntheticObservations observed = served.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(served::httpVerticle)) {
            // Given a deployment serving every restrictive policy operation
            // When each is requested without credentials
            // Then each answers 401, evaluates no action, and never reaches its terminal handler
            for (String name :
                    List.of("deny", "authenticated", "roles", "scopes", "action", "roles-action", "combined")) {
                Outcome anonymous = request(deployment, observed, name, null, null, null);
                assertEquals(401, anonymous.status(), name + " status");
                assertEquals(0, anonymous.authorizations(), name + " must not reach authorization");
                assertNeverServed(anonymous, name);
            }
        }
    }

    // --- Refusal where enforcement cannot be provided ---

    @Test
    @DisplayName("An action the registry does not register is refused at install")
    void shouldRefuseAnActionTheRegistryDoesNotRegister() throws Exception {
        // Given the supported composition whose action registry lacks the policies' action
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_WithUnregisteredAction.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            for (Class<? extends AccessPolicy> policy : List.of(
                    TypedPolicies.Action.class, TypedPolicies.RolesAndAction.class, TypedPolicies.Combined.class)) {
                // When an operation requiring that action is installed
                RestConfigurationException refusal = probe.refusal(installer, "/typed/x", operation(policy, SCHEME));

                // Then it is refused for the unregistered action and leaves no route
                assertEquals(
                        List.of(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                        TypedSyntheticInstallProbe.routeViolationTypes(refusal),
                        policy.getSimpleName());
            }

            // And an operation needing no action installs, so the refusal is about the action
            assertEquals(1, probe.install(installer, "/typed/roles", operation(TypedPolicies.Roles.class, SCHEME)));
        }
    }

    @Test
    @DisplayName("Restrictive operations are refused at install when they name no scheme")
    void shouldRefuseRestrictiveOperationsWithTheEmptyScheme() throws Exception {
        // Given the supported composition
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_Legacy.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            for (Class<? extends AccessPolicy> policy : List.of(
                    TypedPolicies.Deny.class,
                    TypedPolicies.Authenticated.class,
                    TypedPolicies.Roles.class,
                    TypedPolicies.Scopes.class,
                    TypedPolicies.Action.class,
                    TypedPolicies.RolesAndAction.class,
                    TypedPolicies.Combined.class)) {
                // When an operation requiring authentication or authorization names no scheme
                // Then it is refused and leaves no route, since only a public operation may omit the scheme
                RestConfigurationException refusal = probe.refusal(installer, "/typed/x", operation(policy, ""));
                if (policy == TypedPolicies.Action.class) {
                    // An action-only policy resolves to no restriction, so the policy validator has
                    // nothing to reject; the missing scheme is caught as a missing authentication handler
                    assertNull(refusal.getCause(), policy.getSimpleName());
                    assertTrue(refusal.getMessage().contains("security scheme ''"), refusal.getMessage());
                } else {
                    assertEquals(
                            List.of(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY),
                            TypedSyntheticInstallProbe.policyViolationTypes(refusal),
                            policy.getSimpleName());
                }
            }

            // And a public operation naming no scheme installs on the same composition
            assertEquals(1, probe.install(installer, "/typed/public", operation(TypedPolicies.Public.class, "")));
        }
    }

    @Test
    @DisplayName("A public operation naming a scheme is refused at install as conflicting")
    void shouldRefuseAPublicOperationNamingAScheme() throws Exception {
        // Given the supported composition, whose policy validator comes from the security module
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_Legacy.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            // When a public operation naming the bearer scheme is installed
            RestConfigurationException refusal =
                    probe.refusal(installer, "/typed/public", operation(TypedPolicies.Public.class, SCHEME));

            // Then it is refused as conflicting semantics and leaves no route
            assertEquals(
                    List.of(SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS),
                    TypedSyntheticInstallProbe.policyViolationTypes(refusal));
        }
    }

    @Test
    @DisplayName("An action is refused at install when no authorizer is installed")
    void shouldRefuseAnActionWithoutAnAuthorizer() throws Exception {
        // Given the supported composition with an action registry but no authorizer
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_WithoutAuthorizer.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            // When operations requiring a registered action are installed
            RestConfigurationException actionOnly =
                    probe.refusal(installer, "/typed/action", operation(TypedPolicies.Action.class, SCHEME));
            RestConfigurationException rolesAndAction = probe.refusal(
                    installer, "/typed/roles-action", operation(TypedPolicies.RolesAndAction.class, SCHEME));

            // Then both are refused, for lacking an authorizer to enforce the action, and leave no route
            assertEquals(
                    List.of(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                    TypedSyntheticInstallProbe.routeViolationTypes(actionOnly));
            assertEquals(
                    List.of(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                    TypedSyntheticInstallProbe.routeViolationTypes(rolesAndAction));

            // And an operation needing no action installs, so the refusal is about the authorizer
            assertEquals(1, probe.install(installer, "/typed/roles", operation(TypedPolicies.Roles.class, SCHEME)));
        }
    }

    @Test
    @DisplayName("An action is refused at install when no authorization engine is installed")
    void shouldRefuseAnActionWithoutTheAuthorizationEngine() throws Exception {
        // Given the supported composition with neither an action registry nor an authorizer
        SyntheticOperationInstaller installer = DaggerTypedSyntheticComponents_WithoutAuthorizationEngine.create()
                .syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            // When an operation requiring an action is installed
            RestConfigurationException refusal =
                    probe.refusal(installer, "/typed/action", operation(TypedPolicies.Action.class, SCHEME));

            // Then it is refused for the missing engine and leaves no route
            assertEquals(
                    List.of(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                    TypedSyntheticInstallProbe.routeViolationTypes(refusal));
        }
    }

    @Test
    @DisplayName("Restrictive operations are refused at install when the scheme has no handler")
    void shouldRefuseRestrictiveOperationsWithoutASchemeHandler() throws Exception {
        // Given the supported security modules but no handler for the bearer scheme
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_WithoutSchemeHandler.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            for (Class<? extends AccessPolicy> policy : List.of(
                    TypedPolicies.Deny.class,
                    TypedPolicies.Authenticated.class,
                    TypedPolicies.Roles.class,
                    TypedPolicies.Scopes.class,
                    TypedPolicies.Action.class,
                    TypedPolicies.RolesAndAction.class)) {
                // When an operation naming that scheme is installed
                RestConfigurationException refusal = probe.refusal(installer, "/typed/x", operation(policy, SCHEME));

                // Then it is refused for the scheme without a handler and leaves no route
                assertEquals(
                        List.of(SecurityPolicyViolation.ViolationType.OPENAPI_SECURITY_WITHOUT_HANDLER),
                        TypedSyntheticInstallProbe.policyViolationTypes(refusal),
                        policy.getSimpleName());
            }

            // And a public operation naming no scheme installs, so the refusals are about the scheme
            assertEquals(1, probe.install(installer, "/typed/public", operation(TypedPolicies.Public.class, "")));
        }
    }

    @Test
    @DisplayName("An action-only operation is refused at install when no route authentication handler exists")
    void shouldRefuseAnActionOnlyOperationWithoutARouteAuthenticationHandler() throws Exception {
        // Given the supported composition with a scheme handler but no route authentication handler
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_WithoutRouteAuthHandler.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            // When an action-only operation is installed
            RestConfigurationException refusal =
                    probe.refusal(installer, "/typed/action", operation(TypedPolicies.Action.class, SCHEME));

            // Then the action-gate authentication contributor refuses it, naming the missing handler,
            // and the router keeps no route
            IllegalStateException cause = assertInstanceOf(IllegalStateException.class, refusal.getCause());
            assertTrue(cause.getMessage().contains("RouteAuthHandler"), cause.getMessage());

            // And a roles-and-action operation, which the scheme handler already authenticates, installs
            assertEquals(
                    1,
                    probe.install(
                            installer, "/typed/roles-action", operation(TypedPolicies.RolesAndAction.class, SCHEME)));

            // And the refused operation id was released: the same id installs on the same router once
            // the operation no longer needs the missing handler
            SyntheticOperation sameId = SyntheticOperation.withPolicy(
                    ORIGIN,
                    "typed:" + TypedPolicies.Action.class.getSimpleName(),
                    SCHEME,
                    APPLICATION,
                    TypedPolicies.RolesAndAction.class);
            assertEquals(2, probe.install(installer, "/typed/action", sameId));
        }
    }

    @Test
    @DisplayName("An action-only operation is refused at install when route authentication handlers are ambiguous")
    void shouldRefuseAnActionOnlyOperationWithTwoRouteAuthenticationHandlers() throws Exception {
        // Given the supported composition with two route authentication handlers
        SyntheticOperationInstaller installer =
                DaggerTypedSyntheticComponents_WithTwoRouteAuthHandlers.create().syntheticOperationInstaller();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            // When an action-only operation is installed
            RestConfigurationException refusal =
                    probe.refusal(installer, "/typed/action", operation(TypedPolicies.Action.class, SCHEME));

            // Then the action-gate authentication contributor refuses it as ambiguous and the router keeps no route
            IllegalStateException cause = assertInstanceOf(IllegalStateException.class, refusal.getCause());
            assertTrue(cause.getMessage().contains("multiple RouteAuthHandler"), cause.getMessage());
            assertFalse(cause.getMessage().isBlank());
        }
    }

    // --- Helpers ---

    private static SyntheticOperation operation(Class<? extends AccessPolicy> policy, String scheme) {
        return SyntheticOperation.withPolicy(ORIGIN, "typed:" + policy.getSimpleName(), scheme, APPLICATION, policy);
    }

    /** What one request showed: its status and what it did to the terminal handler and the authorizer. */
    private record Outcome(String name, int status, int terminalRuns, int authorizations, String body) {}

    private static Outcome request(
            TypedSyntheticDeployment deployment,
            TypedSyntheticObservations observed,
            String name,
            String subject,
            String roles,
            String scopes) {
        String operationId = "typed:" + name;
        int terminalBefore = observed.terminalRuns(operationId);
        int authorizationsBefore = observed.authorizations();
        HttpResponse<Buffer> response = deployment.get("/typed/" + name, subject, roles, scopes);
        return new Outcome(
                name,
                response.statusCode(),
                observed.terminalRuns(operationId) - terminalBefore,
                observed.authorizations() - authorizationsBefore,
                response.bodyAsString());
    }

    private static void assertServed(Outcome outcome, String name) {
        assertEquals(200, outcome.status(), name + " status");
        assertEquals("ok:typed:" + name, outcome.body(), name + " body");
        assertEquals(1, outcome.terminalRuns(), name + " terminal runs");
    }

    private static void assertNeverServed(Outcome outcome, String label) {
        assertEquals(0, outcome.terminalRuns(), label + ": a denied request must not reach the terminal handler");
        assertFalse(
                outcome.body() != null && outcome.body().contains("ok:typed"),
                label + ": a denied request must not carry successful bytes");
    }
}
