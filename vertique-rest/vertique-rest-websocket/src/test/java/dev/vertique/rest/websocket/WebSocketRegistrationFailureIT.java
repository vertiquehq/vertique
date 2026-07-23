// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import dev.vertique.security.channel.ChannelBinding;
import dev.vertique.security.channel.ChannelIdentityManager;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for the fail-closed behavior of channel registration during WebSocket bootstrap
 * (finding F-W5).
 *
 * <p>When a {@link WebSocketChannelAdapter} is present, {@link WebSocketEndpointRegistrar} hands the
 * session's snapshot {@link dev.vertique.core.context.ContextHolder.Scope} to the
 * {@link ChannelIdentityManager} via {@code register(...)} and transfers scope ownership to the
 * resulting {@link WebSocketChannelBinding}. If that registration fails asynchronously, the manager
 * never retains the binding, so the manager's {@code deregister} cleanup path is a no-op — and the
 * registrar has already cleared {@code session.contextScope()}, so the local close path cannot
 * release it either. Without a fail-closed handler the scope is orphaned <em>and</em> {@code @OnOpen}
 * runs with {@code ws.resume()} on an unmanaged scope lifecycle.
 *
 * <p>This test forces {@link ChannelIdentityManager#register} to return a failed future and asserts
 * the registrar fails closed:
 * <ul>
 *   <li>the WebSocket is closed (the client connection does not stay open);</li>
 *   <li>{@code @OnOpen} is NOT invoked — no frames reach user code on an unmanaged scope;</li>
 *   <li>the scope handed to the binding is released — proven by a follow-up
 *       {@link WebSocketChannelBinding#rebind} failing with {@link IllegalStateException}
 *       ("already released"), the same released-scope signal pinned by
 *       {@code WebSocketChannelBindingTest.rebindAfterReleaseFails}. Before the fix the scope is
 *       orphaned, so {@code rebind} would succeed.</li>
 * </ul>
 *
 * <p><b>Adapter boundary hardening (round-2).</b> The {@link ChannelIdentityManager#register} SPI
 * documents a non-null {@link Future} return, but a custom manager could violate that contract by
 * throwing synchronously or returning {@code null}. {@link WebSocketChannelAdapter#onOpen} must treat
 * either as a failed registration: release the binding's scope and return a failed future, so the
 * registrar's failure path closes the socket ({@code 1011}) and {@code @OnOpen} never runs. Two
 * additional tests pin this: {@link SyncThrowRegisterManager} and {@link NullReturnRegisterManager}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketRegistrationFailureIT {

    private static final SecurityContext TEST_SC = userContext("dave");

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;

    /** Captures the {@link ChannelBinding} handed to {@link ChannelIdentityManager#register}. */
    private static final AtomicReference<ChannelBinding> capturedBinding = new AtomicReference<>();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        // Bind a non-anonymous SecurityContext before the upgrade so the snapshot carries it and the
        // adapter branch (securityRuntime.current() != null) is taken in bootstrapSession. The same
        // SC-binding handler is installed on every fault-injection route.
        for (String routePath : List.of("/ws/reg-fail", "/ws/reg-sync-throw", "/ws/reg-null")) {
            router.route(routePath).handler(rc -> {
                RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
                lifecycle.onClose(ContextValues.bind(SecurityContext.class, TEST_SC));
                rc.next();
            });
        }

        // One registrar per fault-injection manager — each WebSocketChannelAdapter wraps a single
        // manager, so the three failure modes (async-failed-future / sync-throw / null-return) each
        // need their own adapter + registrar mounting their endpoint on the shared router.
        registrar(securityRuntime, new FailingRegisterManager()).registerAll(Set.of(new RegFailEndpoint()), router);
        registrar(securityRuntime, new SyncThrowRegisterManager()).registerAll(Set.of(new SyncThrowEndpoint()), router);
        registrar(securityRuntime, new NullReturnRegisterManager())
                .registerAll(Set.of(new NullReturnEndpoint()), router);

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

    /**
     * Verifies the registrar fails closed when channel registration fails: the socket is closed,
     * {@code @OnOpen} does not run, and the scope handed to the binding is released (not orphaned).
     *
     * @param vertx the Vert.x instance used for post-failure timers
     * @param ctx   the test context
     */
    @Test
    @DisplayName("channel registration failure: socket closed, @OnOpen not run, scope released (not orphaned)")
    void registrationFailureFailsClosed(Vertx vertx, VertxTestContext ctx) {
        RegFailEndpoint.reset();
        capturedBinding.set(null);

        // The handshake normally succeeds (toWebSocket() resolves) and the registration failure happens
        // afterward inside bootstrapSession, so the server closes the socket post-handshake. Use
        // onComplete (not succeeding) so a transient handshake failure — the server's fail-closed close
        // racing the 101, or an environmental handshake-404 — does not abort the test before the
        // assertions run. Either way the socket must not stay open, @OnOpen must not run, and the scope
        // handed to the binding must be released.
        wsClient.connect(new WebSocketConnectOptions()
                        .setHost("localhost")
                        .setPort(port)
                        .setURI("/ws/reg-fail"))
                .onComplete(connectResult -> {
                    AtomicReference<WebSocket> sock =
                            new AtomicReference<>(connectResult.succeeded() ? connectResult.result() : null);
                    // Let bootstrapSession run (register fails async, fail-closed teardown runs).
                    vertx.setTimer(
                            300,
                            id -> ctx.verify(() -> {
                                // 1. @OnOpen must NOT have run on the unmanaged scope.
                                assertNull(
                                        RegFailEndpoint.onOpenSc.get(),
                                        "@OnOpen must NOT run when channel registration fails");

                                // 2. If the handshake resolved a socket, it must be closed by the server's
                                //    fail-closed teardown. A connect that failed outright leaves no socket
                                //    to inspect — equally a not-open outcome.
                                WebSocket ws = sock.get();
                                if (ws != null) {
                                    assertTrue(ws.isClosed(), "the WebSocket must be closed on registration failure");
                                }

                                // 3. The scope handed to the binding must be released — a follow-up rebind
                                //    must fail with IllegalStateException ("already released"). If the scope
                                //    were orphaned (the bug), rebind would succeed. This holds regardless of
                                //    how the handshake settled: register() captures the binding either way.
                                ChannelBinding binding = capturedBinding.get();
                                if (binding == null) {
                                    closeQuietly(ws);
                                    ctx.failNow("register() was never invoked — adapter branch not taken");
                                    return;
                                }
                                binding.rebind(userContext("dave-late"))
                                        .onComplete(ar -> ctx.verify(() -> {
                                            assertTrue(
                                                    ar.failed(),
                                                    "rebind after fail-closed release must fail (scope released)");
                                            assertInstanceOf(
                                                    IllegalStateException.class,
                                                    ar.cause(),
                                                    "released-scope rebind must fail with IllegalStateException");
                                            closeQuietly(ws);
                                            ctx.completeNow();
                                        }));
                            }));
                });
    }

    private static void closeQuietly(WebSocket ws) {
        if (ws != null && !ws.isClosed()) {
            ws.close();
        }
    }

    /**
     * Builds a registrar whose channel adapter wraps the given fault-injection manager.
     *
     * @param securityRuntime the shared security runtime
     * @param manager         the manager whose {@code register} simulates a failure mode
     * @return a registrar configured with the manager's adapter and no other optional dependencies
     */
    private static WebSocketEndpointRegistrar registrar(
            HolderBackedSecurityRuntime securityRuntime, ChannelIdentityManager manager) {
        WebSocketChannelAdapter adapter = new WebSocketChannelAdapter(manager, securityRuntime);
        return new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, securityRuntime, Set.of(), null, null, adapter, null, null);
    }

    /**
     * Verifies the registrar fails closed when a custom manager's {@code register} throws
     * synchronously: the socket is closed and {@code @OnOpen} does not run.
     *
     * @param vertx the Vert.x instance used for post-failure timers
     * @param ctx   the test context
     */
    @Test
    @DisplayName("synchronous register() throw: socket closed, @OnOpen not run")
    void syncThrowRegisterFailsClosed(Vertx vertx, VertxTestContext ctx) {
        SyncThrowEndpoint.reset();
        assertFailsClosed(vertx, ctx, "/ws/reg-sync-throw", SyncThrowEndpoint.onOpenSc);
    }

    /**
     * Verifies the registrar fails closed when a custom manager's {@code register} returns
     * {@code null} (a contract violation): the socket is closed and {@code @OnOpen} does not run.
     *
     * @param vertx the Vert.x instance used for post-failure timers
     * @param ctx   the test context
     */
    @Test
    @DisplayName("null register() return: socket closed, @OnOpen not run")
    void nullReturnRegisterFailsClosed(Vertx vertx, VertxTestContext ctx) {
        NullReturnEndpoint.reset();
        assertFailsClosed(vertx, ctx, "/ws/reg-null", NullReturnEndpoint.onOpenSc);
    }

    /**
     * Connects to the given fault-injection route and asserts the registrar fails closed: the socket
     * is closed by the server and {@code @OnOpen} never recorded a {@link SecurityContext}.
     *
     * @param vertx       the Vert.x instance used for the post-failure timer
     * @param ctx         the test context
     * @param uri         the fault-injection route to connect to
     * @param onOpenProbe the endpoint's {@code @OnOpen} capture reference, expected to stay null
     */
    private void assertFailsClosed(
            Vertx vertx, VertxTestContext ctx, String uri, AtomicReference<SecurityContext> onOpenProbe) {
        wsClient.connect(new WebSocketConnectOptions()
                        .setHost("localhost")
                        .setPort(port)
                        .setURI(uri))
                .onComplete(ar -> {
                    // The handshake may succeed (toWebSocket() resolves) and then the server closes the
                    // socket post-handshake, or it may fail outright if the close races the 101 — either
                    // way the socket must not stay open and @OnOpen must not run. Use onComplete (not
                    // succeeding) so a connect failure does not abort the test before the assertions.
                    AtomicReference<WebSocket> sock = new AtomicReference<>(ar.succeeded() ? ar.result() : null);
                    vertx.setTimer(
                            300,
                            id -> ctx.verify(() -> {
                                assertNull(
                                        onOpenProbe.get(),
                                        "@OnOpen must NOT run when register() throws synchronously or returns null");
                                WebSocket ws = sock.get();
                                if (ws != null) {
                                    assertTrue(
                                            ws.isClosed(),
                                            "the WebSocket must be closed when register() throws synchronously or"
                                                    + " returns null");
                                    closeQuietly(ws);
                                }
                                ctx.completeNow();
                            }));
                });
    }

    // --- Endpoint ---

    /**
     * WebSocket endpoint whose {@code @OnOpen} records the {@link SecurityContext} it observes, so the
     * test can assert it never runs when registration fails.
     */
    @WebSocketEndpoint("/ws/reg-fail")
    static class RegFailEndpoint {

        /** {@link SecurityContext} captured in {@link OnOpen}; stays {@code null} when {@code @OnOpen} is skipped. */
        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
        }

        /**
         * Captures the resolved {@link SecurityContext} at open time.
         *
         * @param session the WebSocket session
         * @param sc      the resolved security context
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
        }
    }

    /**
     * WebSocket endpoint for the synchronous-throw register fault. Its {@code @OnOpen} records the
     * {@link SecurityContext} it observes so the test can assert it never runs.
     */
    @WebSocketEndpoint("/ws/reg-sync-throw")
    static class SyncThrowEndpoint {

        /** {@link SecurityContext} captured in {@link OnOpen}; stays {@code null} when {@code @OnOpen} is skipped. */
        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
        }

        /**
         * Captures the resolved {@link SecurityContext} at open time.
         *
         * @param session the WebSocket session
         * @param sc      the resolved security context
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
        }
    }

    /**
     * WebSocket endpoint for the null-return register fault. Its {@code @OnOpen} records the
     * {@link SecurityContext} it observes so the test can assert it never runs.
     */
    @WebSocketEndpoint("/ws/reg-null")
    static class NullReturnEndpoint {

        /** {@link SecurityContext} captured in {@link OnOpen}; stays {@code null} when {@code @OnOpen} is skipped. */
        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
        }

        /**
         * Captures the resolved {@link SecurityContext} at open time.
         *
         * @param session the WebSocket session
         * @param sc      the resolved security context
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
        }
    }

    // --- Test doubles ---

    /**
     * {@link ChannelIdentityManager} whose {@link #register} captures the binding and returns a failed
     * future, simulating an asynchronous registration failure. {@link #deregister} is a no-op succeeded
     * future, matching the real manager's behavior for a channel that was never registered.
     */
    static class FailingRegisterManager implements ChannelIdentityManager {

        @Override
        public Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding) {
            capturedBinding.set(binding);
            return Future.failedFuture(new IllegalStateException("simulated channel registration failure"));
        }

        @Override
        public Future<Void> refreshIdentity(String channelId, SecurityContext newCtx) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> closeChannel(String channelId, String reasonCode) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> deregister(String channelId, String fallbackReasonCode) {
            // The channel was never registered (register failed), so the real manager would no-op here.
            return Future.succeededFuture();
        }

        @Override
        public Optional<SecurityContext> current(String channelId) {
            return Optional.empty();
        }
    }

    /**
     * {@link ChannelIdentityManager} whose {@link #register} captures the binding and then throws
     * synchronously, simulating a custom manager that violates the non-throwing {@code register}
     * contract. The adapter boundary must treat this as a failed registration.
     */
    static class SyncThrowRegisterManager implements ChannelIdentityManager {

        @Override
        public Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding) {
            capturedBinding.set(binding);
            throw new IllegalStateException("simulated synchronous register() throw");
        }

        @Override
        public Future<Void> refreshIdentity(String channelId, SecurityContext newCtx) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> closeChannel(String channelId, String reasonCode) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> deregister(String channelId, String fallbackReasonCode) {
            return Future.succeededFuture();
        }

        @Override
        public Optional<SecurityContext> current(String channelId) {
            return Optional.empty();
        }
    }

    /**
     * {@link ChannelIdentityManager} whose {@link #register} captures the binding and returns
     * {@code null}, simulating a custom manager that violates the non-null {@code register} return
     * contract. The adapter boundary must treat this as a failed registration.
     */
    static class NullReturnRegisterManager implements ChannelIdentityManager {

        @Override
        public Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding) {
            capturedBinding.set(binding);
            return null;
        }

        @Override
        public Future<Void> refreshIdentity(String channelId, SecurityContext newCtx) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> closeChannel(String channelId, String reasonCode) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> deregister(String channelId, String fallbackReasonCode) {
            return Future.succeededFuture();
        }

        @Override
        public Optional<SecurityContext> current(String channelId) {
            return Optional.empty();
        }
    }

    /**
     * Builds a minimal non-anonymous {@link SecurityContext} for the given user id.
     *
     * @param userId the user identifier
     * @return a USER-typed security context
     */
    private static SecurityContext userContext(String userId) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = AuthorizationClaims.empty();
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return auth;
            }

            @Override
            public AuthorizationClaims authorization() {
                return claims;
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }
}
