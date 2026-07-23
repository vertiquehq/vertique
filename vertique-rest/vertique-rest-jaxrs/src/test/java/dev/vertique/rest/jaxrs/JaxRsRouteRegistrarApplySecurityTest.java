// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the defensive single-scheme guard in {@link JaxRsRouteRegistrar#applySecurity}.
 *
 * <p>{@code applySecurity} extracts {@code set.schemes().get(0)} for each set, assuming every set is
 * single-scheme. That assumption is enforced upstream by {@code DefaultSecurityPolicyValidator} (in
 * {@code rest-security}), but that validator is an SPI that an application may not wire. The registrar
 * must therefore protect itself: a multi-scheme AND-set reaching {@code applySecurity} must fail
 * startup with a {@link RestConfigurationException} rather than silently mounting with only the first
 * scheme of the set enforced. Single-scheme sets (direct or OR) must continue to register normally.
 *
 * <p>Real {@link SimpleAuthenticationHandler}s are used (not mocks) so the OR composition into a
 * Vert.x {@code ChainAuthHandler.any()} — which casts to an internal handler type — succeeds; the
 * single/multi-scheme cases exercise the guard, the OR case proves the guard leaves the existing OR
 * path intact.
 */
class JaxRsRouteRegistrarApplySecurityTest {

    private Vertx vertx;
    private Router router;
    private Route route;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
        route = router.route();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("Multi-scheme AND-set fails startup with RestConfigurationException, even with both handlers present")
    void multiSchemeSet_throws() {
        AuthenticationHandler jwt = SimpleAuthenticationHandler.create();
        AuthenticationHandler apiKey = SimpleAuthenticationHandler.create();
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        collector.record(stub("jwt"), jwt);
        collector.record(stub("apiKey"), apiKey);

        SecurityRequirementSet andSet = new SecurityRequirementSet(
                List.of(new SecurityRequirement("jwt", List.of()), new SecurityRequirement("apiKey", List.of())));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> JaxRsRouteRegistrar.applySecurity("multiSchemeOp", route, List.of(andSet), collector));

        assertTrue(ex.getMessage().contains("multiSchemeOp"), "message should name the operationId");
    }

    @Test
    @DisplayName("Single-scheme set registers normally (no throw)")
    void singleSchemeSet_registers() {
        AuthenticationHandler jwt = SimpleAuthenticationHandler.create();
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        collector.record(stub("jwt"), jwt);

        SecurityRequirementSet singleSet =
                new SecurityRequirementSet(List.of(new SecurityRequirement("jwt", List.of())));

        assertDoesNotThrow(() -> JaxRsRouteRegistrar.applySecurity("singleOp", route, List.of(singleSet), collector));
    }

    @Test
    @DisplayName("Two single-scheme OR sets register normally (no throw)")
    void orOfSingleSchemeSets_registers() {
        AuthenticationHandler jwt = SimpleAuthenticationHandler.create();
        AuthenticationHandler apiKey = SimpleAuthenticationHandler.create();
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        collector.record(stub("jwt"), jwt);
        collector.record(stub("apiKey"), apiKey);

        SecurityRequirementSet setA = new SecurityRequirementSet(List.of(new SecurityRequirement("jwt", List.of())));
        SecurityRequirementSet setB = new SecurityRequirementSet(List.of(new SecurityRequirement("apiKey", List.of())));

        assertDoesNotThrow(() -> JaxRsRouteRegistrar.applySecurity("orOp", route, List.of(setA, setB), collector));
    }

    /** Minimal {@link SecuritySchemeHandler} stub reporting a fixed scheme name. */
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
