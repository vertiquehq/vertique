// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.websocket.dagger.WebSocketEndpoints;
import dev.vertique.rest.websocket.dagger.WebSocketModule;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Dagger graph-compilation and behavior proof (TP-001, T008) for a WebSocket-only component: a
 * {@link WebSocketModule}-only graph (no {@code AuthModule}/{@code SecurityModule}) that exposes
 * {@link Set}{@code <}{@link RouterMount}{@code >} and {@link WebSocketMount.Factory}.
 *
 * <p>Two nested {@code @Component} interfaces share the same module shape (config/Vert.x stand-ins
 * plus a stub {@link RouteAuthHandler} contribution, covering the "auth handler bound without the
 * pipeline" case per the contract): {@link UnannotatedOnlyComponent} contributes a single
 * unannotated endpoint, and {@link RestrictiveEndpointsComponent} contributes a {@code @DenyAll} and
 * a {@code @RolesAllowed("admin")} endpoint together.
 *
 * <p>Expected initial result (before T008's production slice lands): {@code test-compile} fails
 * Dagger validation — {@code WebSocketMount.Factory}'s current {@code @Inject} constructor requires
 * {@code Optional<AuthorizationGateConfig>}, which only {@code AuthModule} declares
 * {@code @BindsOptionalOf} for; a {@code WebSocketModule}-only graph exposing the factory cannot
 * satisfy it. T008's frozen single-{@code Optional<IdentityPipelineFactory>} constructor removes
 * that requirement.
 */
@ExtendWith(VertxExtension.class)
class WebSocketOnlyMountComponentTest {

    // --- Config/Vert.x stand-ins (mirrors RateLimitCoreTestFixtures.ConfigFixtureModule) ---

    /**
     * Supplies an empty config section and a minimal real {@link ConfigParser} (plain Jackson, no
     * framework config-mapper dependency is available from this module's test scope) so
     * {@code WebSocketModule.webSocketConfig} resolves to {@link WebSocketConfig}'s defaults.
     */
    @Module
    static final class ConfigFixtureModule {

        @Provides
        @VertxConfig
        static JsonObject vertxConfig() {
            return new JsonObject();
        }

        @Provides
        static ConfigParser configParser() {
            return new ConfigParser() {
                private final ObjectMapper mapper = new ObjectMapper();

                @Override
                public <T> T parse(JsonObject section, Class<T> type) {
                    JsonObject json = section != null ? section : new JsonObject();
                    try {
                        return mapper.readValue(json.encode(), type);
                    } catch (Exception e) {
                        throw new ConfigurationException(
                                "failed to parse test config section into " + type.getName(), e);
                    }
                }

                @Override
                public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                    throw new UnsupportedOperationException("not needed by this fixture");
                }

                @Override
                public <T> List<T> parseKeyedObject(
                        JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                    throw new UnsupportedOperationException("not needed by this fixture");
                }
            };
        }
    }

    /**
     * Contributes one {@link RouteAuthHandler} without any identity pipeline bound — the AC-010.2
     * "auth-handler-without-pipeline" case the contract calls out.
     */
    @Module
    static final class RouteAuthHandlerFixtureModule {

        @Provides
        @IntoSet
        static RouteAuthHandler stubRouteAuthHandler() {
            return new RouteAuthHandler() {
                @Override
                public String schemeName() {
                    return "stub";
                }

                @Override
                public Handler<RoutingContext> createHandler() {
                    return RoutingContext::next;
                }
            };
        }
    }

    /** Contributes the single unannotated endpoint fixture. */
    @Module
    static final class UnannotatedEndpointModule {

        @Provides
        @IntoSet
        @WebSocketEndpoints
        static Object endpoint() {
            return new UnannotatedEndpoint();
        }
    }

    /** Contributes the {@code @DenyAll} and {@code @RolesAllowed("admin")} endpoint fixtures together. */
    @Module
    static final class RestrictiveEndpointsModule {

        @Provides
        @IntoSet
        @WebSocketEndpoints
        static Object denyAllEndpoint() {
            return new DenyAllEndpoint();
        }

        @Provides
        @IntoSet
        @WebSocketEndpoints
        static Object adminOnlyEndpoint() {
            return new AdminOnlyEndpoint();
        }
    }

    /** Component contributing only the unannotated endpoint fixture. */
    @Singleton
    @Component(
            modules = {
                WebSocketModule.class,
                ConfigFixtureModule.class,
                RouteAuthHandlerFixtureModule.class,
                UnannotatedEndpointModule.class
            })
    interface UnannotatedOnlyComponent {

        Set<RouterMount> routerMounts();

        WebSocketMount.Factory mountFactory();
    }

    /** Component contributing the {@code @DenyAll} and {@code @RolesAllowed("admin")} fixtures. */
    @Singleton
    @Component(
            modules = {
                WebSocketModule.class,
                ConfigFixtureModule.class,
                RouteAuthHandlerFixtureModule.class,
                RestrictiveEndpointsModule.class
            })
    interface RestrictiveEndpointsComponent {

        Set<RouterMount> routerMounts();

        WebSocketMount.Factory mountFactory();
    }

    // --- Endpoint fixtures ---

    /** No security annotations — must register and serve unauthenticated (FR-010, AC-008.1). */
    @WebSocketEndpoint("/ws/open")
    static class UnannotatedEndpoint {
        @OnOpen
        public void onOpen(WebSocketSession session) {
            // no-op: this test asserts on registration/build outcomes only
        }
    }

    /** {@code @DenyAll} — restrictive policy; must fail closed when the pipeline is absent (AC-010.2). */
    @WebSocketEndpoint("/ws/deny")
    @DenyAll
    static class DenyAllEndpoint {
        @OnOpen
        public void onOpen(WebSocketSession session) {
            // no-op: this test asserts on registration/build outcomes only
        }
    }

    /** {@code @RolesAllowed("admin")} — restrictive policy; must fail closed when the pipeline is absent (AC-010.2). */
    @WebSocketEndpoint("/ws/admin")
    @RolesAllowed("admin")
    static class AdminOnlyEndpoint {
        @OnOpen
        public void onOpen(WebSocketSession session) {
            // no-op: this test asserts on registration/build outcomes only
        }
    }

    // --- Test ---

    @Test
    @DisplayName("WebSocket-only component builds an unannotated mount and fails closed on restrictive endpoints")
    void webSocketOnlyGraphBuildsMountsWithoutSecurity(Vertx vertx) {
        // --- Given/When: a WebSocketModule-only graph with one unannotated endpoint ---
        UnannotatedOnlyComponent openComponent =
                DaggerWebSocketOnlyMountComponentTest_UnannotatedOnlyComponent.create();

        WebSocketMount.Factory factory = openComponent.mountFactory();
        // Package-private field access (same package as WebSocketMount.Factory).
        assertNull(
                factory.identityResolutionHandler,
                "identity handler must be null when no IdentityPipelineFactory is bound");
        assertNull(factory.securityPolicyEnforcer, "enforcer must be null when no IdentityPipelineFactory is bound");

        Set<RouterMount> openMounts = openComponent.routerMounts();
        assertEquals(1, openMounts.size(), "exactly one mount must be produced for the contributed endpoint");
        RouterMount openMount = openMounts.iterator().next();

        // --- Then: an unannotated endpoint registers and serves without error ---
        assertDoesNotThrow(
                () -> openMount.createRouter(vertx), "an unannotated endpoint must register without error (FR-010)");

        // --- Given/When: the same graph shape, but with @DenyAll and @RolesAllowed("admin") endpoints ---
        RestrictiveEndpointsComponent restrictiveComponent =
                DaggerWebSocketOnlyMountComponentTest_RestrictiveEndpointsComponent.create();
        RouterMount restrictiveMount =
                restrictiveComponent.routerMounts().iterator().next();

        // --- Then: registration fails closed with one aggregated exception naming both endpoints
        //     and their policies, before any auth handler is installed (AC-010.2) ---
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> restrictiveMount.createRouter(vertx),
                "@DenyAll/@RolesAllowed endpoints must fail registration closed when no pipeline is bound");
        assertTrue(
                thrown.getMessage().contains(DenyAllEndpoint.class.getName()),
                "the aggregated exception must name the @DenyAll endpoint: " + thrown.getMessage());
        assertTrue(
                thrown.getMessage().contains(AdminOnlyEndpoint.class.getName()),
                "the aggregated exception must name the @RolesAllowed endpoint: " + thrown.getMessage());
    }
}
