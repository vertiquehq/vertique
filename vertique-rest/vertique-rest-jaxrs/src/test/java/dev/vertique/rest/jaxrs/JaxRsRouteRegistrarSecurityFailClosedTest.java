// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the registrar's <strong>fail-closed</strong> security gate (finding C1): an operation
 * that declares a {@code @SecurityRequirement} whose scheme has no collected
 * {@link io.vertx.ext.web.handler.AuthenticationHandler} must fail startup rather than mount with no
 * authentication (a fail-OPEN route). A public operation (no requirement) still mounts with no
 * handler.
 */
class JaxRsRouteRegistrarSecurityFailClosedTest {

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

    /** Resource declaring a class-level {@code @SecurityRequirement} for the {@code bearerAuth} scheme. */
    @Path("/secured")
    @SecurityRequirement(name = "bearerAuth")
    static class SecuredResource {

        /** Secured GET inheriting the class-level requirement. */
        @GET
        @Operation(operationId = "securedGet")
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Public resource declaring no security requirement. */
    @Path("/public")
    static class PublicResource {

        /** Unsecured GET. */
        @GET
        @Operation(operationId = "publicGet")
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    @Test
    @DisplayName("Declared @SecurityRequirement with no collected handler fails startup (fail-closed)")
    void declaredSchemeWithNoHandler_failsStartup() {
        // Empty collector: no AuthenticationHandler is registered for "bearerAuth". A null
        // securityPolicyValidator proves the guard is independent of that validator.
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new SecuredResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        new SecuritySchemeHandlerCollector(),
                        List.of(),
                        List.of(),
                        null,
                        true,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));

        assertTrue(ex.getMessage().contains("securedGet"), "message names the operationId");
        assertTrue(ex.getMessage().contains("bearerAuth"), "message names the missing scheme");
    }

    @Test
    @DisplayName("Public operation (no @SecurityRequirement) mounts without an auth handler")
    void publicOperation_mountsWithoutHandler() {
        // No requirement declared → no fail-closed guard; registration completes without throwing.
        RegistrarTestSupport.registerAll(
                registrar,
                Set.of(new PublicResource()),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                new SecuritySchemeHandlerCollector(),
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                null,
                false);
    }
}
