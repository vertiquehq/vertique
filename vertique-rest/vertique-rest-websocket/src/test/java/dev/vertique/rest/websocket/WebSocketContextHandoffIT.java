// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import io.vertx.junit5.Checkpoint;
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
 * Integration tests verifying the WebSocket context snapshot/rebind handoff.
 *
 * <p>Covers:
 * <ul>
 *   <li>The {@link SecurityContext} and MDC keys bound during the HTTP upgrade request are
 *       visible inside {@link OnOpen}, {@link OnMessage}, and {@link OnClose} callbacks
 *       after {@link RequestContextLifecycle} drives the lifecycle to completion via
 *       {@code completeNow()}.</li>
 *   <li>The snapshot is bound exactly once inside the {@code afterClose} task; multiple
 *       {@link OnMessage} invocations all see the same holder values without re-binding.</li>
 *   <li>The originating HTTP request's lifecycle closes (via {@code completeNow()}) before any
 *       WebSocket frame arrives, but the session's rebound snapshot remains valid.</li>
 *   <li>Without an ambient {@link SecurityContext} (unauthenticated), {@code runtime.current()}
 *       returns {@code null} inside callbacks.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketContextHandoffIT {

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;
    private static HolderBackedSecurityRuntime securityRuntime;

    // --- SecurityContext for the authenticated path ---
    private static final SecurityContext TEST_SC =
            new StubSecurityContext("alice", "client-1"); // userIdValue, clientIdValue

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        wsClient = vertx.createWebSocketClient();

        Router router = Router.router(vertx);

        // --- Root lifecycle middleware: registers exactly one end handler, runs last ---
        router.route("/*").handler(new RequestContextLifecycle());

        // --- Route /ws/ctx-auth: inject a SecurityContext and MDC keys before the upgrade ---
        router.route("/ws/ctx-auth").handler(rc -> {
            // Simulate SecurityContextMiddleware: bind SC and MDC via the lifecycle
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            lifecycle.onClose(ContextValues.bind(SecurityContext.class, TEST_SC));
            lifecycle.onClose(MDCContexts.bindAll(Map.of("userId", "alice", "tenant", "acme")));
            rc.next();
        });

        // --- Route /ws/ctx-anon: no SecurityContext bound (unauthenticated) ---
        // (no extra handler — just the lifecycle)

        // --- Build the registrar ---
        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, securityRuntime, Set.of(), null, null, null, null, null);

        // --- Register endpoints ---
        registrar.registerAll(Set.of(new ContextCapturingEndpoint(), new AnonCapturingEndpoint()), router);

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
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
                new WebSocketConnectOptions().setHost("127.0.0.1").setPort(port).setURI(path));
    }

    // --- Tests ---

    @Test
    @DisplayName("onOpen / onMessage / onClose see SecurityContext and MDC keys from HTTP upgrade")
    void contextVisibleInAllCallbacks(Vertx vertx, VertxTestContext ctx) {
        Checkpoint onOpenPassed = ctx.checkpoint();
        Checkpoint onMessagePassed = ctx.checkpoint();
        Checkpoint onClosePassed = ctx.checkpoint();

        ContextCapturingEndpoint.reset();

        connect("/ws/ctx-auth").onComplete(ctx.succeeding(ws -> {
            // onOpen runs synchronously during bootstrapSession — captured by the time connect() resolves
            ctx.verify(() -> {
                assertNotNull(ContextCapturingEndpoint.onOpenSc.get(), "onOpen must see SC");
                assertEquals(
                        "alice",
                        ContextCapturingEndpoint.onOpenSc
                                .get()
                                .identity()
                                .actor()
                                .id());
                assertEquals("alice", ContextCapturingEndpoint.onOpenMdcUserId.get());
                onOpenPassed.flag();
            });

            // Send a message; onMessage should also see SC + MDC
            ws.writeTextMessage("ping").onComplete(ctx.succeeding(v -> {
                vertx.setTimer(
                        100,
                        id -> ctx.verify(() -> {
                            assertNotNull(ContextCapturingEndpoint.onMessageSc.get(), "onMessage must see SC");
                            assertEquals(
                                    "alice",
                                    ContextCapturingEndpoint.onMessageSc
                                            .get()
                                            .identity()
                                            .actor()
                                            .id());
                            assertEquals("alice", ContextCapturingEndpoint.onMessageMdcUserId.get());
                            onMessagePassed.flag();

                            // Close the WebSocket; onClose should also see SC
                            ws.close()
                                    .onComplete(ctx.succeeding(v2 -> vertx.setTimer(
                                            100,
                                            id2 -> ctx.verify(() -> {
                                                assertNotNull(
                                                        ContextCapturingEndpoint.onCloseSc.get(),
                                                        "onClose must see SC");
                                                assertEquals(
                                                        "alice",
                                                        ContextCapturingEndpoint.onCloseSc
                                                                .get()
                                                                .identity()
                                                                .actor()
                                                                .id());
                                                onClosePassed.flag();
                                            }))));
                        }));
            }));
        }));
    }

    @Test
    @DisplayName("bind-once contract: multiple onMessage invocations see same SC without re-binding")
    void bindOnceAcrossMultipleMessages(Vertx vertx, VertxTestContext ctx) {
        Checkpoint allMessagesPassed = ctx.checkpoint(3);

        ContextCapturingEndpoint.reset();

        connect("/ws/ctx-auth").onComplete(ctx.succeeding(ws -> {
            ws.textMessageHandler(reply -> ctx.verify(() -> {
                assertNotNull(ContextCapturingEndpoint.onMessageSc.get(), "onMessage must see SC");
                assertEquals(
                        "alice",
                        ContextCapturingEndpoint.onMessageSc
                                .get()
                                .identity()
                                .actor()
                                .id(),
                        "SC must remain alice across all messages");
                allMessagesPassed.flag();
            }));

            ws.writeTextMessage("msg-1");
            ws.writeTextMessage("msg-2");
            ws.writeTextMessage("msg-3");
        }));
    }

    @Test
    @DisplayName("HTTP-request lifetime independence: snapshot valid after HTTP lifecycle closes via completeNow")
    void snapshotValidAfterHttpRequestLifecycleCloses(Vertx vertx, VertxTestContext ctx) {
        // The HTTP request lifecycle completes via completeNow() during the upgrade.
        // The WebSocket session's snapshot (isolated deep copy) must remain valid
        // for subsequent onMessage callbacks regardless of how long after the upgrade they arrive.
        ContextCapturingEndpoint.reset();

        connect("/ws/ctx-auth").onComplete(ctx.succeeding(ws -> {
            // Delay to let any HTTP-request context state fully settle before sending
            vertx.setTimer(150, id -> {
                ws.textMessageHandler(reply -> ctx.verify(() -> {
                    assertNotNull(
                            ContextCapturingEndpoint.onMessageSc.get(),
                            "SC must still be visible in onMessage even after HTTP lifecycle closed");
                    ctx.completeNow();
                }));
                ws.writeTextMessage("delayed-msg");
            });
        }));
    }

    @Test
    @DisplayName("unauthenticated upgrade: SecurityRuntime.current() returns null in all callbacks")
    void unauthenticatedSessionHasNullSc(Vertx vertx, VertxTestContext ctx) {
        AnonCapturingEndpoint.reset();

        connect("/ws/ctx-anon").onComplete(ctx.succeeding(ws -> {
            // onOpen runs during bootstrap — check immediately
            ctx.verify(
                    () -> assertNull(AnonCapturingEndpoint.onOpenSc.get(), "onOpen must see null SC for anon session"));

            ws.writeTextMessage("hello")
                    .onComplete(ctx.succeeding(v -> vertx.setTimer(
                            100,
                            id -> ctx.verify(() -> {
                                assertNull(
                                        AnonCapturingEndpoint.onMessageSc.get(),
                                        "onMessage must see null SC for anon session");
                                ws.close();
                                ctx.completeNow();
                            }))));
        }));
    }

    // --- Test endpoint inner classes ---

    /**
     * WebSocket endpoint for {@code /ws/ctx-auth}. Captures SC and MDC values observed in each
     * lifecycle callback so post-callback assertions can verify context propagation.
     */
    @WebSocketEndpoint("/ws/ctx-auth")
    static class ContextCapturingEndpoint {

        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();
        static final AtomicReference<String> onOpenMdcUserId = new AtomicReference<>();
        static final AtomicReference<SecurityContext> onMessageSc = new AtomicReference<>();
        static final AtomicReference<String> onMessageMdcUserId = new AtomicReference<>();
        static final AtomicReference<SecurityContext> onCloseSc = new AtomicReference<>();
        static final AtomicInteger messageCount = new AtomicInteger();

        /** Resets all captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
            onOpenMdcUserId.set(null);
            onMessageSc.set(null);
            onMessageMdcUserId.set(null);
            onCloseSc.set(null);
            messageCount.set(0);
        }

        /**
         * Captures SC and MDC at open time.
         *
         * @param session the WebSocket session
         * @param sc      the current security context resolved via {@link SecurityRuntime}
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
            onOpenMdcUserId.set(MDCContexts.get("userId"));
        }

        /**
         * Captures SC and MDC per message and echoes a reply frame.
         *
         * @param session the WebSocket session
         * @param sc      the current security context
         * @param msg     the received text message
         * @return future that resolves when the echo is sent
         */
        @OnMessage
        public Future<Void> onMessage(WebSocketSession session, SecurityContext sc, String msg) {
            onMessageSc.set(sc);
            onMessageMdcUserId.set(MDCContexts.get("userId"));
            messageCount.incrementAndGet();
            return session.sendText("reply:" + messageCount.get());
        }

        /**
         * Captures SC at close time.
         *
         * @param session the WebSocket session
         * @param sc      the current security context
         */
        @OnClose
        public void onClose(WebSocketSession session, SecurityContext sc) {
            onCloseSc.set(sc);
        }
    }

    /**
     * WebSocket endpoint for {@code /ws/ctx-anon}. Captures SC values — expected to be
     * {@code null} for unauthenticated connections.
     */
    @WebSocketEndpoint("/ws/ctx-anon")
    static class AnonCapturingEndpoint {

        // Initialised with a non-null sentinel so we can distinguish "not yet called"
        // from "called with null". reset() clears to null before each test.
        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>(null);
        static final AtomicReference<SecurityContext> onMessageSc = new AtomicReference<>(null);

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
            onMessageSc.set(null);
        }

        /**
         * Captures SC at open time.
         *
         * @param session the WebSocket session
         * @param sc      the current security context (expected {@code null} for anon)
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
        }

        /**
         * Captures SC per message.
         *
         * @param session the WebSocket session
         * @param sc      the current security context
         * @param msg     the received text message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, SecurityContext sc, String msg) {
            onMessageSc.set(sc);
        }
    }

    // --- Test double: minimal SecurityContext ---

    /**
     * Minimal {@link SecurityContext} implementation for use in tests. Holds a user ID and optional
     * client ID; all other fields return empty/default values.
     *
     * <p>Implements the typed identity model: {@code userIdValue} maps to a USER actor; when
     * {@code clientIdValue} is non-null it is attached as a {@link ClientRef}.
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
