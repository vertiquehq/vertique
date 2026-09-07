// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that an operation whose <strong>effective</strong> security policy is restrictive but whose
 * <strong>raw</strong> security policy is not — the scoped {@code @SecurityRequirement} case — fails
 * startup when the auth-enforcement capability is absent.
 *
 * <p>A scoped {@code @SecurityRequirement(name = "jwt", scopes = {"write"})} with no {@code @Authorized}
 * / {@code @RolesAllowed} leaves the raw {@link dev.vertique.rest.core.security.SecurityPolicy} as
 * {@code None} (non-restrictive), while {@code descriptor.effectiveSecurityPolicy()} folds the scopes
 * into a restrictive {@code Constrained} policy that is enforced only by the authorization contributor
 * — which is wired solely when the auth-enforcement capability is present. With auth absent the folded
 * scopes are never enforced, a silent authorization fail-open. {@link RouteValidator#checkSecurityWithoutAuth}
 * must therefore evaluate the effective policy so this route is rejected at startup.
 *
 * <p>A {@link SimpleAuthenticationHandler} is registered for the scheme so the route would otherwise
 * mount (the fail-closed {@code applySecurity} gate requires a collected handler); the violation under
 * test is the missing <em>authorization</em> enforcement, not a missing authentication handler.
 */
class RouteValidatorScopedSecurityWithoutAuthTest {

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

    /** Operation with a scoped {@code @SecurityRequirement} and no {@code @Authorized}/{@code @RolesAllowed}. */
    @Path("/scoped")
    static class ScopedRequirementResource {
        @GET
        @Operation(operationId = "scopedOp")
        @SecurityRequirement(
                name = "jwt",
                scopes = {"write"})
        public Future<String> scoped() {
            return Future.succeededFuture("ok");
        }
    }

    /** Operation with a scopeless {@code @SecurityRequirement} (authentication only). */
    @Path("/scopeless")
    static class ScopelessRequirementResource {
        @GET
        @Operation(operationId = "scopelessOp")
        @SecurityRequirement(name = "jwt")
        public Future<String> scopeless() {
            return Future.succeededFuture("ok");
        }
    }

    private SecuritySchemeHandlerCollector collectorWithJwt() {
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        collector.record(
                new dev.vertique.rest.core.security.SecuritySchemeHandler() {
                    @Override
                    public String schemeName() {
                        return "jwt";
                    }

                    @Override
                    public void configure(dev.vertique.rest.core.routing.SecuritySchemeRegistry registry) {}
                },
                SimpleAuthenticationHandler.create());
        return collector;
    }

    @Test
    @DisplayName("Scoped @SecurityRequirement with auth absent fails startup (effective policy is restrictive)")
    void scopedRequirementWithoutAuth_fails() {
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new ScopedRequirementResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        collectorWithJwt(),
                        List.of(),
                        List.of(),
                        null,
                        // auth enforcement ABSENT
                        false,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));

        assertEquals(1, ex.violations().size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,
                ex.violations().get(0).type());
        assertEquals("scopedOp", ex.violations().get(0).operationId());
    }

    @Test
    @DisplayName("Scoped @SecurityRequirement with auth present registers without violation")
    void scopedRequirementWithAuth_ok() {
        assertDoesNotThrow(() -> RegistrarTestSupport.registerAll(
                registrar,
                Set.of(new ScopedRequirementResource()),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                collectorWithJwt(),
                List.of(),
                List.of(),
                null,
                // auth enforcement PRESENT — the authorization contributor enforces the folded scopes
                true,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                null,
                false));
    }

    @Test
    @DisplayName("Scopeless @SecurityRequirement with auth absent registers (authentication-only, unchanged)")
    void scopelessRequirementWithoutAuth_ok() {
        // A scopeless requirement is authentication-only: its effective policy is non-restrictive, so
        // it is unaffected by the auth-absent authorization check. The authentication handler still
        // guards it. This pins that the fix does NOT newly reject scopeless requirements.
        assertDoesNotThrow(() -> RegistrarTestSupport.registerAll(
                registrar,
                Set.of(new ScopelessRequirementResource()),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                collectorWithJwt(),
                List.of(),
                List.of(),
                null,
                // auth enforcement ABSENT
                false,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                null,
                false));
    }
}
