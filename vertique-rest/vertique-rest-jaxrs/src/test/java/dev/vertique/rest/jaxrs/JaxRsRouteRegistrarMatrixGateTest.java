// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.AuthenticationHandler;
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
 * Verifies the registrar enforces the <strong>full</strong> fail-closed security matrix even when
 * {@code securityPolicyValidator == null} — i.e. when an application wires {@code rest-jaxrs} WITHOUT
 * {@code rest-security} (which is the only module that binds {@code DefaultSecurityPolicyValidator}).
 *
 * <p>In that configuration the registrar's always-on gate is the <em>only</em> defense. It must reject
 * the three deferred V1 shapes: a multi-scheme AND-set, a scoped-OR (more than one alternative where
 * any alternative carries scopes), and the both-scopes shape ({@code @Authorized(scopes)} together with
 * a scoped {@code @SecurityRequirement}). Supported shapes (public, single-scheme scoped/scopeless,
 * scopeless-OR) must register without throwing.
 *
 * <p>Before this gate was made validator-independent, the scoped-OR and both-scopes shapes mounted
 * silently with {@code validator == null}: a scoped-OR mounted authenticated-but-not-scope-enforced,
 * and a both-scopes operation had its {@code @Authorized} scopes silently replaced by the
 * {@code @SecurityRequirement} scopes (ADR-0124's promised fail-closed defense was absent).
 */
class JaxRsRouteRegistrarMatrixGateTest {

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

    // --- Test resources ---

    /** Scoped-OR: {@code {bearerAuth:[write]}} OR {@code {apiKey:[]}} — deferred V1 shape. */
    @Path("/scoped-or")
    static class ScopedOrResource {
        @GET
        @Operation(
                operationId = "scopedOrGet",
                security = {
                    @SecurityRequirement(name = "bearerAuth", scopes = "write"),
                    @SecurityRequirement(name = "apiKey")
                })
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Both-scopes: {@code @Authorized(scopes)} plus a scoped {@code @SecurityRequirement} — deferred V1 shape. */
    @Path("/both-scopes")
    static class BothScopesResource {
        @GET
        @Operation(
                operationId = "bothScopesGet",
                security = {@SecurityRequirement(name = "oauth2", scopes = "read")})
        @Authorized(scopes = "write")
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Multi-scheme AND-set via {@code combine()} — deferred V1 shape. */
    @Path("/multi-scheme")
    static class MultiSchemeResource {
        @GET
        @Operation(
                operationId = "multiSchemeGet",
                security =
                        @SecurityRequirement(
                                combine = {
                                    @SecurityRequirementEntry(name = "jwt"),
                                    @SecurityRequirementEntry(name = "apiKey")
                                }))
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Public operation — supported shape. */
    @Path("/public")
    static class PublicResource {
        @GET
        @Operation(operationId = "publicGet")
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Single-scheme scoped operation — supported shape. */
    @Path("/single-scoped")
    static class SingleSchemeScopedResource {
        @GET
        @Operation(
                operationId = "singleScopedGet",
                security = {@SecurityRequirement(name = "oauth2", scopes = "read")})
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Single-scheme scopeless operation — supported shape. */
    @Path("/single-scopeless")
    static class SingleSchemeScopelessResource {
        @GET
        @Operation(
                operationId = "singleScopelessGet",
                security = {@SecurityRequirement(name = "bearerAuth")})
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    /** Scopeless OR of two single-scheme sets — supported shape. */
    @Path("/scopeless-or")
    static class ScopelessOrResource {
        @GET
        @Operation(
                operationId = "scopelessOrGet",
                security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKey")})
        public Future<String> get() {
            return Future.succeededFuture("ok");
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("scoped-OR fails startup with validator==null (registrar is the only defense)")
    void scopedOr_failsStartup_withoutValidator() {
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new ScopedOrResource()),
                        router,
                        collectorWith("bearerAuth", "apiKey"),
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
        assertTrue(ex.getMessage().contains("scopedOrGet"), "message must name the operationId");
    }

    @Test
    @DisplayName("both-scopes fails startup with validator==null (registrar is the only defense)")
    void bothScopes_failsStartup_withoutValidator() {
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new BothScopesResource()),
                        router,
                        collectorWith("oauth2"),
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
        assertTrue(ex.getMessage().contains("bothScopesGet"), "message must name the operationId");
    }

    @Test
    @DisplayName("multi-scheme AND-set still fails startup with validator==null")
    void multiScheme_failsStartup_withoutValidator() {
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new MultiSchemeResource()),
                        router,
                        collectorWith("jwt", "apiKey"),
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
        assertTrue(ex.getMessage().contains("multiSchemeGet"), "message must name the operationId");
    }

    @Test
    @DisplayName("supported shapes register without throwing (validator==null)")
    void supportedShapes_register_withoutValidator() {
        assertDoesNotThrow(() -> RegistrarTestSupport.registerAll(
                registrar,
                Set.of(
                        new PublicResource(),
                        new SingleSchemeScopedResource(),
                        new SingleSchemeScopelessResource(),
                        new ScopelessOrResource()),
                router,
                collectorWith("oauth2", "bearerAuth", "apiKey"),
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
    }

    // --- Helpers ---

    /**
     * Builds a {@link SecuritySchemeHandlerCollector} with a real {@link SimpleAuthenticationHandler}
     * registered for each named scheme, so the registrar's missing-handler fail-closed gate does not
     * fire before the matrix gate under test.
     *
     * @param schemeNames the scheme names to register handlers for
     * @return a populated collector
     */
    private static SecuritySchemeHandlerCollector collectorWith(String... schemeNames) {
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        for (String schemeName : schemeNames) {
            AuthenticationHandler handler = SimpleAuthenticationHandler.create();
            collector.record(stub(schemeName), handler);
        }
        return collector;
    }

    /**
     * Minimal {@link SecuritySchemeHandler} stub reporting a fixed scheme name.
     *
     * @param schemeName the scheme name the stub owns
     * @return the stub handler
     */
    private static SecuritySchemeHandler stub(String schemeName) {
        return new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return schemeName;
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {}
        };
    }
}
