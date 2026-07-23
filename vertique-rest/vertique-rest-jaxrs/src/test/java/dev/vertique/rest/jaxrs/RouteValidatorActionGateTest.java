// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.RequiresAction;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed startup validation for the {@code @RequiresAction} action gate on JAX-RS routes
 * (Phase-3 review finding C1).
 *
 * <p>An action-only route ({@link dev.vertique.rest.core.security.SecurityPolicy.None} carrying
 * {@code @RequiresAction}) is enforced at runtime by the REST authorization pipeline
 * ({@code AuthorizationContributor} + {@code IdentityResolutionMiddleware}), which is installed only
 * when {@code AuthModule} is present. The authz engine (the {@link ActionRegistry}) and the REST
 * enforcement pipeline are separately bound, so the engine can be present while the enforcement
 * pipeline is absent — and {@code SecurityPolicy.None.isRestrictive()} is {@code false}, so the
 * generic {@code SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE} check does not catch an action-only
 * route. Startup must therefore fail-closed when a {@code @RequiresAction} route is declared but the
 * REST auth-enforcement capability ({@code authEnabled}) is absent: accepting the annotation while
 * the gate that enforces it is never installed would be a silent authorization bypass.
 */
class RouteValidatorActionGateTest {

    private JaxRsRouteRegistrar registrar;
    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        registrar = new JaxRsRouteRegistrar();
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    /** Resource whose single operation carries an action-only {@code @RequiresAction} policy. */
    @Path("/action")
    static class ActionOnlyResource {
        @GET
        @Operation(operationId = "actionGet")
        @RequiresAction("cms.content.read")
        public Future<String> read() {
            return Future.succeededFuture("ok");
        }
    }

    @Test
    @DisplayName("@RequiresAction route fails startup when auth enforcement is absent (engine present)")
    void requiresAction_authEnforcementAbsent_failsStartup() {
        // Engine present (non-null registry that contains the action), but auth enforcement absent.
        Optional<AuthEnforcementCapability> capability = Optional.empty();

        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new ActionOnlyResource()),
                        router,
                        List.of(),
                        List.of(),
                        null,
                        capability.isPresent(),
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        new StubActionRegistry(ActionRef.of("cms", "content", "read")),
                        // authEnabled=false fails first; the Authorizer flag is immaterial here.
                        false));

        assertTrue(
                ex.violations().stream()
                        .anyMatch(v -> "actionGet".equals(v.operationId())
                                && v.type() == RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                "an action-only route without auth enforcement must fail startup with REQUIRES_ACTION_INVALID");
    }

    @Test
    @DisplayName("@RequiresAction route fails startup when the authz engine (ActionRegistry) is absent")
    void requiresAction_actionRegistryAbsent_failsStartup() {
        // Auth enforcement present, but the authz engine (registry) is absent (null).
        Optional<AuthEnforcementCapability> capability = Optional.of(AuthEnforcementCapability.INSTANCE);

        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new ActionOnlyResource()),
                        router,
                        List.of(),
                        List.of(),
                        null,
                        capability.isPresent(),
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        // registry==null fails first; the Authorizer flag is immaterial here.
                        false));

        assertTrue(
                ex.violations().stream()
                        .anyMatch(v -> "actionGet".equals(v.operationId())
                                && v.type() == RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                "an action-only route without the authz engine must fail startup with REQUIRES_ACTION_INVALID");
    }

    @Test
    @DisplayName("@RequiresAction route fails startup when the Authorizer is absent (registry + enforcement present)")
    void requiresAction_authorizerAbsent_failsStartup() {
        // Engine present (registry contains the action) AND auth enforcement present (authEnabled),
        // but the core Authorizer that evaluates the action gate is absent — a non-default graph where
        // ActionRegistry and Authorizer are bound through separate optional seams. The gate would NPE
        // and fail closed per request rather than being rejected at boot; startup must fail-closed (W2).
        Optional<AuthEnforcementCapability> capability = Optional.of(AuthEnforcementCapability.INSTANCE);

        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new ActionOnlyResource()),
                        router,
                        List.of(),
                        List.of(),
                        null,
                        capability.isPresent(),
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        new StubActionRegistry(ActionRef.of("cms", "content", "read")),
                        false));

        assertTrue(
                ex.violations().stream()
                        .anyMatch(v -> "actionGet".equals(v.operationId())
                                && v.type() == RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID),
                "an action-only route without an Authorizer must fail startup with REQUIRES_ACTION_INVALID");
    }

    @Test
    @DisplayName("@RequiresAction route registers when registry, enforcement, and Authorizer are all present")
    void requiresAction_allPresent_registersFine() {
        // Engine present, enforcement present, AND the Authorizer present — the complete graph. The
        // route must register without any REQUIRES_ACTION_INVALID violation.
        assertDoesNotThrow(
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new ActionOnlyResource()),
                        router,
                        List.of(),
                        List.of(),
                        null,
                        true,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        new StubActionRegistry(ActionRef.of("cms", "content", "read")),
                        true),
                "a @RequiresAction route with engine + enforcement + Authorizer all present must register");
    }

    /**
     * Minimal {@link ActionRegistry} stub that contains a fixed set of actions, keyed by their
     * canonical value.
     */
    static final class StubActionRegistry implements ActionRegistry {
        private final Set<String> known;

        StubActionRegistry(ActionRef... refs) {
            this.known = Arrays.stream(refs).map(ActionRef::value).collect(Collectors.toSet());
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return List.of();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return known.contains(action.value());
        }
    }
}
