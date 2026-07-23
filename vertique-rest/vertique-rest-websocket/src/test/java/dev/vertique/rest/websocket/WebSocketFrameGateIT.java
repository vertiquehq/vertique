// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.context.ContextValues;
import dev.vertique.logging.MDCContexts;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test that pins the pause/resume frame-gate ordering in {@link WebSocketEndpointRegistrar}.
 *
 * <p>The registrar calls {@code ws.pause()} immediately after the successful upgrade and only calls
 * {@code ws.resume()} at the end of the {@code afterClose} bootstrap task — after the
 * {@link dev.vertique.context.ContextSnapshot} has been bound and all frame handlers have been
 * installed. This test is the explicit regression guard: it verifies that a text frame sent by the
 * client on the same call-chain as the handshake completion (no deliberate delay) is still delivered
 * to the user's {@link OnMessage} handler exactly once, with the correct {@link SecurityContext} and
 * MDC values visible.
 *
 * <p>The frame is buffered by Netty in the read direction because the socket is paused. The
 * pause/resume invariant guarantees the frame is dispatched to the user's handler only after the
 * bootstrap task completes, preventing a race between frame arrival and handler installation.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketFrameGateIT {

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;
    private static HolderBackedSecurityRuntime securityRuntime;

    // --- SecurityContext injected on the upgrade request ---
    private static final SecurityContext GATE_SC = new StubSecurityContext("bob", "client-gate");

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        wsClient = vertx.createWebSocketClient();

        Router router = Router.router(vertx);

        // Root lifecycle middleware — required by WebSocketEndpointRegistrar
        router.route("/*").handler(new RequestContextLifecycle());

        // Bind SecurityContext and MDC before the upgrade
        router.route("/ws/gate").handler(rc -> {
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            lifecycle.onClose(ContextValues.bind(SecurityContext.class, GATE_SC));
            lifecycle.onClose(MDCContexts.bindAll(Map.of("userId", "bob", "requestId", "req-gate-001")));
            rc.next();
        });

        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, securityRuntime, Set.of(), null, null, null, null, null);

        registrar.registerAll(Set.of(new GateEndpoint()), router);

        vertx.createHttpServer().requestHandler(router).listen(0).onComplete(ctx.succeeding(s -> {
            server = s;
            port = s.actualPort();
            ctx.completeNow();
        }));
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> s = server != null ? server.close() : Future.succeededFuture();
        Future<?> c = wsClient != null ? wsClient.close() : Future.succeededFuture();
        Future.join(s, c).onComplete(ar -> ctx.completeNow());
    }

    // --- Connect helper ---

    private Future<WebSocket> connect(String path) {
        return wsClient.connect(
                new WebSocketConnectOptions().setHost("localhost").setPort(port).setURI(path));
    }

    // --- Tests ---

    @Test
    @DisplayName(
            "frame sent immediately on handshake is delivered to onMessage with correct SC and MDC — pause/resume gate")
    void frameSentImmediatelyOnHandshakeIsDeliveredWithContext(Vertx vertx, VertxTestContext ctx) {
        GateEndpoint.reset();

        connect("/ws/gate").onComplete(ctx.succeeding(ws -> {
            // Send a frame on the same call-chain that resolved the connect future.
            // The socket is paused on the server side at this point — the registrar will only
            // call ws.resume() after the afterClose bootstrap task has finished binding the
            // snapshot and installing handlers. The frame is buffered by Netty and dispatched
            // to onMessage only once the socket is resumed.
            ws.writeTextMessage("gate-probe");

            // Wait for the onMessage to be invoked on the server and capture results
            vertx.setTimer(
                    500,
                    id -> ctx.verify(() -> {
                        // (a) Frame was received exactly once
                        assertEquals(1, GateEndpoint.messageCount.get(), "frame must be received exactly once");

                        // (b) SecurityContext was correct inside onMessage
                        SecurityContext captured = GateEndpoint.onMessageSc.get();
                        assertNotNull(captured, "SecurityContext must be visible inside onMessage");
                        assertEquals("bob", captured.identity().actor().id(), "userId must be 'bob'");

                        // (c) MDC keys were populated inside onMessage
                        Map<String, String> capturedMdc = GateEndpoint.onMessageMdc.get();
                        assertNotNull(capturedMdc, "MDC must be captured inside onMessage");
                        assertEquals("bob", capturedMdc.get("userId"), "MDC userId must be 'bob'");
                        assertEquals("req-gate-001", capturedMdc.get("requestId"), "MDC requestId must be populated");

                        ws.close();
                        ctx.completeNow();
                    }));
        }));
    }

    // --- Endpoint ---

    /**
     * WebSocket endpoint at {@code /ws/gate}. Captures the {@link SecurityContext} and MDC values
     * observed inside {@link OnMessage} so the test can assert the pause/resume invariant.
     */
    @WebSocketEndpoint("/ws/gate")
    static class GateEndpoint {

        static final AtomicReference<SecurityContext> onMessageSc = new AtomicReference<>();
        static final AtomicReference<Map<String, String>> onMessageMdc = new AtomicReference<>();
        static final AtomicInteger messageCount = new AtomicInteger();

        /** Resets all captured state before a test run. */
        static void reset() {
            onMessageSc.set(null);
            onMessageMdc.set(null);
            messageCount.set(0);
        }

        /**
         * Captures the {@link SecurityContext} and MDC snapshot at message-dispatch time.
         *
         * @param session the WebSocket session
         * @param sc      the current security context
         * @param msg     the received text message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, SecurityContext sc, String msg) {
            onMessageSc.set(sc);
            onMessageMdc.set(MDCContexts.copy());
            messageCount.incrementAndGet();
        }
    }

    // --- Stub SecurityContext ---

    /**
     * Minimal {@link SecurityContext} implementation for use in tests. Holds a user ID and optional
     * client ID; implements the typed identity model.
     *
     * @param userIdValue   the user identifier
     * @param clientIdValue the optional client identifier
     */
    record StubSecurityContext(String userIdValue, String clientIdValue) implements SecurityContext {

        @Override
        public SecurityIdentity identity() {
            PrincipalRef actor = new PrincipalRef(PrincipalType.USER, userIdValue, Map.of());
            Optional<ClientRef> clientRef = clientIdValue != null
                    ? Optional.of(new ClientRef(clientIdValue, "test", Map.of()))
                    : Optional.empty();
            return new SecurityIdentity(actor, Optional.empty(), Optional.empty(), clientRef);
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }
}
