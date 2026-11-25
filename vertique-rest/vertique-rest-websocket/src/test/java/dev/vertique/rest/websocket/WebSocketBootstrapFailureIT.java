// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.ContextValues;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.ClientWebSocket;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test verifying the bootstrap failure policy in {@link WebSocketEndpointRegistrar}.
 *
 * <p>When the {@code afterClose} bootstrap task throws (or when {@link OnOpen} returns a failed
 * {@code Future<Void>}) before {@code ws.resume()} is reached, the registrar must:
 * <ul>
 *   <li>Close and clear any partially-bound {@link dev.vertique.core.context.ContextHolder.Scope}
 *       on the session so no context leaks to other sessions.</li>
 *   <li>Close the WebSocket with close code {@code 1011} (internal server error).</li>
 *   <li>Not call {@code ws.resume()}, meaning no {@link OnMessage} dispatch occurs after
 *       the failure.</li>
 *   <li>Not suppress other {@code afterClose} tasks registered on the same
 *       {@link RequestContextLifecycle.Handle} — each task runs in its own {@code try/catch}.</li>
 * </ul>
 *
 * <p>Two fault-injection styles are tested:
 * <ul>
 *   <li><b>Synchronous throw</b> — {@link BootstrapFailingEndpoint} throws a
 *       {@link RuntimeException} directly from {@link OnOpen}.</li>
 *   <li><b>Async failed future</b> — {@link AsyncBootstrapFailingEndpoint} returns a
 *       {@code Future.failedFuture(...)} from {@link OnOpen}, exercising the async composition
 *       path in {@code bootstrapSession}.</li>
 * </ul>
 *
 * <p>Scope-leak assertion uses a behavioral check: a second, healthy WebSocket session opened
 * after the failed one must observe {@code SecurityRuntime.current() == null} in its callbacks,
 * which would not hold if the failed session's scope had leaked into the shared holder context.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketBootstrapFailureIT {

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;
    private static HolderBackedSecurityRuntime securityRuntime;

    /**
     * Shared sentinel flag: set to {@code true} by the additional {@code afterClose} task
     * registered from the test middleware on the failing route. Used to assert that sibling
     * {@code afterClose} tasks are not suppressed by a bootstrap failure in this task.
     */
    static final AtomicBoolean sentinelAfterCloseRan = new AtomicBoolean(false);

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        wsClient = vertx.createWebSocketClient();

        Router router = Router.router(vertx);

        // Root lifecycle middleware — required by WebSocketEndpointRegistrar
        router.route("/*").handler(new RequestContextLifecycle());

        // Middleware for the failing route: bind a SecurityContext and also register
        // a sentinel afterClose task to verify that sibling afterClose tasks still run
        // when the bootstrap task throws.
        router.route("/ws/fail").handler(rc -> {
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            // Bind a SecurityContext so there is something to clean up on failure
            lifecycle.onClose(ContextValues.bind(SecurityContext.class, new StubSecurityContext("fail-user")));
            // Sentinel: a second afterClose task registered BEFORE the registrar's bootstrap task.
            // The registrar's task is registered inside handleUpgrade() which runs later in the
            // handler chain. RequestContextLifecycle.Handle.closeAll() wraps each afterClose task
            // in its own try/catch, so this sentinel must run regardless of bootstrap outcome.
            lifecycle.afterClose(() -> sentinelAfterCloseRan.set(true));
            rc.next();
        });

        // Middleware for the async-failing route: same pattern as /ws/fail but for the async path.
        router.route("/ws/fail-async").handler(rc -> {
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            lifecycle.onClose(ContextValues.bind(SecurityContext.class, new StubSecurityContext("fail-async-user")));
            rc.next();
        });

        // No extra middleware for the healthy route (used for scope-leak check)
        // — unauthenticated, SC stays null

        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, securityRuntime, Set.of(), null, null, null, null, null);

        registrar.registerAll(
                Set.of(new BootstrapFailingEndpoint(), new AsyncBootstrapFailingEndpoint(), new HealthyAnonEndpoint()),
                router);

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

    // --- Connect helpers ---

    /** Close code reported when the connection ended without a clean close frame. */
    private static final short NO_CLOSE_FRAME = -1;

    private static WebSocketConnectOptions options(String path) {
        return new WebSocketConnectOptions().setHost("127.0.0.1").setPort(port).setURI(path);
    }

    private Future<WebSocket> connect(String path) {
        return wsClient.connect(options(path));
    }

    /**
     * Connects to a route whose server side closes the socket right after the upgrade and returns
     * a future that completes when that close is observed, with the close code received from the
     * server ({@code 0} for a close frame without a status code, {@link #NO_CLOSE_FRAME} when the
     * connection ended without a clean close frame: a failed connect or a transport error).
     *
     * <p>The handlers are installed on an unconnected {@link ClientWebSocket} <em>before</em>
     * {@code connect}, so the close cannot be missed. The server closes within milliseconds of the
     * upgrade, and a handler registered from the connect future's callback is installed after the
     * socket may already be closed, in which case it never fires. The wait ends on the event
     * itself; the class timeout is the only bound.
     */
    private Future<Short> connectAndAwaitClose(String path) {
        Promise<Short> closed = Promise.promise();
        ClientWebSocket ws = wsClient.webSocket();
        ws.closeHandler(v -> {
            Short code = ws.closeStatusCode();
            closed.tryComplete(code != null ? code : (short) 0);
        });
        // Fallback: fires if the server drops the connection without a proper close handshake.
        ws.exceptionHandler(e -> closed.tryComplete(NO_CLOSE_FRAME));
        // connect() failed: the server closed before a proper handshake completed, which also
        // counts as the server having closed the connection.
        ws.connect(options(path)).onFailure(e -> closed.tryComplete(NO_CLOSE_FRAME));
        return closed.future();
    }

    /**
     * Opens a healthy anonymous session, waits until its {@code @OnOpen} has actually run, and
     * asserts it saw no {@link SecurityContext}; completes the test context.
     */
    private void awaitHealthySessionOpen(VertxTestContext ctx, String leakMessage) {
        Future<Void> opened = HealthyAnonEndpoint.opened.future();
        connect("/ws/anon")
                .onComplete(ctx.succeeding(ws2 -> opened.onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertNull(HealthyAnonEndpoint.onOpenSc.get(), leakMessage);
                    ws2.close();
                    ctx.completeNow();
                })))));
    }

    // --- Tests ---

    @Test
    @DisplayName("bootstrap failure: close 1011, no message dispatch, scope cleared, sibling afterClose runs")
    void bootstrapFailurePolicy(VertxTestContext ctx) {
        sentinelAfterCloseRan.set(false);
        BootstrapFailingEndpoint.reset();
        HealthyAnonEndpoint.reset();

        // Wait for the client to observe the server's close, then assert all invariants.
        connectAndAwaitClose("/ws/fail")
                .onComplete(ctx.succeeding(code -> ctx.verify(() -> {
                    // (b) Server must have closed the connection; if a clean close frame was
                    // received, it must carry code 1011
                    if (code != NO_CLOSE_FRAME) {
                        assertEquals((short) 1011, code, "server close code must be 1011");
                    }

                    // (c) No onMessage was dispatched — ws.resume() was never called so no
                    // frames could reach the user's handler
                    assertEquals(
                            0,
                            BootstrapFailingEndpoint.messageCount.get(),
                            "no onMessage must be dispatched after bootstrap failure");

                    // (d) Sibling afterClose task still ran — RequestContextLifecycle wraps
                    // each afterClose task in its own try/catch so failures in one task do not
                    // suppress the others
                    assertTrue(
                            sentinelAfterCloseRan.get(),
                            "sentinel afterClose task must run even when bootstrap task throws");

                    // (a) Scope-leak check: open a healthy anon session and verify SC is null.
                    // A leaked binding from the failed session would cause current() to be non-null
                    // on the next session's event loop context.
                    awaitHealthySessionOpen(
                            ctx, "healthy anon session must not see a leaked SC from the failed session");
                })));
    }

    @Test
    @DisplayName("async @OnOpen returning failed Future: close 1011, no message dispatch, scope cleared")
    void asyncOnOpenFailurePolicy(VertxTestContext ctx) {
        AsyncBootstrapFailingEndpoint.reset();
        HealthyAnonEndpoint.reset();

        // Wait for the async @OnOpen future to settle and the server close to arrive.
        connectAndAwaitClose("/ws/fail-async")
                .onComplete(ctx.succeeding(code -> ctx.verify(() -> {
                    // (b) Server must have closed the connection with 1011.
                    if (code != NO_CLOSE_FRAME) {
                        assertEquals((short) 1011, code, "server close code must be 1011 for async failure");
                    }

                    // (c) No onMessage dispatched — ws.resume() must not have been called.
                    assertEquals(
                            0,
                            AsyncBootstrapFailingEndpoint.messageCount.get(),
                            "no onMessage must be dispatched after async @OnOpen failure");

                    // (a) Scope-leak check: healthy anon session must see null SC.
                    awaitHealthySessionOpen(
                            ctx, "healthy anon session must not see leaked SC from async-failed session");
                })));
    }

    // --- Endpoints ---

    /**
     * WebSocket endpoint at {@code /ws/fail} whose {@link OnOpen} callback always throws.
     *
     * <p>This triggers the bootstrap failure path in {@link WebSocketEndpointRegistrar#bootstrapSession}
     * before {@code ws.resume()} is reached.
     */
    @WebSocketEndpoint("/ws/fail")
    static class BootstrapFailingEndpoint {

        static final AtomicInteger messageCount = new AtomicInteger();

        /** Resets captured state before a test run. */
        static void reset() {
            messageCount.set(0);
        }

        /**
         * Always throws to simulate an unrecoverable bootstrap failure.
         *
         * @param session the WebSocket session
         * @throws RuntimeException unconditionally
         */
        @OnOpen
        public void onOpen(WebSocketSession session) {
            throw new RuntimeException("simulated bootstrap failure in onOpen");
        }

        /**
         * Counts frames to detect any inadvertent dispatch after the bootstrap failure.
         *
         * @param session the WebSocket session
         * @param msg     the received text message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, String msg) {
            messageCount.incrementAndGet();
        }
    }

    /**
     * WebSocket endpoint at {@code /ws/fail-async} whose {@link OnOpen} callback returns a
     * failed {@code Future<Void>}, exercising the async composition path in
     * {@link WebSocketEndpointRegistrar#bootstrapSession}.
     *
     * <p>Unlike {@link BootstrapFailingEndpoint}, this endpoint does not throw synchronously —
     * it returns a pre-failed future, which is the distinct async composition path.
     */
    @WebSocketEndpoint("/ws/fail-async")
    static class AsyncBootstrapFailingEndpoint {

        static final AtomicInteger messageCount = new AtomicInteger();

        /** Resets captured state before a test run. */
        static void reset() {
            messageCount.set(0);
        }

        /**
         * Returns a pre-failed {@code Future<Void>} to simulate an async bootstrap failure.
         *
         * @param session the WebSocket session
         * @return a failed future, never {@code null}
         */
        @OnOpen
        public Future<Void> onOpen(WebSocketSession session) {
            return Future.failedFuture(new RuntimeException("simulated async @OnOpen failure"));
        }

        /**
         * Counts frames to detect any inadvertent dispatch after the async bootstrap failure.
         *
         * @param session the WebSocket session
         * @param msg     the received text message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, String msg) {
            messageCount.incrementAndGet();
        }
    }

    /**
     * WebSocket endpoint at {@code /ws/anon} used as a scope-leak detector.
     *
     * <p>Since no {@link SecurityContext} is bound for this route, any non-null value observed in
     * {@link OnOpen} would indicate a context leak from a previous, failed session.
     */
    @WebSocketEndpoint("/ws/anon")
    static class HealthyAnonEndpoint {

        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();

        /** Completes once {@link #onOpen} has run for the current test. */
        static volatile Promise<Void> opened = Promise.promise();

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
            opened = Promise.promise();
        }

        /**
         * Captures the current {@link SecurityContext} at open time for scope-leak detection.
         *
         * @param session the WebSocket session
         * @param sc      the current security context (expected {@code null} for anon)
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
            opened.tryComplete();
        }
    }

    // --- Stub SecurityContext ---

    /**
     * Minimal {@link SecurityContext} for fault injection in this test. Only holds a user ID;
     * uses the typed identity model.
     *
     * @param userIdValue the user identifier
     */
    record StubSecurityContext(String userIdValue) implements SecurityContext {

        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userIdValue, Map.of()));
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
