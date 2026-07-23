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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
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
 * Integration test for the close-while-registration-pending lifecycle gap in
 * {@link WebSocketEndpointRegistrar#bootstrapSession} (round-2 hardening of finding F-W5).
 *
 * <p>Channel registration via a {@link ChannelIdentityManager} is asynchronous. The registrar
 * installs the WebSocket close handler <em>before</em> the {@code register()} future settles. If the
 * peer closes the socket while registration is still pending, the close handler's
 * {@code releaseChannelResources} runs first and calls {@code deregister} on a channel the manager
 * has not yet retained (a no-op). When {@code register()} subsequently <em>succeeds</em>, the
 * registration-success branch must not blindly invoke {@code @OnOpen} and {@code ws.resume()} on an
 * already-closed socket, and must not leave the now-registered binding's scope orphaned.
 *
 * <p>This test uses a {@link DeferredRegisterManager} whose {@code register()} returns a deferred
 * {@link Promise} resolved later by the test. The client closes the socket before the promise is
 * resolved, then the test resolves the promise (registration succeeds) and asserts the registrar
 * fails closed:
 * <ul>
 *   <li>{@code @OnOpen} is NOT invoked — no user handler runs on a closed socket;</li>
 *   <li>the binding's scope is released (not orphaned) — proven by a follow-up
 *       {@link WebSocketChannelBinding#rebind} failing with {@link IllegalStateException}
 *       ("already released"), the same released-scope signal pinned by
 *       {@code WebSocketChannelBindingTest.rebindAfterReleaseFails}. Before the fix the success
 *       branch clears the session scope and never deregisters the late-registered binding, so its
 *       scope is orphaned and {@code rebind} would succeed.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketCloseDuringRegistrationIT {

    private static final SecurityContext TEST_SC = userContext("erin");

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;

    /** Captures the {@link ChannelBinding} handed to {@link ChannelIdentityManager#register}. */
    private static final AtomicReference<ChannelBinding> capturedBinding = new AtomicReference<>();

    /** The deferred registration promise the manager returns; resolved by the test after the close. */
    private static final AtomicReference<Promise<Void>> deferredRegister = new AtomicReference<>();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);

        WebSocketChannelAdapter channelAdapter =
                new WebSocketChannelAdapter(new DeferredRegisterManager(), securityRuntime);

        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(),
                null,
                null,
                securityRuntime,
                Set.of(),
                null,
                null,
                channelAdapter,
                null,
                null);

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        router.route("/ws/close-pending").handler(rc -> {
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            lifecycle.onClose(ContextValues.bind(SecurityContext.class, TEST_SC));
            rc.next();
        });

        registrar.registerAll(Set.of(new ClosePendingEndpoint()), router);

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
     * Verifies the registrar fails closed when the peer closes the socket while channel registration
     * is still pending and registration then succeeds: {@code @OnOpen} does not run and the
     * late-registered binding's scope is released (not orphaned).
     *
     * @param vertx the Vert.x instance used for post-close timers
     * @param ctx   the test context
     */
    @Test
    @DisplayName("close during pending registration: @OnOpen not run, scope released after late register success")
    void closeWhilePendingFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ClosePendingEndpoint.reset();
        capturedBinding.set(null);
        deferredRegister.set(null);

        wsClient.connect(new WebSocketConnectOptions()
                        .setHost("localhost")
                        .setPort(port)
                        .setURI("/ws/close-pending"))
                .onComplete(ctx.succeeding(ws -> {
                    // The handshake succeeded; bootstrapSession is now running and register() is
                    // pending (the manager returned an unresolved promise). Close the socket from the
                    // client BEFORE resolving the registration.
                    ws.close()
                            .onComplete(ctx.succeeding(closed -> vertx.setTimer(200, afterClose -> {
                                ctx.verify(() -> {
                                    Promise<Void> reg = deferredRegister.get();
                                    if (reg == null) {
                                        ctx.failNow("register() was never invoked — adapter branch not taken");
                                        return;
                                    }
                                    // Resolve the registration AFTER the socket is closed — this drives
                                    // the registration-success branch on an already-closed socket.
                                    reg.complete();
                                });

                                // Give the success branch time to run its fail-closed teardown.
                                vertx.setTimer(
                                        300,
                                        afterResolve -> ctx.verify(() -> {
                                            // 1. @OnOpen must NOT have run on the closed socket.
                                            assertNull(
                                                    ClosePendingEndpoint.onOpenSc.get(),
                                                    "@OnOpen must NOT run when the socket closed before registration"
                                                            + " completed");

                                            // 2. The late-registered binding's scope must be released
                                            //    (deregistered), not orphaned. Probe via rebind.
                                            ChannelBinding binding = capturedBinding.get();
                                            if (binding == null) {
                                                ctx.failNow("register() never captured a binding");
                                                return;
                                            }
                                            binding.rebind(userContext("erin-late"))
                                                    .onComplete(ar -> ctx.verify(() -> {
                                                        assertTrue(
                                                                ar.failed(),
                                                                "rebind after fail-closed release must fail"
                                                                        + " (scope released)");
                                                        assertInstanceOf(
                                                                IllegalStateException.class,
                                                                ar.cause(),
                                                                "released-scope rebind must fail with"
                                                                        + " IllegalStateException");
                                                        ctx.completeNow();
                                                    }));
                                        }));
                            })));
                }));
    }

    // --- Endpoint ---

    /**
     * WebSocket endpoint whose {@code @OnOpen} records the {@link SecurityContext} it observes, so the
     * test can assert it never runs when the socket closes before registration completes.
     */
    @WebSocketEndpoint("/ws/close-pending")
    static class ClosePendingEndpoint {

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
     * {@link ChannelIdentityManager} whose {@link #register} captures the binding and returns a
     * deferred {@link Promise} the test resolves later, simulating a slow asynchronous registration.
     * {@link #deregister} releases the binding's scope (mirroring the real manager once the channel is
     * registered) and records that it ran.
     */
    static class DeferredRegisterManager implements ChannelIdentityManager {

        @Override
        public Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding) {
            capturedBinding.set(binding);
            Promise<Void> promise = Promise.promise();
            deferredRegister.set(promise);
            return promise.future();
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
            ChannelBinding binding = capturedBinding.get();
            // Only release once the registration promise has been resolved — before that the real
            // manager has not retained the binding, so deregister is a no-op.
            Promise<Void> reg = deferredRegister.get();
            if (reg != null && reg.future().succeeded() && binding != null) {
                return binding.releaseResources();
            }
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
