// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.RequiresAction;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed startup validation for the WebSocket {@code @RequiresAction} action gate
 * (Phase-3 review finding C2).
 *
 * <p>A class-level {@code @RequiresAction} on a WebSocket endpoint is enforced once at upgrade by the
 * composed {@link SecurityPolicyEnforcer} action gate (FR-AUTHZ-048, ADR-0115). Three distinct
 * dependencies must all be present for the gate to be enforceable:
 * <ul>
 *   <li>the authz engine — the {@link ActionRegistry} — used to validate the action value;</li>
 *   <li>the REST enforcement pipeline — the {@link SecurityPolicyEnforcer} — that installs the gate
 *       handler at upgrade; and</li>
 *   <li>the core action {@link Authorizer} — the function the enforcer calls to actually decide the
 *       action gate. {@code ActionRegistry} and {@code Authorizer} are bound through separate optional
 *       seams, so a non-default graph can have the registry present while the authorizer is absent
 *       (finding W2).</li>
 * </ul>
 * When any of the three is absent, accepting the annotation would silently never enforce it (or fail
 * closed per-request via an NPE), so startup must fail-closed.
 */
@DisplayName("WebSocket @RequiresAction startup validation")
class WebSocketActionGateStartupTest {

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

    @Test
    @DisplayName("registrar rejects the raw IdentityResolutionMiddleware in the identity-step slot (REST origin)")
    void registrarRejectsRawRestOriginMiddleware() {
        dev.vertique.rest.security.IdentityResolutionMiddleware rawRestMiddleware =
                mock(dev.vertique.rest.security.IdentityResolutionMiddleware.class);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> new WebSocketEndpointRegistrar(
                        new WebSocketMessageCodec(),
                        mock(SecurityPolicyEnforcer.class),
                        rawRestMiddleware,
                        null,
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null),
                "the identity step must be the websocket-origin handler, never the REST-origin middleware");
        org.junit.jupiter.api.Assertions.assertTrue(
                rejected.getMessage().contains("IdentityPipelineOptions.webSocket()"), rejected.getMessage());
    }

    // --- Fixtures ---

    @WebSocketEndpoint("/ws/content")
    @RequiresAction("cms.content.read")
    static class ActionEndpoint {
        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    // --- Enforcement-pipeline-absent rejection (C2a) ---

    @Nested
    @DisplayName("enforcement pipeline absent (SecurityPolicyEnforcer null)")
    class EnforcerAbsent {

        @Test
        @DisplayName("class-level @RequiresAction with null enforcer fails startup")
        void requiresActionWithNullEnforcer_failsStartup() {
            // Engine present (non-null registry containing the action), but the enforcement pipeline
            // (SecurityPolicyEnforcer) is absent. Accepting the annotation would never enforce it.
            WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                    new WebSocketMessageCodec(),
                    null, // securityPolicyEnforcer absent
                    null,
                    null,
                    Set.of(),
                    null,
                    null,
                    null,
                    new StubActionRegistry(Set.of(CONTENT_READ)),
                    null); // authorizer absent — but enforcer-null fires first

            assertThrows(
                    IllegalStateException.class,
                    () -> registrar.registerAll(Set.of(new ActionEndpoint()), router),
                    "a class-level @RequiresAction endpoint must fail startup when the enforcer is absent");
        }

        @Test
        @DisplayName("endpoint with no @RequiresAction registers without an enforcer")
        void noRequiresActionWithNullEnforcer_registersFine() {
            WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                    new WebSocketMessageCodec(), null, null, null, Set.of(), null, null, null, null, null);

            assertDoesNotThrow(
                    () -> registrar.registerAll(Set.of(new NoActionEndpoint()), router),
                    "an endpoint without @RequiresAction must register even when the enforcer is absent");
        }
    }

    // --- Engine-absent rejection via the no-arg / null-registry scanner path (C2b) ---

    @Nested
    @DisplayName("authz engine absent (ActionRegistry null)")
    class RegistryAbsent {

        @Test
        @DisplayName("no-arg scanner rejects a @RequiresAction endpoint (null registry)")
        void noArgScanner_requiresAction_failsStartup() {
            WebSocketEndpointScanner scanner = new WebSocketEndpointScanner();
            ActionEndpoint endpoint = new ActionEndpoint();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scanner.scan(endpoint),
                    "the no-arg scanner (null registry) must reject a @RequiresAction endpoint");
        }

        @Test
        @DisplayName("registrar with null registry rejects a @RequiresAction endpoint")
        void registrarNullRegistry_requiresAction_failsStartup() {
            // Enforcer present but the authz engine (registry) is absent (null). The scanner rejects
            // the endpoint before the enforcer is ever consulted, so a bare mock suffices.
            WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                    new WebSocketMessageCodec(),
                    mock(SecurityPolicyEnforcer.class),
                    null,
                    null,
                    Set.of(),
                    null,
                    null,
                    null,
                    null,
                    null); // registry null — the scanner rejects before the authorizer check

            assertThrows(
                    IllegalArgumentException.class,
                    () -> registrar.registerAll(Set.of(new ActionEndpoint()), router),
                    "a @RequiresAction endpoint must fail startup when the authz engine (registry) is absent");
        }
    }

    // --- Authorizer-absent rejection (W2) ---

    @Nested
    @DisplayName("core Authorizer absent (registry + enforcer present)")
    class AuthorizerAbsent {

        @Test
        @DisplayName("class-level @RequiresAction with null Authorizer fails startup")
        void requiresActionWithNullAuthorizer_failsStartup() {
            // Engine present (registry contains the action) AND the enforcement pipeline present
            // (non-null enforcer), but the core Authorizer that the enforcer calls to decide the action
            // gate is absent — a non-default graph where ActionRegistry and Authorizer are bound through
            // separate optional seams. Without it the gate would NPE and fail closed per request rather
            // than the graph being rejected at boot; startup must fail-closed (W2).
            WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                    new WebSocketMessageCodec(),
                    mock(SecurityPolicyEnforcer.class),
                    null,
                    null,
                    Set.of(),
                    null,
                    null,
                    null,
                    new StubActionRegistry(Set.of(CONTENT_READ)),
                    null); // authorizer absent

            assertThrows(
                    IllegalStateException.class,
                    () -> registrar.registerAll(Set.of(new ActionEndpoint()), router),
                    "a class-level @RequiresAction endpoint must fail startup when the Authorizer is absent");
        }

        @Test
        @DisplayName("class-level @RequiresAction registers when registry, enforcer, and Authorizer all present")
        void requiresActionWithCompleteGraph_registersFine() {
            // The complete enforceable graph: engine + enforcement pipeline + Authorizer all present.
            // An action-only @RequiresAction endpoint also requires authentication, so a RouteAuthHandler
            // must be registered for route wiring to complete past auth-handler selection.
            WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                    new WebSocketMessageCodec(),
                    mock(SecurityPolicyEnforcer.class),
                    null,
                    null,
                    Set.of(new StubAuthHandler()),
                    null,
                    null,
                    null,
                    new StubActionRegistry(Set.of(CONTENT_READ)),
                    new StubAuthorizer());

            assertDoesNotThrow(
                    () -> registrar.registerAll(Set.of(new ActionEndpoint()), router),
                    "a @RequiresAction endpoint with registry + enforcer + Authorizer all present must register");
        }
    }

    @WebSocketEndpoint("/ws/none")
    static class NoActionEndpoint {
        @OnOpen
        void onOpen(WebSocketSession session) {}
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

    /**
     * Minimal {@link Authorizer} test double used only to signal "Authorizer present" in the
     * complete-graph startup test. It is never invoked at startup (the gate runs at upgrade), so its
     * decision is a benign permit.
     */
    static final class StubAuthorizer implements Authorizer {

        @Override
        public io.vertx.core.Future<dev.vertique.security.authz.AuthorizationDecision> authorize(
                dev.vertique.security.authz.AuthorizationRequest request) {
            return io.vertx.core.Future.succeededFuture(
                    dev.vertique.security.authz.AuthorizationDecision.permit("PERMITTED"));
        }

        @Override
        public io.vertx.core.Future<dev.vertique.security.authz.AuthorizationDecision> authorize(
                dev.vertique.security.SecurityContext ctx,
                ActionRef action,
                dev.vertique.security.authz.ResourceRef resource) {
            return io.vertx.core.Future.succeededFuture(
                    dev.vertique.security.authz.AuthorizationDecision.permit("PERMITTED"));
        }
    }

    /**
     * Minimal {@link dev.vertique.rest.core.security.RouteAuthHandler} that authenticates by passing
     * the request straight through. Required so an action-only {@code @RequiresAction} endpoint — which
     * always requires authentication — can complete route wiring in the complete-graph startup test.
     */
    static final class StubAuthHandler implements dev.vertique.rest.core.security.RouteAuthHandler {

        @Override
        public String schemeName() {
            return "stub";
        }

        @Override
        public io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> createHandler() {
            return io.vertx.ext.web.RoutingContext::next;
        }
    }
}
