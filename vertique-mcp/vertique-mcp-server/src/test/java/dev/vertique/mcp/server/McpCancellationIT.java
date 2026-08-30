// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
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
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T013 TP-001 — real port-0 contract matrix for cancellation and write-phase settlement.
 *
 * <p>A real server, bound and connected on the literal {@code 127.0.0.1} (never {@code listen(0)},
 * never {@code "localhost"} — see {@code docs/standards/testing.md} § Dynamic Port Allocation),
 * publishes one cooperative long-running tool that observes {@link McpCancellationSignal#cancelled()}
 * and one uncooperative tool that ignores it entirely. Each row triggers exactly one transport event
 * from a raw {@link Socket} — a client disconnects mid-invocation, resets the stream mid-write, or
 * closes normally after the terminal message — and drains the terminal/completion events a registered
 * {@link McpRequestLifecycleObserver} records.
 *
 * <p>This is the real-transport contract proof; {@link McpWritePhaseSettlementTest} is the
 * deterministic claim the write-failure row here corroborates on a live socket.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpCancellationIT {

    private static final String DISCONNECT_ROW = "shouldSignalCancellationOnDisconnectAndSuppressTheLateResult";
    private static final String RESET_ROW = "shouldSettleOnceOnStreamReset";
    private static final String WRITE_FAILURE_ROW = "shouldRecordTheActualCommitStateOnWriteFailure";
    private static final String LATE_HANDLER_RESULT_ROW = "shouldSuppressASecondCompletionAfterALateHandlerResult";

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    private static final String COOPERATIVE_TOOL = "call.cooperativeLongRunning";
    private static final String UNCOOPERATIVE_TOOL = "call.uncooperative";

    /** A body large enough that a single reset just after the first byte can still land mid-write. */
    private static final int WRITE_FAILURE_RESULT_CHARS = 1_000_000;

    /** Shrunk socket buffer size (kernel hint, not a guarantee) used only by WRITE_FAILURE_ROW. */
    private static final int SMALL_SOCKET_BUFFER_BYTES = 8_192;

    /** Bounded wait for every async signal; the class-level {@code @Timeout} is the backstop. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** Grace period after the primary settlement, giving an unfixed double-settlement time to arrive. */
    private static final long DOUBLE_SETTLE_GRACE_MS = 300;

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;

    private static Stream<String> t013ContractRows() {
        return Stream.of(DISCONNECT_ROW, RESET_ROW, WRITE_FAILURE_ROW, LATE_HANDLER_RESULT_ROW);
    }

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure != null) {
                closed.completeExceptionally(failure);
            } else {
                closed.complete(null);
            }
        }));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t013ContractRows")
    @DisplayName("T013 cancellation matrix: exactly-once terminal-before-completion, observed cancellation, "
            + "and suppressed late results across disconnect, reset, write failure, and late handler results")
    void shouldEnforceT013ContractMatrix(String row) throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();

        switch (row) {
            case DISCONNECT_ROW -> {
                // Given: the cooperative tool's invocation is reached, then the client disconnects
                // (a plain, orderly FIN close, no SO_LINGER) before any response byte is written.
                try (Socket socket = rawSocket()) {
                    sendToolCallRequest(socket, COOPERATIVE_TOOL, 1);
                    assertThat(fixture.cooperativeTool().awaitInvoked(ASYNC_TIMEOUT_SECONDS))
                            .as("the invocation must be reached before the disconnect")
                            .isTrue();
                    socket.close();
                }

                // Then: exactly one terminal precedes exactly one completion, uncommitted (no write
                // ever started), and the cooperative tool genuinely observed the cancellation signal.
                // Empirically (verified with both a raw Socket close and Vert.x's own HttpClient
                // connection close), a connection that closes before any response byte is written
                // always reaches HttpServerResponse#exceptionHandler in this Vert.x version, never
                // closeHandler — so the coordinator classifies it RESET, not DISCONNECTED, regardless of
                // how orderly the client's own close was. RESET_ROW below reaches the identical
                // classification through a hard RST; this row's distinguishing claims are the cooperative
                // tool's cancellation observation and the suppressed late result, not the specific
                // transport-outcome enum (docs/standards/testing.md "match outcomes, not exact types").
                assertThat(fixture.observer().awaitSettlement(ASYNC_TIMEOUT_SECONDS))
                        .isTrue();
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();
                McpRequestCompletedEvent completed = fixture.observer().lastCompleted();
                assertThat(completed.transportOutcome())
                        .as("a pre-response disconnect must never be recorded as a successful write")
                        .isIn(McpTransportOutcome.DISCONNECTED, McpTransportOutcome.RESET);
                assertThat(completed.responseCommitted())
                        .as("a disconnect before any write must record an uncommitted response")
                        .isFalse();
                assertThat(fixture.cooperativeTool().awaitCancellationObserved(ASYNC_TIMEOUT_SECONDS))
                        .as("DECISIVE: the cooperative tool must genuinely observe cancellation, not merely "
                                + "have counts that happen to look right")
                        .isTrue();

                // When: the tool's late result — proven to genuinely reach the framework's own
                // completion handler, since our continuation and the framework's are both registered on
                // this exact same future — arrives after settlement already won.
                CompletableFuture<Void> lateResultReachedFramework = new CompletableFuture<>();
                fixture.cooperativeTool()
                        .completeLateWith(McpToolResult.text("late-but-cooperative"), lateResultReachedFramework);
                assertThat(lateResultReachedFramework.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the late result must actually have reached the framework's own completion handler "
                                + "for the suppression below to be decisive, not vacuous")
                        .isNull();

                // Then (DECISIVE — suppression, not vacuous silence): give any regressed double-settlement
                // time to arrive, then assert the counts never moved past one.
                Thread.sleep(DOUBLE_SETTLE_GRACE_MS);
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();
            }
            case RESET_ROW -> {
                // Given: the uncooperative tool's invocation is reached, then the client resets the
                // stream (hard RST, SO_LINGER 0) before any response byte is written.
                try (Socket socket = rawSocket()) {
                    sendToolCallRequest(socket, UNCOOPERATIVE_TOOL, 2);
                    assertThat(fixture.uncooperativeTool().awaitInvoked(ASYNC_TIMEOUT_SECONDS))
                            .as("the invocation must be reached before the reset")
                            .isTrue();
                    socket.setSoLinger(true, 0);
                }

                // Then: exactly one terminal precedes exactly one completion, uncommitted, classified RESET.
                assertThat(fixture.observer().awaitSettlement(ASYNC_TIMEOUT_SECONDS))
                        .isTrue();
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();
                McpRequestCompletedEvent completed = fixture.observer().lastCompleted();
                assertThat(completed.transportOutcome()).isEqualTo(McpTransportOutcome.RESET);
                assertThat(completed.responseCommitted())
                        .as("a reset before any write must record an uncommitted response")
                        .isFalse();

                // Then (DECISIVE — settles only once): the tool later resolving anyway must not produce
                // a second settlement.
                fixture.uncooperativeTool().completeLateWith(McpToolResult.text("late"), null);
                Thread.sleep(DOUBLE_SETTLE_GRACE_MS);
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();
            }
            case WRITE_FAILURE_ROW -> {
                // Given: the uncooperative tool resolves immediately with a body large enough — combined
                // with a shrunk client receive buffer, mirroring McpStalledWriteIT's kernel-hint
                // technique — that a reset issued right after the first observed byte has a real chance
                // of landing while the terminal write is still in flight. Real-transport supporting
                // evidence, not the determinism claim (that is McpWritePhaseSettlementTest, which owns
                // the write future directly and needs no socket at all).
                fixture.uncooperativeTool()
                        .completeLateWith(McpToolResult.text("x".repeat(WRITE_FAILURE_RESULT_CHARS)), null);
                try (Socket socket = rawSocketWithSmallReceiveBuffer()) {
                    sendToolCallRequest(socket, UNCOOPERATIVE_TOOL, 3);
                    assertThat(fixture.uncooperativeTool().awaitInvoked(ASYNC_TIMEOUT_SECONDS))
                            .as("the invocation must be reached before the response can start")
                            .isTrue();
                    int firstByte = readFirstResponseBodyByte(socket);
                    assertThat(firstByte)
                            .as("the response must have started streaming before the reset")
                            .isGreaterThanOrEqualTo(0);
                    socket.setSoLinger(true, 0);
                }

                // Then: exactly one terminal precedes exactly one completion; the outcome is whichever
                // non-WRITTEN classification the real race produced (a failed write settling directly, or
                // a reset recovering a stalled write — both are legitimate per-platform outcomes of the
                // identical underlying event, per docs/standards/testing.md "match outcomes, not exact
                // types"), and the recorded responseCommitted matches the real (committed) state.
                assertThat(fixture.observer().awaitSettlement(ASYNC_TIMEOUT_SECONDS))
                        .isTrue();
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();
                McpRequestCompletedEvent completed = fixture.observer().lastCompleted();
                assertThat(completed.transportOutcome())
                        .as("a reset while the terminal write is in flight must never be recorded as a "
                                + "successful write")
                        .isIn(McpTransportOutcome.WRITE_FAILED, McpTransportOutcome.RESET);
                assertThat(completed.responseCommitted())
                        .as("the response head was already sent, so the real commit state is true")
                        .isTrue();
            }
            case LATE_HANDLER_RESULT_ROW -> {
                // Given: the uncooperative tool's invocation is reached, then the client disconnects
                // before any response byte is written — isolates suppression alone, on a tool that never
                // looks at cancellation, distinct from the DISCONNECT_ROW's combined cancellation-observed
                // claim.
                try (Socket socket = rawSocket()) {
                    sendToolCallRequest(socket, UNCOOPERATIVE_TOOL, 4);
                    assertThat(fixture.uncooperativeTool().awaitInvoked(ASYNC_TIMEOUT_SECONDS))
                            .as("the invocation must be reached before the disconnect")
                            .isTrue();
                    socket.close();
                }
                assertThat(fixture.observer().awaitSettlement(ASYNC_TIMEOUT_SECONDS))
                        .isTrue();
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();

                // When: the uncooperative handler's late result genuinely reaches the framework's own
                // completion handler (same proof shape as DISCONNECT_ROW).
                CompletableFuture<Void> lateResultReachedFramework = new CompletableFuture<>();
                fixture.uncooperativeTool().completeLateWith(McpToolResult.text("late"), lateResultReachedFramework);
                assertThat(lateResultReachedFramework.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the late result must actually have reached the framework's own completion "
                                + "handler for the suppression below to be decisive")
                        .isNull();

                // Then (DECISIVE — a second completion from the late handler result is suppressed): give
                // a regressed guard time to fire, then assert the counts never moved past one.
                Thread.sleep(DOUBLE_SETTLE_GRACE_MS);
                fixture.observer().assertExactlyOneTerminalThenOneCompletion();
            }
            default -> fail("unknown T013 cancellation matrix row: " + row);
        }
    }

    // --- Raw socket helpers ---

    private Socket rawSocket() throws IOException {
        Socket socket = new Socket(LOOPBACK, fixture.port());
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
        return socket;
    }

    /**
     * A raw socket whose receive buffer is shrunk to {@value #SMALL_SOCKET_BUFFER_BYTES} bytes
     * <em>before</em> connecting — the only point Java honors the hint — mirroring {@code
     * McpStalledWriteIT}'s kernel-hint technique so WRITE_FAILURE_ROW's larger body has a real chance
     * of still being in flight when the reset lands. A kernel hint, not a guarantee (WRITE_FAILURE_ROW
     * is real-transport supporting evidence, not the determinism claim).
     */
    private Socket rawSocketWithSmallReceiveBuffer() throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(SMALL_SOCKET_BUFFER_BYTES);
        socket.connect(new java.net.InetSocketAddress(LOOPBACK, fixture.port()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
        return socket;
    }

    private static void sendToolCallRequest(Socket socket, String toolName, Object id) throws IOException {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("name", toolName);
        byte[] body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer()
                .getBytes();
        String head = "POST " + REQUEST_PATH + " HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + "\r\n"
                + "Content-Type: application/json\r\n"
                + "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                + "Mcp-Method: tools/call\r\n"
                + "Mcp-Name: " + toolName + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    /**
     * Reads the response head from the raw socket, asserts it is a 200, and returns the first byte of
     * the body framing that follows it (proving the response is committed and streaming). Mirrors
     * {@code StreamingWireFailureIT}'s identical helper.
     */
    private static int readFirstResponseBodyByte(Socket socket) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < terminator.length) {
            int value = socket.getInputStream().read();
            if (value < 0) {
                throw new IOException("response ended before the header terminator");
            }
            headers.write(value);
            matched = value == terminator[matched] ? matched + 1 : (value == terminator[0] ? 1 : 0);
            if (headers.size() > 16 * 1024) {
                throw new IOException("response headers exceeded the test bound");
            }
        }
        String headerText = headers.toString(StandardCharsets.US_ASCII);
        assertThat(headerText).as("the tool call must start a 200 response").startsWith("HTTP/1.1 200");
        return socket.getInputStream().read();
    }

    // --- Fixture ---

    /**
     * Builds and starts one real MCP mount with one cooperative and one uncooperative
     * {@code PERMIT_ALL} zero-argument tool, an anonymous-only identity pipeline (no authentication
     * scheme — cancellation is orthogonal to authorization), and the single {@link RecordingObserver}
     * registered as this request's {@link McpRequestLifecycleObserver}.
     */
    private static final class Fixture {

        private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        private final HttpServer server;
        private final int port;
        private final CancellationAwareToolInvoker cooperativeTool;
        private final CancellationAwareToolInvoker uncooperativeTool;
        private final RecordingObserver observer;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();

            this.cooperativeTool = new CancellationAwareToolInvoker(descriptor(COOPERATIVE_TOOL), true);
            this.uncooperativeTool = new CancellationAwareToolInvoker(descriptor(UNCOOPERATIVE_TOOL), false);
            McpToolRegistry registry = McpToolRegistry.build(Set.of(cooperativeTool, uncooperativeTool));
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
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();

            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(observer),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            // A shrunk send buffer (kernel hint) gives WRITE_FAILURE_ROW's larger body a real chance of
            // still being in flight when that row's reset lands; harmless for every other row's tiny
            // responses.
            this.server = await(vertx.createHttpServer(
                            new io.vertx.core.http.HttpServerOptions().setSendBufferSize(SMALL_SOCKET_BUFFER_BYTES))
                    .requestHandler(router)
                    .listen(0, LOOPBACK));
            this.port = server.actualPort();
        }

        static Fixture start(Vertx vertx) throws Exception {
            return new Fixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        CancellationAwareToolInvoker cooperativeTool() {
            return cooperativeTool;
        }

        CancellationAwareToolInvoker uncooperativeTool() {
            return uncooperativeTool;
        }

        RecordingObserver observer() {
            return observer;
        }

        private static McpToolDescriptor descriptor(String name) {
            return new McpToolDescriptor(
                    name,
                    null,
                    "T013 fixture tool " + name + ".",
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

    /**
     * A zero-argument {@link McpToolInvoker} whose invocation is held open on a test-owned gate until
     * {@link #completeLateWith} is called, optionally observing {@link McpCancellationSignal#cancelled()}.
     *
     * <p>{@link #completeLateWith} composes its own completion signal onto the exact same {@link
     * Future} the framework's {@code invoke()} caller observes — Vert.x invokes every handler
     * registered on one future, in registration order, when it settles — so a caller awaiting that
     * signal has proof the value genuinely reached the framework's own completion handler, not merely
     * that this test's own bookkeeping ran.
     */
    private static final class CancellationAwareToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final boolean observesCancellation;
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();
        private final CompletableFuture<Void> cancellationObserved = new CompletableFuture<>();
        private final Promise<McpToolResult<?>> resultGate = Promise.promise();

        CancellationAwareToolInvoker(McpToolDescriptor descriptor, boolean observesCancellation) {
            this.descriptor = descriptor;
            this.observesCancellation = observesCancellation;
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
                    invocationCount.incrementAndGet();
                    if (observesCancellation) {
                        cancellation.cancelled().onSuccess(v -> cancellationObserved.complete(null));
                    }
                    invoked.complete(null);
                    return resultGate.future();
                }
            };
        }

        boolean awaitInvoked(long timeoutSeconds) throws Exception {
            return await(invoked, timeoutSeconds);
        }

        boolean awaitCancellationObserved(long timeoutSeconds) throws Exception {
            return await(cancellationObserved, timeoutSeconds);
        }

        /**
         * Completes this invocation's gate, optionally reporting (via {@code reachedFramework}, when
         * non-null) once the framework's own completion handler — registered on this same future after
         * this method's own continuation — has also run.
         */
        void completeLateWith(McpToolResult<?> result, CompletableFuture<Void> reachedFramework) {
            if (reachedFramework != null) {
                resultGate.future().onSuccess(v -> reachedFramework.complete(null));
            }
            resultGate.complete(result);
        }

        int invocationCount() {
            return invocationCount.get();
        }

        private static boolean await(CompletableFuture<Void> future, long timeoutSeconds) throws Exception {
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
                return true;
            } catch (TimeoutException timedOut) {
                return false;
            }
        }
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

    /** Resolves the canonical anonymous identity from empty evidence; no other credential is ever sent. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
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
