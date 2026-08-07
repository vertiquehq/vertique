// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for the <em>foreign-context</em> variant of the channel-registration completion
 * race in {@link WebSocketEndpointRegistrar#bootstrapSession} (round-4 hardening of finding F-W5).
 *
 * <p>The sibling {@link WebSocketCloseDuringRegistrationIT} resolves the deferred {@code register()}
 * promise from a Vert.x timer, so the registration-completion callback runs on the WebSocket's owner
 * event loop. That does not exercise a custom asynchronous {@link ChannelIdentityManager} that
 * completes {@code register()} from a worker / foreign Vert.x context — which the
 * {@link ChannelIdentityManager#register} javadoc permits (it promises nothing about completion
 * context).
 *
 * <p><b>The runtime fact this test pins.</b> When {@code register()}'s future is completed on a
 * worker thread, the {@code onComplete} callback the registrar attached (through
 * {@link WebSocketChannelAdapter#onOpen}'s {@code .recover(...)}) runs on that <em>worker thread</em>
 * even though {@code Vertx.currentContext()} still reports the owner context. The owner-context
 * <em>object</em> is associated, but the carrier <em>thread</em> is a worker thread — so the success
 * branch executes concurrently with owner-loop tasks (e.g. the close handler), with no mutual
 * exclusion. {@code @OnOpen}, {@code ws.resume()}, and every session/scope mutation must instead run
 * on the socket-owner event-loop thread.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>{@link #foreignContextCompletionRunsOnOwnerThread}: socket stays open; registration completes
 *       from a worker thread. Asserts {@code @OnOpen} runs on a {@code vert.x-eventloop-thread-*}
 *       (the owner event loop), not a worker thread. <b>This is the red→green proof:</b> before the
 *       fix {@code @OnOpen} runs on {@code vert.x-worker-thread-*}; after the fix the registrar
 *       marshals the completion body through {@code ownerContext.runOnContext(...)} so it runs on the
 *       event-loop thread.</li>
 *   <li>{@link #closeWhilePendingForeignContextFailsClosed}: client closes before the foreign-context
 *       completion; asserts {@code @OnOpen} does not run and the late-registered binding's scope is
 *       released (not orphaned), proven via a follow-up {@link WebSocketChannelBinding#rebind}
 *       failing with {@link IllegalStateException} ("already released"). This guards the fail-closed
 *       path under foreign-context completion.</li>
 * </ol>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketCloseDuringForeignContextRegistrationIT {

    private static final SecurityContext TEST_SC = userContext("frank");

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;

    /** Captures the {@link ChannelBinding} handed to {@link ChannelIdentityManager#register}. */
    private static final AtomicReference<ChannelBinding> capturedBinding = new AtomicReference<>();

    /** The deferred registration promise the manager returns; resolved by the test from a worker thread. */
    private static final AtomicReference<Promise<Void>> deferredRegister = new AtomicReference<>();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);

        WebSocketChannelAdapter channelAdapter =
                new WebSocketChannelAdapter(new ForeignContextRegisterManager(), securityRuntime);

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

        router.route("/ws/foreign-*").handler(rc -> {
            RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(rc);
            lifecycle.onClose(ContextValues.bind(SecurityContext.class, TEST_SC));
            rc.next();
        });

        registrar.registerAll(Set.of(new ForeignOpenEndpoint(), new ForeignClosePendingEndpoint()), router);

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

    /**
     * Verifies that when a custom manager completes {@code register()} from a worker thread, the
     * registrar runs {@code @OnOpen} (and, by construction, {@code ws.resume()} and all session/scope
     * mutations) on the socket-owner event-loop thread — not on the worker thread that completed the
     * registration future.
     *
     * <p>Red (before fix): {@code @OnOpen} observes {@code vert.x-worker-thread-*}. Green (after fix):
     * {@code @OnOpen} observes {@code vert.x-eventloop-thread-*} because the completion body is
     * marshaled through {@code ownerContext.runOnContext(...)}.
     *
     * @param vertx the Vert.x instance used for the worker-thread completion and post-open timers
     * @param ctx   the test context
     */
    @Test
    @DisplayName("foreign-context register completion: @OnOpen runs on the owner event-loop thread")
    void foreignContextCompletionRunsOnOwnerThread(Vertx vertx, VertxTestContext ctx) {
        ForeignOpenEndpoint.reset();
        capturedBinding.set(null);
        deferredRegister.set(null);

        wsClient.connect(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setURI("/ws/foreign-open"))
                .onComplete(ctx.succeeding(ws -> {
                    // Handshake succeeded; bootstrapSession is running and register() is pending. Leave
                    // the socket OPEN and resolve the registration from a worker thread so the
                    // completion callback fires off the owner event loop unless the registrar marshals
                    // it back.
                    vertx.setTimer(150, t -> {
                        Promise<Void> reg = deferredRegister.get();
                        if (reg == null) {
                            ctx.failNow("register() was never invoked — adapter branch not taken");
                            return;
                        }
                        vertx.executeBlocking(() -> {
                            reg.complete();
                            return null;
                        });

                        vertx.setTimer(
                                400,
                                done -> ctx.verify(() -> {
                                    assertTrue(
                                            ForeignOpenEndpoint.onOpenRan.get(),
                                            "@OnOpen must run when the socket stays open and registration"
                                                    + " succeeds");
                                    String thread = ForeignOpenEndpoint.onOpenThread.get();
                                    assertNotNull(thread, "@OnOpen thread must have been captured");
                                    assertTrue(
                                            thread.startsWith("vert.x-eventloop-thread-"),
                                            "@OnOpen must run on the socket-owner event-loop thread, not a"
                                                    + " worker thread; actual thread was: " + thread);
                                    ws.close().onComplete(ar -> ctx.completeNow());
                                }));
                    });
                }));
    }

    /**
     * Verifies the registrar fails closed when the peer closes the socket while channel registration
     * is still pending and registration then succeeds <em>from a foreign (worker) context</em>:
     * {@code @OnOpen} does not run and the late-registered binding's scope is released (not orphaned).
     *
     * @param vertx the Vert.x instance used for post-close timers and the worker-thread completion
     * @param ctx   the test context
     */
    @Test
    @DisplayName("close during pending registration completed on a foreign context: @OnOpen not run, scope released")
    void closeWhilePendingForeignContextFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ForeignClosePendingEndpoint.reset();
        capturedBinding.set(null);
        deferredRegister.set(null);

        wsClient.connect(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setURI("/ws/foreign-close-pending"))
                .onComplete(ctx.succeeding(ws -> {
                    // The handshake succeeded; bootstrapSession is now running and register() is
                    // pending. Close the socket from the client BEFORE resolving the registration.
                    ws.close()
                            .onComplete(ctx.succeeding(closed -> vertx.setTimer(200, afterClose -> {
                                Promise<Void> reg = deferredRegister.get();
                                if (reg == null) {
                                    ctx.failNow("register() was never invoked — adapter branch not taken");
                                    return;
                                }
                                // Resolve the registration AFTER the socket is closed, FROM A WORKER
                                // THREAD (foreign context).
                                vertx.executeBlocking(() -> {
                                    reg.complete();
                                    return null;
                                });

                                // Give the completion callback time to run its fail-closed teardown
                                // (marshaled back onto the owner context).
                                vertx.setTimer(
                                        400,
                                        afterResolve -> ctx.verify(() -> {
                                            // 1. @OnOpen must NOT have run on the closed socket.
                                            assertNull(
                                                    ForeignClosePendingEndpoint.onOpenSc.get(),
                                                    "@OnOpen must NOT run when the socket closed before registration"
                                                            + " completed on a foreign context");

                                            // 2. The late-registered binding's scope must be released
                                            //    (deregistered), not orphaned. Probe via rebind.
                                            ChannelBinding binding = capturedBinding.get();
                                            if (binding == null) {
                                                ctx.failNow("register() never captured a binding");
                                                return;
                                            }
                                            binding.rebind(userContext("frank-late"))
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

    // --- Endpoints ---

    /**
     * WebSocket endpoint whose {@code @OnOpen} records the carrier thread it ran on, so the test can
     * assert the registration-completion body was marshaled onto the owner event-loop thread.
     */
    @WebSocketEndpoint("/ws/foreign-open")
    static class ForeignOpenEndpoint {

        /** Set when {@code @OnOpen} runs. */
        static final AtomicBoolean onOpenRan = new AtomicBoolean(false);

        /** The name of the thread {@code @OnOpen} ran on. */
        static final AtomicReference<String> onOpenThread = new AtomicReference<>();

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenRan.set(false);
            onOpenThread.set(null);
        }

        /**
         * Records the carrier thread at open time.
         *
         * @param session the WebSocket session
         */
        @OnOpen
        public void onOpen(WebSocketSession session) {
            onOpenRan.set(true);
            onOpenThread.set(Thread.currentThread().getName());
        }
    }

    /**
     * WebSocket endpoint whose {@code @OnOpen} records the {@link SecurityContext} it observes, so the
     * test can assert it never runs when the socket closes before registration completes.
     */
    @WebSocketEndpoint("/ws/foreign-close-pending")
    static class ForeignClosePendingEndpoint {

        /** {@link SecurityContext} captured in {@link OnOpen}; stays {@code null} when {@code @OnOpen} is skipped. */
        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();

        /** Resets captured state before a test run. */
        static void reset() {
            onOpenSc.set(null);
        }

        /**
         * Captures the resolved {@link SecurityContext} at open time. Must never run in this test.
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
     * deferred {@link Promise} the test resolves later <em>from a worker thread</em>, simulating a
     * custom asynchronous manager that completes registration on a foreign Vert.x context.
     * {@link #deregister} releases the binding's scope (mirroring the real manager once the channel is
     * registered).
     */
    static class ForeignContextRegisterManager implements ChannelIdentityManager {

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
