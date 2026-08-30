// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
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
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** T032's fixed characterization of exactly-once settlement across real transport races. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpSettlementRaceCharacterizationIT {

    private static final String SEED_RESOURCE = "/mcp/characterization/disconnect-seeds.jsonl";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final int SMALL_SOCKET_BUFFER_BYTES = 8_192;
    private static final int LARGE_RESULT_CHARS = 1_000_000;
    private static final String LARGE_RESULT = "x".repeat(LARGE_RESULT_CHARS);
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    @ParameterizedTest(name = "{0}")
    @MethodSource("seeds")
    @DisplayName("T032: every seeded transport schedule settles terminal then completion exactly once")
    void shouldSettleOnceForEverySeededSchedule(Seed seed) throws Exception {
        // Given: one literal schedule and a fresh port-0 server whose tool result is test-controlled.
        try (var fixture = McpSettlementRaceCharacterizationITFixture.start()) {
            // When: the client replays the schedule's disconnect/reset timing exactly once.
            fixture.replay(seed);

            // Then: logical settlement precedes transport completion exactly once and the observed
            // transport facts retain the committed corpus classification.
            assertThat(fixture.observer().awaitSettlement()).isTrue();
            fixture.observer().assertExactlyOneTerminalThenOneCompletion();
            McpRequestCompletedEvent completed = fixture.observer().completed();
            assertThat(completed.responseCommitted()).isEqualTo(seed.expectedCommitted());
            assertThat(seed.expectedOutcomes()).contains(completed.transportOutcome());

            if (seed.lateCompletion()) {
                fixture.completeTool("late");
                fixture.awaitLateCompletionReachedFramework();
            }
            // Deterministic replacement for a fixed sleep: a marker task queued on the exact Vert.x
            // context the request ran on only resolves once every task already queued ahead of it —
            // including a stalled write's recovery redispatch and any late-completion continuation —
            // has itself run to completion. Draining that context is therefore equivalent to (and
            // strictly tighter than) waiting out a fixed grace period.
            fixture.drainRequestContext();
            fixture.observer().assertExactlyOneTerminalThenOneCompletion();
        }
    }

    private static Stream<Seed> seeds() {
        return readSeeds().stream();
    }

    private static List<Seed> readSeeds() {
        try (var input = McpSettlementRaceCharacterizationIT.class.getResourceAsStream(SEED_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing characterization resource " + SEED_RESOURCE);
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(Seed::fromJson)
                        .toList();
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read characterization resource " + SEED_RESOURCE, unreadable);
        }
    }

    private enum TransportAction {
        DISCONNECT,
        RESET
    }

    private enum Timing {
        BEFORE_WRITE,
        DURING_WRITE,
        AFTER_WRITE
    }

    private record Seed(
            String id,
            TransportAction transport,
            Timing timing,
            boolean lateCompletion,
            boolean expectedCommitted,
            List<McpTransportOutcome> expectedOutcomes) {

        private static Seed fromJson(String line) {
            JsonObject json = new JsonObject(line);
            JsonArray outcomes = json.getJsonArray("expectedOutcomes");
            return new Seed(
                    json.getString("id"),
                    TransportAction.valueOf(json.getString("transport")),
                    Timing.valueOf(json.getString("timing")),
                    json.getBoolean("lateCompletion"),
                    json.getBoolean("expectedCommitted"),
                    outcomes.stream()
                            .map(String.class::cast)
                            .map(McpTransportOutcome::valueOf)
                            .toList());
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** Owns one real server and the deterministic milestones used to replay a single schedule. */
    private static final class McpSettlementRaceCharacterizationITFixture implements AutoCloseable {

        private static final String TOOL_NAME = "characterization.settlement";
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        private final Vertx vertx;
        private final HttpServer server;
        private final ControlledToolInvoker tool;
        private final RecordingObserver observer;

        private McpSettlementRaceCharacterizationITFixture() throws Exception {
            vertx = Vertx.vertx();
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-characterization")
                    .serverVersion("1.0")
                    .build();
            tool = new ControlledToolInvoker();
            observer = new RecordingObserver();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));
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
            server = await(vertx.createHttpServer(new HttpServerOptions().setSendBufferSize(SMALL_SOCKET_BUFFER_BYTES))
                    .requestHandler(router)
                    .listen(0, LOOPBACK));
        }

        private static McpSettlementRaceCharacterizationITFixture start() throws Exception {
            return new McpSettlementRaceCharacterizationITFixture();
        }

        private void replay(Seed seed) throws Exception {
            try (Socket socket = openSocket(seed.timing() == Timing.DURING_WRITE)) {
                sendToolCall(socket);
                tool.awaitInvoked();
                switch (seed.timing()) {
                    case BEFORE_WRITE -> closePeer(socket, seed.transport());
                    case DURING_WRITE -> {
                        completeTool(LARGE_RESULT);
                        assertThat(readFirstResponseBodyByte(socket)).isGreaterThanOrEqualTo(0);
                        closePeer(socket, seed.transport());
                    }
                    case AFTER_WRITE -> {
                        completeTool("complete");
                        assertThat(readEntireResponse(socket)).contains("HTTP/1.1 200");
                    }
                }
            }
        }

        private Socket openSocket(boolean shrinkReceiveBuffer) throws IOException {
            Socket socket = new Socket();
            if (shrinkReceiveBuffer) {
                socket.setReceiveBufferSize(SMALL_SOCKET_BUFFER_BYTES);
            }
            socket.connect(new InetSocketAddress(LOOPBACK, server.actualPort()));
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
            return socket;
        }

        private static void closePeer(Socket socket, TransportAction action) throws IOException {
            if (action == TransportAction.RESET) {
                socket.setSoLinger(true, 0);
            }
            socket.close();
        }

        private static void sendToolCall(Socket socket) throws IOException {
            JsonObject meta = new JsonObject()
                    .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                    .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
            byte[] body = new JsonObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 32)
                    .put("method", "tools/call")
                    .put("params", new JsonObject().put("_meta", meta).put("name", TOOL_NAME))
                    .toBuffer()
                    .getBytes();
            String head = "POST " + REQUEST_PATH + " HTTP/1.1\r\n"
                    + "Host: " + LOOPBACK + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                    + "Mcp-Method: tools/call\r\n"
                    + "Mcp-Name: " + TOOL_NAME + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        }

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
            assertThat(headers.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 200");
            return socket.getInputStream().read();
        }

        private static String readEntireResponse(Socket socket) throws IOException {
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        private void completeTool(String text) {
            tool.complete(McpToolResult.text(text));
        }

        private void awaitLateCompletionReachedFramework() throws Exception {
            tool.awaitResultHandler();
        }

        /**
         * Deterministically drains the exact Vert.x context the request ran on: a marker task queued
         * on that context only resolves once every task already queued ahead of it has itself run,
         * which is what a fixed {@code Thread.sleep} grace period previously approximated.
         */
        private void drainRequestContext() throws Exception {
            Context requestContext = tool.requestContext();
            CompletableFuture<Void> marker = new CompletableFuture<>();
            requestContext.runOnContext(ignored -> marker.complete(null));
            marker.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private RecordingObserver observer() {
            return observer;
        }

        @Override
        public void close() throws Exception {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            server.close().onComplete(joined -> vertx.close().onComplete(vertxResult -> {
                Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
                if (failure != null) {
                    closed.completeExceptionally(failure);
                } else {
                    closed.complete(null);
                }
            }));
            closed.get(10, TimeUnit.SECONDS);
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

        private static final class ControlledToolInvoker implements McpToolInvoker {
            private final McpToolDescriptor descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "Settlement-race characterization tool.",
                    new McpToolAnnotations(true, false, true, false),
                    CLOSED_OBJECT_SCHEMA,
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            private final Promise<McpToolResult<?>> result = Promise.promise();
            private final CompletableFuture<Void> invoked = new CompletableFuture<>();
            private final CompletableFuture<Void> resultHandler = new CompletableFuture<>();
            private volatile Context requestContext;

            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                // Captured here rather than in invoke(): prepare() runs synchronously within
                // invokeAndRespond, unambiguously on the request-owning context, before any
                // application-supplied future could ever introduce an off-context detour.
                requestContext = Vertx.currentContext();
                return new McpPreparedToolCall() {
                    @Override
                    public Map<String, Object> normalizedArguments() {
                        return Map.of();
                    }

                    @Override
                    public Future<McpToolResult<?>> invoke() {
                        invoked.complete(null);
                        return result.future().onComplete(ignored -> resultHandler.complete(null));
                    }
                };
            }

            private void awaitInvoked() throws Exception {
                await(invoked);
            }

            private void complete(McpToolResult<?> value) {
                result.tryComplete(value);
            }

            private void awaitResultHandler() throws Exception {
                await(resultHandler);
            }

            private Context requestContext() {
                return requestContext;
            }

            private static void await(CompletableFuture<Void> signal) throws Exception {
                signal.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new ArrayList<>();
        private int terminalCount;
        private int completionCount;
        private volatile McpRequestCompletedEvent completed;

        @Override
        public McpRequestObservation open(java.time.Instant startedAt) {
            return this;
        }

        @Override
        public synchronized void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
            order.add("terminal");
            settlement.countDown();
        }

        @Override
        public synchronized void onCompleted(McpRequestCompletedEvent event) {
            completionCount++;
            completed = event;
            order.add("completed");
            settlement.countDown();
        }

        private boolean awaitSettlement() throws InterruptedException {
            return settlement.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private McpRequestCompletedEvent completed() {
            return completed;
        }

        private synchronized void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount).isOne();
            assertThat(completionCount).isOne();
            assertThat(order).containsExactly("terminal", "completed");
        }
    }

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

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return ContextScopes.noop();
        }
    };

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return ContextScopes.noop();
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
