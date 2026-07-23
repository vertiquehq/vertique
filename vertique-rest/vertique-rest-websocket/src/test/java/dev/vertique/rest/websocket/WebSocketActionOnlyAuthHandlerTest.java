// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.RequiresAction;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that an action-only WebSocket endpoint ({@link dev.vertique.rest.core.security.SecurityPolicy.None}
 * carrying a class-level {@code @RequiresAction}) installs an authentication handler so the action
 * gate evaluates the real caller's identity rather than an anonymous one (Phase-3 review finding C2b).
 *
 * <p>Before the fix, {@code installAuthenticationHandler} ran only for {@code AuthenticatedOnly} /
 * {@code Constrained} policies, so a {@code None} + {@code @RequiresAction} endpoint had no
 * {@link RouteAuthHandler} on its route and the action gate evaluated an anonymous identity. The fix
 * treats a present {@code requiredAction} as also requiring authentication.
 */
@DisplayName("WebSocket action-only endpoint authentication")
class WebSocketActionOnlyAuthHandlerTest {

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // --- Fixtures ---

    /** Action-only endpoint: no Jakarta role/scope policy, only a class-level {@code @RequiresAction}. */
    @WebSocketEndpoint("/ws/content")
    @RequiresAction("cms.content.read")
    static class ActionOnlyEndpoint {
        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    /** Plain endpoint with no security at all. */
    @WebSocketEndpoint("/ws/open")
    static class OpenEndpoint {
        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    // --- Tests ---

    @Test
    @DisplayName("action-only endpoint installs an auth handler (gate sees the real identity)")
    void actionOnlyEndpoint_installsAuthHandler() {
        CountingAuthHandler authHandler = new CountingAuthHandler();
        WebSocketEndpointRegistrar registrar = registrarWith(authHandler);

        registrar.registerAll(Set.of(new ActionOnlyEndpoint()), router);

        assertTrue(
                authHandler.created,
                "an action-only @RequiresAction endpoint must install an authentication handler so the "
                        + "action gate evaluates the real caller's identity, not an anonymous one");
    }

    @Test
    @DisplayName("plain endpoint with no security installs no auth handler")
    void openEndpoint_installsNoAuthHandler() {
        CountingAuthHandler authHandler = new CountingAuthHandler();
        WebSocketEndpointRegistrar registrar = registrarWith(authHandler);

        registrar.registerAll(Set.of(new OpenEndpoint()), router);

        assertFalse(authHandler.created, "an unsecured endpoint must not install an authentication handler");
    }

    // --- Helpers ---

    private WebSocketEndpointRegistrar registrarWith(RouteAuthHandler authHandler) {
        return new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(),
                permissiveEnforcer(),
                null,
                null,
                Set.of(authHandler),
                null,
                null,
                null,
                new StubActionRegistry(Set.of(CONTENT_READ)),
                // Authorizer present (mock): this test exercises the auth-handler wiring, not the
                // action-gate decision, so a bare mock satisfies the W2 startup presence check.
                mock(Authorizer.class));
    }

    /** A mock enforcer returning a no-op handler so route wiring proceeds past authorization. */
    private static SecurityPolicyEnforcer permissiveEnforcer() {
        SecurityPolicyEnforcer enforcer = mock(SecurityPolicyEnforcer.class);
        when(enforcer.createHandler(any(SecurityPolicy.class), any())).thenReturn(RoutingContext::next);
        return enforcer;
    }

    /**
     * {@link RouteAuthHandler} that records whether {@link #createHandler()} was invoked, signalling
     * that the registrar decided this route needs authentication.
     */
    static final class CountingAuthHandler implements RouteAuthHandler {
        private boolean created;

        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            created = true;
            return RoutingContext::next;
        }
    }

    /**
     * Minimal {@link ActionRegistry} backed by a fixed set of registered {@link ActionRef}s.
     *
     * @param registered the set of action references considered registered
     */
    record StubActionRegistry(Set<ActionRef> registered) implements ActionRegistry {

        @Override
        public Collection<ActionDefinition> actions() {
            return registered.stream().map(ActionDefinition::new).toList();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return registered.contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return registered.contains(action);
        }
    }
}
