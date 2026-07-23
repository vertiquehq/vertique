// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that route registration fails when an operation declares a restrictive security policy
 * but the auth enforcement runtime is not installed, and that the install signal is carried by the
 * typed {@link AuthEnforcementCapability} marker rather than the mere presence of a
 * {@link SecurityPolicyValidator}.
 *
 * <p>The {@code authEnabled} boolean fed to {@link JaxRsRouteRegistrar#registerAll} is computed by
 * {@link JaxRsRouterMount.Factory} as {@code authEnforcementCapability.isPresent()}; these tests
 * exercise the same mapping by deriving the boolean from an {@code Optional<AuthEnforcementCapability>}.
 */
class RouteValidatorSecurityTest {

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

    /** Resource whose single operation carries a restrictive {@code @RolesAllowed} policy. */
    @Path("/secure")
    static class RestrictiveResource {
        @GET
        @Operation(operationId = "secureOp")
        @RolesAllowed("admin")
        public Future<String> secure() {
            return Future.succeededFuture("ok");
        }
    }

    @Test
    @DisplayName("Restrictive operation without auth capability fails route registration")
    void restrictiveWithoutAuth_fails() {
        Optional<AuthEnforcementCapability> capability = Optional.empty();

        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new RestrictiveResource()),
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
                        // no @RequiresAction here; Authorizer flag is immaterial.
                        false));

        assertEquals(1, ex.violations().size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,
                ex.violations().get(0).type());
        assertEquals("secureOp", ex.violations().get(0).operationId());
    }

    @Test
    @DisplayName("Restrictive operation with auth capability registers without violation")
    void withAuthCapability_ok() {
        Optional<AuthEnforcementCapability> capability = Optional.of(AuthEnforcementCapability.INSTANCE);

        assertDoesNotThrow(() -> RegistrarTestSupport.registerAll(
                registrar,
                Set.of(new RestrictiveResource()),
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
                // no @RequiresAction here; Authorizer flag is immaterial.
                false));
    }

    @Test
    @DisplayName("A lone SecurityPolicyValidator without the auth capability is NOT auth-installed")
    void validatorAloneWithoutAuthModule_NOTauthInstalled() {
        // A validator is bound (non-null), but the AuthModule capability is absent.
        SecurityPolicyValidator validator = mock(SecurityPolicyValidator.class);
        when(validator.validate(any(), any())).thenReturn(List.of());

        Optional<AuthEnforcementCapability> capability = Optional.empty();

        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new RestrictiveResource()),
                        router,
                        List.of(),
                        List.of(),
                        validator,
                        capability.isPresent(),
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        // no @RequiresAction here; Authorizer flag is immaterial.
                        false));

        assertEquals(1, ex.violations().size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,
                ex.violations().get(0).type());
        assertEquals("secureOp", ex.violations().get(0).operationId());
    }
}
