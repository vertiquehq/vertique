// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test pinning the {@code InputLocation} provenance that
 * {@link WebSocketEndpointRegistrar} reports to the {@code InputObjectProcessor} for each
 * processed value:
 *
 * <ul>
 *   <li><b>TEXT message</b> ({@code String}-typed {@code @OnMessage} payload) — processed as
 *       {@link InputLocation#PAYLOAD} (message provenance, not an HTTP body).</li>
 *   <li><b>Decoded object message</b> (DTO-typed {@code @OnMessage} payload, two-phase
 *       intermediate processing) — processed as {@link InputLocation#PAYLOAD}.</li>
 *   <li><b>Path parameter</b> ({@code @PathParam} on a lifecycle method) — processed as
 *       {@link InputLocation#PATH}.</li>
 * </ul>
 *
 * <p>A capturing test-double processor records every {@code (value, targetType, location)}
 * invocation and returns the value unchanged, so the assertions observe exactly what the
 * registrar reported without altering message flow.
 *
 * <p>A second endpoint is mounted on a registrar carrying the <em>real</em> engine, pinning the
 * wire &rarr; Java name projection the registrar must supply for object message bodies: a
 * {@code @JsonProperty}-renamed field carrying a declared {@code @Sanitize} chain is keyed on the
 * wire by its renamed name, while the engine keys its metadata on the Java property name. Without
 * a projection the declared chain silently never runs.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketInputProcessingIT {

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;
    private static final CapturingProcessor processor = new CapturingProcessor();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        Router router = Router.router(vertx);
        // Root lifecycle middleware — required by WebSocketEndpointRegistrar
        router.route("/*").handler(new RequestContextLifecycle());

        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, null, Set.of(), null, processor, null, null, null);
        registrar.registerAll(Set.of(new TextEndpoint(), new DtoEndpoint()), router);

        // A second registrar carrying the real engine: the renamed-field endpoint must observe the
        // declared chain actually running, which only the wire -> Java projection makes possible.
        InputObjectProcessor engine = InputObjectProcessor.createDefault(
                type -> {
                    throw new AssertionError("no canonicalizer is declared by this fixture: " + type);
                },
                type -> new UppercasingSanitizer());
        WebSocketEndpointRegistrar sanitizingRegistrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, null, Set.of(), null, engine, null, null, null);
        sanitizingRegistrar.registerAll(Set.of(new RenamedEndpoint()), router);

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

    private static WebSocket connect(String path) throws Exception {
        return wsClient.connect(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setURI(path))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    // --- Tests ---

    @Test
    @DisplayName("path parameter values are processed with InputLocation.PATH")
    void pathParamProcessedWithPathLocation() throws Exception {
        processor.captures.clear();
        TextEndpoint.reset();

        WebSocket ws = connect("/ws/proc/lobby");
        try {
            assertTrue(TextEndpoint.openLatch.await(10, TimeUnit.SECONDS), "@OnOpen must be invoked");
        } finally {
            ws.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        Capture pathCapture = findCapture("lobby");
        assertEquals(
                InputLocation.PATH,
                pathCapture.location(),
                "a @PathParam value must be processed with InputLocation.PATH");
    }

    @Test
    @DisplayName("TEXT message values are processed with InputLocation.PAYLOAD")
    void textMessageProcessedWithPayloadLocation() throws Exception {
        processor.captures.clear();
        TextEndpoint.reset();

        WebSocket ws = connect("/ws/proc/lobby");
        try {
            assertTrue(TextEndpoint.openLatch.await(10, TimeUnit.SECONDS), "@OnOpen must be invoked");
            ws.writeTextMessage("hello");
            assertTrue(TextEndpoint.messageLatch.await(10, TimeUnit.SECONDS), "@OnMessage must be invoked");
        } finally {
            ws.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        Capture messageCapture = findCapture("hello");
        assertEquals(
                InputLocation.PAYLOAD,
                messageCapture.location(),
                "a TEXT @OnMessage value must be processed with InputLocation.PAYLOAD");
    }

    @Test
    @DisplayName("decoded object message values are processed with InputLocation.PAYLOAD")
    void decodedObjectMessageProcessedWithPayloadLocation() throws Exception {
        processor.captures.clear();
        DtoEndpoint.reset();

        WebSocket ws = connect("/ws/proc-dto");
        try {
            ws.writeTextMessage("{\"text\":\"hi\"}");
            assertTrue(DtoEndpoint.messageLatch.await(10, TimeUnit.SECONDS), "@OnMessage must be invoked");
        } finally {
            ws.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        Capture dtoCapture = processor.captures.stream()
                .filter(c -> c.targetType() == ChatMessage.class)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no processor invocation captured for ChatMessage"));
        assertEquals(
                InputLocation.PAYLOAD,
                dtoCapture.location(),
                "a decoded object @OnMessage value must be processed with InputLocation.PAYLOAD");
    }

    @Test
    @DisplayName("a @JsonProperty-renamed message-body field is sanitized by its declared chain")
    void shouldSanitizeRenamedFieldsOnMessageBodies() throws Exception {
        RenamedEndpoint.reset();

        WebSocket ws = connect("/ws/proc-renamed");
        try {
            ws.writeTextMessage("{\"user_name\":\"ada\",\"city\":\"paris\"}");
            assertTrue(RenamedEndpoint.messageLatch.await(10, TimeUnit.SECONDS), "@OnMessage must be invoked");
        } finally {
            ws.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        RenamedMessage received = RenamedEndpoint.received.get();
        assertNotNull(received, "the renamed message must materialize");
        assertEquals(
                "ADA",
                received.userName(),
                "the @Sanitize declared on the Java property must run on its renamed wire key");
        assertEquals("paris", received.city(), "an ungoverned field is left untouched");
    }

    private Capture findCapture(String value) {
        return processor.captures.stream()
                .filter(c -> value.equals(c.value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no processor invocation captured for value '" + value + "'"));
    }

    // --- Test doubles ---

    /** One recorded processor invocation. */
    record Capture(Object value, Type targetType, InputLocation location) {}

    /**
     * Capturing {@code InputObjectProcessor} test double: records every invocation's value,
     * target type, and {@link InputLocation}, and returns the value unchanged.
     */
    static class CapturingProcessor implements InputObjectProcessor {

        final List<Capture> captures = new CopyOnWriteArrayList<>();

        @Override
        public Object processInput(
                Object input,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            captures.add(new Capture(input, targetType, location));
            return input;
        }
    }

    // --- Endpoints ---

    /** DTO message type for the decoded-object processing path. */
    record ChatMessage(String text) {}

    /** Endpoint with a path parameter and a raw {@code String} message payload. */
    @WebSocketEndpoint("/ws/proc/{room}")
    static class TextEndpoint {

        static CountDownLatch openLatch = new CountDownLatch(1);
        static CountDownLatch messageLatch = new CountDownLatch(1);

        /** Resets the latches before a test run. */
        static void reset() {
            openLatch = new CountDownLatch(1);
            messageLatch = new CountDownLatch(1);
        }

        /**
         * Receives the path parameter so the registrar processes it via the processor.
         *
         * @param session the WebSocket session
         * @param room    the room path parameter
         */
        @OnOpen
        public void onOpen(WebSocketSession session, @PathParam("room") String room) {
            openLatch.countDown();
        }

        /**
         * Receives the raw text message so the registrar processes it via the processor.
         *
         * @param session the WebSocket session
         * @param msg     the received text message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, String msg) {
            messageLatch.countDown();
        }
    }

    /** Sanitizer whose effect on a governed value is unmistakable in an assertion. */
    public static final class UppercasingSanitizer implements Sanitizer {

        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : value.toUpperCase(Locale.ROOT);
        }
    }

    /** Message DTO whose governed field is published on the wire under a different name. */
    public record RenamedMessage(
            @JsonProperty("user_name") @Sanitize(UppercasingSanitizer.class)
            String userName,

            String city) {}

    /** Endpoint whose message body carries a renamed, policy-declaring field. */
    @WebSocketEndpoint("/ws/proc-renamed")
    static class RenamedEndpoint {

        static final AtomicReference<RenamedMessage> received = new AtomicReference<>();
        static CountDownLatch messageLatch = new CountDownLatch(1);

        /** Resets the captured message and latch before a test run. */
        static void reset() {
            received.set(null);
            messageLatch = new CountDownLatch(1);
        }

        /**
         * Captures the materialized message so the test can observe whether the declared chain ran.
         *
         * @param session the WebSocket session
         * @param msg     the decoded message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, RenamedMessage msg) {
            received.set(msg);
            messageLatch.countDown();
        }
    }

    /** Endpoint with a DTO message payload exercising the two-phase decode path. */
    @WebSocketEndpoint("/ws/proc-dto")
    static class DtoEndpoint {

        static CountDownLatch messageLatch = new CountDownLatch(1);

        /** Resets the latch before a test run. */
        static void reset() {
            messageLatch = new CountDownLatch(1);
        }

        /**
         * Receives the decoded DTO message.
         *
         * @param session the WebSocket session
         * @param msg     the decoded chat message
         */
        @OnMessage
        public void onMessage(WebSocketSession session, ChatMessage msg) {
            messageLatch.countDown();
        }
    }
}
