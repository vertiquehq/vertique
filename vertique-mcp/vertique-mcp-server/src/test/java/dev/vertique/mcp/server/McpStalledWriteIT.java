// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T013 TP-003 — real-transport supporting evidence for the stalled-write settlement race.
 *
 * <p>A real port-0 server bound to the literal {@code 127.0.0.1}, with a client pinned to the same
 * literal, whose peer stops reading after sending the request. Socket buffer sizes are a kernel hint,
 * not a capacity guarantee (§ task caution): this test asserts the stall is actually established —
 * the terminal write has started (its terminal event published) but has not yet completed — before it
 * acts, and skips with a recorded reason when the platform absorbed the whole body before that could
 * be observed. Real transport nondeterminism means this is <strong>supporting evidence, not the
 * determinism claim</strong>: {@link McpWritePhaseSettlementTest} proves the identical settlement race
 * deterministically by owning the write future directly, with no socket at all.
 *
 * <p>Per the caution in {@code docs/specs/mcp-001-server-support/tasks/T013-*.md}: the response here
 * is a real {@link io.vertx.core.http.HttpServerResponse} throughout — no mock, no stubbed {@code
 * ended()} value — and {@code reset()} failing a pending {@code end()} future is never assumed;
 * whichever transport-cancellation classification the coordinator's own first-observed-wins guard
 * actually produces is accepted, never a hard-coded single enum.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpStalledWriteIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String STALL_TOOL = "call.stallTool";

    /** The fixture's socket buffer size (kernel hint, not a guarantee) on both peer sides. */
    private static final int SOCKET_BUFFER_BYTES = 8_192;

    /** The tool's encoded result size for the main run — a stall-inducing 8 MiB. */
    private static final int RESULT_CHARS = 8 * 1024 * 1024;

    /** {@code mcp.output.maxBytes} raised for this fixture only, comfortably above the 8 MiB result. */
    private static final int OUTPUT_MAX_BYTES = 16_777_216;

    /** Bounded wait for every async signal; the class-level {@code @Timeout} is the backstop. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** How long to poll for the stall to establish before concluding the platform absorbed it. */
    private static final long STALL_POLL_BUDGET_MS = 1_500;

    private static final long STALL_POLL_INTERVAL_MS = 50;

    private final Vertx vertx = Vertx.vertx();

    private McpStalledWriteITFixture fixture;
    private HttpServer server;

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ignored -> vertx.close().onComplete(result -> closed.complete(null)));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
    }

    @Test
    @DisplayName("a real stalled write, reset from the peer, settles exactly once with the real commit state")
    void shouldSettleOnceOnARealStalledNetworkWrite() throws Exception {
        fixture = McpStalledWriteITFixture.start(vertx, RESULT_CHARS);
        server = fixture.server();

        boolean stallEstablished;
        try (Socket socket = smallReceiveBufferSocket(fixture.port())) {
            sendToolCallRequest(socket, fixture.port());
            assertThat(fixture.tool().awaitInvoked(ASYNC_TIMEOUT_SECONDS))
                    .as("the invocation must be reached before the write can start")
                    .isTrue();

            // The peer stops reading entirely from this point on — not even the response head is
            // consumed — so the only evidence available of a partial flush is the coordinator's own
            // published state: the terminal fires exactly when beginWrite claims the write and calls
            // end(Buffer), so a terminal with no completion yet is direct evidence the byte write has
            // started but not finished.
            stallEstablished = awaitStallOrCompletion(fixture.observer());

            // Reset from the peer side exactly once, whether or not the stall was established — a
            // reset arriving after a completed write is a harmless, already-suppressed late signal.
            socket.setSoLinger(true, 0);
        }

        assertThat(fixture.observer().awaitSettlement(ASYNC_TIMEOUT_SECONDS)).isTrue();
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();

        Assumptions.assumeTrue(
                stallEstablished,
                "the platform absorbed the entire " + RESULT_CHARS + "-byte body into its socket buffers "
                        + "before the reset could land while the write was still pending; "
                        + "SO_RCVBUF/SO_SNDBUF are a kernel hint, not a capacity guarantee — see the T013 task's "
                        + "explicit allowance to skip this real-transport supporting-evidence proof");

        McpRequestCompletedEvent completed = fixture.observer().lastCompleted();
        assertThat(completed.transportOutcome())
                .as("a reset while the terminal write is still pending must never be recorded as a "
                        + "successful write — whichever non-WRITTEN classification the real race produced")
                .isIn(McpTransportOutcome.WRITE_FAILED, McpTransportOutcome.RESET);
        assertThat(completed.responseCommitted())
                .as("the response head was already sent before the stall, so the real commit state is true")
                .isTrue();
    }

    /**
     * Polls the observer for up to {@link #STALL_POLL_BUDGET_MS} for the pending-write signature (one
     * terminal published, no completion yet). Returns {@code true} the moment that signature is
     * observed, and {@code false} if a completion arrives first (the platform absorbed the whole body
     * before any stall could be observed).
     */
    private static boolean awaitStallOrCompletion(RecordingObserver observer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STALL_POLL_BUDGET_MS);
        while (System.nanoTime() < deadline) {
            if (observer.completionCount() > 0) {
                return false;
            }
            if (observer.terminalCount() > 0) {
                return true;
            }
            Thread.sleep(STALL_POLL_INTERVAL_MS);
        }
        return observer.terminalCount() > 0 && observer.completionCount() == 0;
    }

    // --- Raw socket helpers ---

    /**
     * A raw socket whose receive buffer is shrunk to {@link #SOCKET_BUFFER_BYTES} <em>before</em>
     * connecting — the only point Java honors the hint.
     */
    private static Socket smallReceiveBufferSocket(int port) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
        socket.connect(new InetSocketAddress(LOOPBACK, port));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
        return socket;
    }

    private static void sendToolCallRequest(Socket socket, int port) throws IOException {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("name", STALL_TOOL);
        byte[] body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer()
                .getBytes();
        String head = "POST " + REQUEST_PATH + " HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + ":" + port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    /** Records terminal and completion callbacks, their arrival order, and the last completion seen. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private volatile int terminalCount;
        private volatile int completionCount;
        private volatile McpRequestCompletedEvent lastCompleted;

        @Override
        public McpRequestObservation open(java.time.Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
            order.add("terminal");
            callbacks.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount++;
            lastCompleted = event;
            order.add("completed");
            callbacks.countDown();
        }

        boolean awaitSettlement(long timeoutSeconds) throws InterruptedException {
            return callbacks.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        int terminalCount() {
            return terminalCount;
        }

        int completionCount() {
            return completionCount;
        }

        McpRequestCompletedEvent lastCompleted() {
            return lastCompleted;
        }

        void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount)
                    .as("exactly one terminal event must be published")
                    .isOne();
            assertThat(completionCount)
                    .as("exactly one completion event must be published")
                    .isOne();
            assertThat(order)
                    .as("the terminal event must precede the completion event")
                    .containsExactly("terminal", "completed");
        }
    }

    /**
     * Builds and starts one real MCP mount with a single {@code PERMIT_ALL} zero-argument tool that
     * returns an encoded result of {@code resultChars} characters, a shrunk server send buffer, a
     * raised {@code mcp.output.maxBytes}, an anonymous-only identity pipeline, and the single {@link
     * RecordingObserver} registered as this request's {@link McpRequestLifecycleObserver}.
     */
    private static final class McpStalledWriteITFixture {

        private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        private final HttpServer server;
        private final int port;
        private final StallToolInvoker tool;
        private final RecordingObserver observer;

        private McpStalledWriteITFixture(Vertx vertx, int resultChars) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-test")
                    .serverVersion("1.0")
                    .outputMaxBytes(OUTPUT_MAX_BYTES)
                    .build();

            this.tool = new StallToolInvoker(descriptor(), resultChars);
            McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));
            this.observer = new RecordingObserver();

            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            HttpConfig httpConfig = HttpConfig.builder().build();

            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(observer),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            // A shrunk send buffer (kernel hint) gives the un-drained client a real chance of stalling
            // the write while it is still in flight.
            this.server = await(vertx.createHttpServer(new HttpServerOptions().setSendBufferSize(SOCKET_BUFFER_BYTES))
                    .requestHandler(router)
                    .listen(0, LOOPBACK));
            this.port = server.actualPort();
        }

        static McpStalledWriteITFixture start(Vertx vertx, int resultChars) throws Exception {
            return new McpStalledWriteITFixture(vertx, resultChars);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        StallToolInvoker tool() {
            return tool;
        }

        RecordingObserver observer() {
            return observer;
        }

        private static McpToolDescriptor descriptor() {
            return new McpToolDescriptor(
                    STALL_TOOL,
                    null,
                    "T013 TP-003 fixture tool.",
                    ANNOTATIONS,
                    CLOSED_OBJECT_SCHEMA,
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** A zero-argument tool that resolves immediately with an {@code resultChars}-character text result. */
    private static final class StallToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final int resultChars;
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();

        StallToolInvoker(McpToolDescriptor descriptor, int resultChars) {
            this.descriptor = descriptor;
            this.resultChars = resultChars;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invoked.complete(null);
                    return Future.succeededFuture(McpToolResult.text("x".repeat(resultChars)));
                }
            };
        }

        boolean awaitInvoked(long timeoutSeconds) throws Exception {
            try {
                invoked.get(timeoutSeconds, TimeUnit.SECONDS);
                return true;
            } catch (TimeoutException timedOut) {
                return false;
            }
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence; no credential is ever sent. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} without asserting on it. */
    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
