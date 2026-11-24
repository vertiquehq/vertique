// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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
 * Verifies that a failing {@link OnOpen} (a thrown exception or a failed future) is dispatched to
 * {@link OnError} before the socket is closed with {@code 1011}, that a failure of {@link OnError}
 * itself is not dispatched again, and that an endpoint without {@link OnError} still closes with
 * {@code 1011}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketOnOpenFailureDispatchIT {

    private static HttpServer server;
    private static WebSocketClient wsClient;
    private static int port;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();
        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());
        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(),
                null,
                null,
                new HolderBackedSecurityRuntime((sc, secure) -> null),
                Set.of(),
                null,
                null,
                null,
                null,
                null);
        registrar.registerAll(
                Set.of(new ThrowingOpen(), new FailedFutureOpen(), new NoErrorHandler(), new ThrowingOpenAndError()),
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

    /** Connects, waits for the server's 1011 close, then runs {@code verify} on the test thread. */
    private void expectCloseWith1011(String path, VertxTestContext ctx, Runnable verify) {
        wsClient.connect(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setURI(path))
                .onComplete(ctx.succeeding(ws -> ws.closeHandler(v -> ctx.verify(() -> {
                    assertEquals(Short.valueOf((short) 1011), ws.closeStatusCode());
                    verify.run();
                    ctx.completeNow();
                }))));
    }

    @Test
    @DisplayName("a thrown @OnOpen failure reaches @OnError before the socket is closed with 1011")
    void thrownOnOpenFailureReachesOnError(VertxTestContext ctx) {
        ThrowingOpen.error.set(null);
        expectCloseWith1011("/ws/open-throws", ctx, () -> {
            assertNotNull(ThrowingOpen.error.get(), "@OnError must have run before the close reached the client");
            assertEquals("simulated @OnOpen throw", ThrowingOpen.error.get().getMessage());
        });
    }

    @Test
    @DisplayName("a failed @OnOpen future reaches @OnError before the socket is closed with 1011")
    void failedOnOpenFutureReachesOnError(VertxTestContext ctx) {
        FailedFutureOpen.error.set(null);
        expectCloseWith1011("/ws/open-fails", ctx, () -> {
            assertNotNull(FailedFutureOpen.error.get(), "@OnError must have run before the close reached the client");
            assertEquals(
                    "simulated @OnOpen failed future",
                    FailedFutureOpen.error.get().getMessage());
        });
    }

    @Test
    @DisplayName("without @OnError a failing @OnOpen still closes with 1011")
    void noOnErrorStillCloses(VertxTestContext ctx) {
        expectCloseWith1011("/ws/open-no-error", ctx, () -> {});
    }

    @Test
    @DisplayName("a failing @OnError is not dispatched again and the socket still closes with 1011")
    void failingOnErrorIsNotRedispatched(VertxTestContext ctx) {
        ThrowingOpenAndError.errorCalls.set(0);
        expectCloseWith1011(
                "/ws/open-and-error-throw", ctx, () -> assertEquals(1, ThrowingOpenAndError.errorCalls.get()));
    }

    @WebSocketEndpoint("/ws/open-throws")
    static class ThrowingOpen {
        static final AtomicReference<Throwable> error = new AtomicReference<>();

        @OnOpen
        public void onOpen(WebSocketSession session) {
            throw new RuntimeException("simulated @OnOpen throw");
        }

        @OnError
        public void onError(WebSocketSession session, Throwable cause) {
            error.set(cause);
        }
    }

    @WebSocketEndpoint("/ws/open-fails")
    static class FailedFutureOpen {
        static final AtomicReference<Throwable> error = new AtomicReference<>();

        @OnOpen
        public Future<Void> onOpen(WebSocketSession session) {
            return Future.failedFuture(new RuntimeException("simulated @OnOpen failed future"));
        }

        @OnError
        public void onError(WebSocketSession session, Throwable cause) {
            error.set(cause);
        }
    }

    @WebSocketEndpoint("/ws/open-no-error")
    static class NoErrorHandler {
        @OnOpen
        public void onOpen(WebSocketSession session) {
            throw new RuntimeException("simulated @OnOpen throw without @OnError");
        }
    }

    @WebSocketEndpoint("/ws/open-and-error-throw")
    static class ThrowingOpenAndError {
        static final AtomicInteger errorCalls = new AtomicInteger();

        @OnOpen
        public void onOpen(WebSocketSession session) {
            throw new RuntimeException("simulated @OnOpen throw");
        }

        @OnError
        public void onError(WebSocketSession session, Throwable cause) {
            errorCalls.incrementAndGet();
            throw new RuntimeException("simulated @OnError failure");
        }
    }
}
