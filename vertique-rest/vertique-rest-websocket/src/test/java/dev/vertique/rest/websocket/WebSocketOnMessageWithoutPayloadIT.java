// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Characterizes an {@link OnMessage} method that declares no message parameter: the text handler is
 * still installed, so the method runs once per text frame and receives no payload. A binary frame
 * does not reach it, because only a {@code Buffer} parameter switches an endpoint to binary
 * delivery.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketOnMessageWithoutPayloadIT {

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, null, Set.of(), null, null, null, null, null);
        registrar.registerAll(Set.of(new NoParameterEndpoint(), new SessionOnlyEndpoint()), router);

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

    private static WebSocket connect(String path) throws Exception {
        return wsClient.connect(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setURI(path))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private static void close(WebSocket ws) throws Exception {
        ws.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("@OnMessage with no parameters runs once per text frame")
    void noParameterMethodRunsOncePerTextFrame() throws Exception {
        NoParameterEndpoint.reset(2);

        WebSocket ws = connect("/ws/no-param");
        try {
            ws.writeTextMessage("first");
            ws.writeTextMessage("second");
            assertTrue(NoParameterEndpoint.latch.await(10, TimeUnit.SECONDS), "method must run for both frames");
        } finally {
            close(ws);
        }
        assertEquals(2, NoParameterEndpoint.calls.get());
    }

    @Test
    @DisplayName("@OnMessage with only the session runs once per text frame")
    void sessionOnlyMethodRunsOncePerTextFrame() throws Exception {
        SessionOnlyEndpoint.reset(2);

        WebSocket ws = connect("/ws/session-only");
        try {
            ws.writeTextMessage("first");
            ws.writeTextMessage("second");
            assertTrue(SessionOnlyEndpoint.latch.await(10, TimeUnit.SECONDS), "method must run for both frames");
        } finally {
            close(ws);
        }
        assertEquals(2, SessionOnlyEndpoint.calls.get());
    }

    @Test
    @DisplayName("@OnMessage with no parameters does not run for a binary frame")
    void noParameterMethodIgnoresBinaryFrame() throws Exception {
        NoParameterEndpoint.reset(1);

        WebSocket ws = connect("/ws/no-param");
        try {
            ws.writeBinaryMessage(Buffer.buffer(new byte[] {1, 2, 3}));
            // Dispatch is synchronous on the event loop, so the binary frame has been handled (run
            // the method or dropped) before the text frame is read. The latch alone cannot tell which
            // frame ran the method; the count is read after the close round-trip, when no further
            // dispatch can still be pending.
            ws.writeTextMessage("marker");
            assertTrue(NoParameterEndpoint.latch.await(10, TimeUnit.SECONDS), "text marker must run the method");
        } finally {
            close(ws);
        }
        assertEquals(1, NoParameterEndpoint.calls.get(), "only the text frame may invoke the method");
    }

    @WebSocketEndpoint("/ws/no-param")
    static class NoParameterEndpoint {
        static volatile CountDownLatch latch = new CountDownLatch(0);
        static final AtomicInteger calls = new AtomicInteger();

        static void reset(int expected) {
            calls.set(0);
            latch = new CountDownLatch(expected);
        }

        @OnMessage
        public void onMessage() {
            calls.incrementAndGet();
            latch.countDown();
        }
    }

    @WebSocketEndpoint("/ws/session-only")
    static class SessionOnlyEndpoint {
        static volatile CountDownLatch latch = new CountDownLatch(0);
        static final AtomicInteger calls = new AtomicInteger();

        static void reset(int expected) {
            calls.set(0);
            latch = new CountDownLatch(expected);
        }

        @OnMessage
        public void onMessage(WebSocketSession session) {
            calls.incrementAndGet();
            latch.countDown();
        }
    }
}
