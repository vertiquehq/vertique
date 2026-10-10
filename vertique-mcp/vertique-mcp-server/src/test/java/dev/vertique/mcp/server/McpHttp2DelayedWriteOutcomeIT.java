// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpCompletionScope;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
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
import dev.vertique.rest.core.security.RouteAuthHandler;
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
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real-transport pin for the transport outcome of a response written from a timer task.
 *
 * <p>On HTTP/2, Vert.x delivers the stream-close signal re-entrantly while the response's own
 * {@code end()} call is still on the stack when the write is issued from a timer rather than from
 * the request handler, and Vert.x Web reports it to routing-context end handlers as a failed
 * {@code "Connection closed"} outcome even though {@code end()} then succeeds. The hook that
 * settles disconnects and resets must not read that outcome as a transport failure. These rows pin
 * the settled outcome of a delayed and an immediate response on h2c and HTTP/1.1, and that a
 * genuine disconnect and a stream reset mid-wait are still reported.
 */
class McpHttp2DelayedWriteOutcomeIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String SCHEME_NAME = "delayed-write-scheme";
    private static final String HELD_TOOL = "delayed.write.held";
    private static final String PLAIN_TOOL = "delayed.write.plain";
    private static final String STREAMED_TOOL = "delayed.write.streamed";

    /** How long the held tool's result is withheld before it is released from a timer task. */
    private static final long RELEASE_DELAY_MS = 200;

    private static final long ASYNC_TIMEOUT_SECONDS = 15;

    private final Vertx vertx = Vertx.vertx();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    private Fixture fixture;
    private HttpClient client;

    @BeforeEach
    void captureUncaughtFailures() {
        vertx.exceptionHandler(uncaught::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
        Future<Void> clientClose = client != null ? client.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure != null) {
                closed.completeExceptionally(failure);
            } else {
                closed.complete(null);
            }
        }));
        closed.get(15, TimeUnit.SECONDS);
        fixture = null;
        client = null;
        assertThat(uncaught)
                .as("no failure escaped to the context exception handler")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Responses that are fully delivered
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an immediate tools/call on h2c completes as WRITTEN and is never cancelled")
    void immediateResponseOnH2cIsWritten() throws Exception {
        start(HttpVersion.HTTP_2);

        Reply reply = callTool(PLAIN_TOOL).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.plain().signal().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a tools/call whose result is written from a timer task on h2c completes as WRITTEN "
            + "and never fires cancellation")
    void delayedResponseOnH2cIsWrittenAndNotCancelled() throws Exception {
        start(HttpVersion.HTTP_2);
        CompletableFuture<Reply> call = callTool(HELD_TOOL);
        fixture.held().awaitInvoked();
        vertx.setTimer(RELEASE_DELAY_MS, ignored -> fixture.held().release());

        Reply reply = call.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.failure()).isNull();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"late\"");
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.terminals().get(0).outcome()).isEqualTo(McpOutcome.SUCCESS);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.held().signal().isCancelled())
                .as("a response that was delivered in full must not cancel the request")
                .isFalse();
    }

    @Test
    @DisplayName("a streamed tools/call whose terminal frame is written from a timer task on h2c "
            + "completes as WRITTEN and never fires cancellation")
    void delayedStreamedResponseOnH2cIsWrittenAndNotCancelled() throws Exception {
        start(HttpVersion.HTTP_2);

        Reply reply = callTool(STREAMED_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.failure()).isNull();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("notifications/progress").contains("streamed");
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(session.completions().get(0).responseCommitted()).isTrue();
        assertThat(fixture.streamed().signal().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("the same delayed result on HTTP/1.1 completes as WRITTEN and never fires cancellation")
    void delayedResponseOnHttp1IsWrittenAndNotCancelled() throws Exception {
        start(HttpVersion.HTTP_1_1);
        CompletableFuture<Reply> call = callTool(HELD_TOOL);
        fixture.held().awaitInvoked();
        vertx.setTimer(RELEASE_DELAY_MS, ignored -> fixture.held().release());

        Reply reply = call.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.held().signal().isCancelled()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Genuine transport failures are still reported
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stream reset by the client while the tool is held on h2c still completes as RESET "
            + "and cancels the tool")
    void streamResetWhileHeldOnH2cIsReset() throws Exception {
        start(HttpVersion.HTTP_2);
        HttpClientRequest request = openToolCall(HELD_TOOL);
        fixture.held().awaitInvoked();

        request.reset();
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.RESET);
        assertThat(fixture.held().signal().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("a connection closed by the client while the tool is held on h2c still completes as "
            + "DISCONNECTED and cancels the tool")
    void connectionCloseWhileHeldOnH2cIsDisconnected() throws Exception {
        start(HttpVersion.HTTP_2);
        HttpClientRequest request = openToolCall(HELD_TOOL);
        fixture.held().awaitInvoked();

        request.connection().close();
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.DISCONNECTED);
        assertThat(fixture.held().signal().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("a connection closed by the client while the tool is held on HTTP/1.1 still completes as "
            + "DISCONNECTED and cancels the tool")
    void connectionCloseWhileHeldOnHttp1IsDisconnected() throws Exception {
        start(HttpVersion.HTTP_1_1);
        HttpClientRequest request = openToolCall(HELD_TOOL);
        fixture.held().awaitInvoked();

        request.connection().close();
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.DISCONNECTED);
        assertThat(fixture.held().signal().isCancelled()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private void start(HttpVersion version) throws Exception {
        fixture = new Fixture(vertx);
        client = vertx.createHttpClient(
                new HttpClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false));
    }

    private CompletableFuture<Reply> callTool(String toolName) {
        return callTool(toolName, false);
    }

    private CompletableFuture<Reply> callTool(String toolName, boolean withProgressToken) {
        return exchange(options(toolName), toolCallBody(toolName, withProgressToken));
    }

    /**
     * Sends {@code body} and reads the whole response. The response and its body are observed before
     * the request is ended, so no body buffer can arrive unobserved; a failed body read keeps the
     * status the head carried.
     */
    private CompletableFuture<Reply> exchange(RequestOptions options, Buffer body) {
        CompletableFuture<Reply> done = new CompletableFuture<>();
        client.request(options).onComplete(created -> {
            if (created.failed()) {
                done.complete(new Reply(-1, null, created.cause()));
                return;
            }
            HttpClientRequest request = created.result();
            request.response().onComplete(responded -> {
                if (responded.failed()) {
                    done.complete(new Reply(-1, null, responded.cause()));
                    return;
                }
                HttpClientResponse response = responded.result();
                int status = response.statusCode();
                response.body()
                        .onComplete(read -> done.complete(
                                read.succeeded()
                                        ? new Reply(status, read.result().toString(), null)
                                        : new Reply(status, null, read.cause())));
            });
            request.end(body);
        });
        return done;
    }

    /** Sends a tools/call and leaves it unanswered, returning the request so the test can abort it. */
    private HttpClientRequest openToolCall(String toolName) throws Exception {
        HttpClientRequest request = await(client.request(options(toolName)));
        // The response is intentionally never read: the test aborts the request or connection.
        request.response().onComplete(ignored -> {});
        request.end(toolCallBody(toolName, false));
        return request;
    }

    private RequestOptions options(String toolName) {
        return new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost(LOOPBACK)
                .setPort(fixture.port())
                .setURI(REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName);
    }

    private static Buffer toolCallBody(String toolName, boolean withProgressToken) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        if (withProgressToken) {
            meta.put("progressToken", "delayed-write-progress");
        }
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", toolName).put("arguments", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** What the client observed for one request. */
    private record Reply(int status, String body, Throwable failure) {}

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /** One real port-0 h2c-capable mount with a tool that is held until released and one that is not. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final Tool held;
        private final Tool plain;
        private final Tool streamed;
        private final Recorder recorder = new Recorder();

        private Fixture(Vertx vertx) throws Exception {
            McpAccessMode permitAll = McpAccessMode.PERMIT_ALL;
            this.held = new Tool(vertx, HELD_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.HOLD);
            this.plain =
                    new Tool(vertx, PLAIN_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.IMMEDIATE);
            this.streamed = new Tool(
                    vertx, STREAMED_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.PROGRESS);
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(held, plain, streamed));
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            // The connection-level idle timer is armed, but far above every observation window.
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(recorder),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(new OptionalRouteAuthHandler()),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router root = Router.router(vertx);
            root.route().handler(new RequestContextLifecycle());
            Router mountRouter = await(mount.createRouter(vertx));
            root.route(config.mountPath()).subRouter(mountRouter);
            HttpServerOptions serverOptions =
                    httpConfig.toHttpServerOptions().setPort(0).setHost(LOOPBACK);
            this.server = await(
                    vertx.createHttpServer(serverOptions).requestHandler(root).listen());
            this.port = server.actualPort();
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        Tool held() {
            return held;
        }

        Tool plain() {
            return plain;
        }

        Tool streamed() {
            return streamed;
        }

        Recorder recorder() {
            return recorder;
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tool, observers, security plumbing
    // ---------------------------------------------------------------------------------------------

    /** One fixture tool: answered immediately, or held until the test releases it. */
    private static final class Tool implements McpToolInvoker {
        enum Behavior {
            HOLD,
            IMMEDIATE,
            PROGRESS
        }

        static final int PROGRESS_TICKS = 3;
        static final long PROGRESS_TICK_MS = 50;

        private final Vertx vertx;
        private final McpToolDescriptor descriptor;
        private final Behavior behavior;
        private final CompletableFuture<Void> firstInvoke = new CompletableFuture<>();
        private final Promise<McpToolResult<?>> gate = Promise.promise();
        private volatile McpCancellationSignal signal;

        Tool(Vertx vertx, String name, McpToolAccess access, Behavior behavior) {
            this.vertx = vertx;
            this.behavior = behavior;
            this.descriptor = new McpToolDescriptor(
                    name,
                    null,
                    "Delayed-write outcome fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\",\"additionalProperties\":false}",
                    null,
                    access);
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            signal = cancellation;
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    firstInvoke.complete(null);
                    return switch (behavior) {
                        case HOLD -> gate.future();
                        case IMMEDIATE -> Future.succeededFuture(McpToolResult.text("done"));
                        case PROGRESS -> streamProgress(cancellation);
                    };
                }
            };
        }

        private Future<McpToolResult<?>> streamProgress(McpCancellationSignal cancellation) {
            Promise<McpToolResult<?>> result = Promise.promise();
            AtomicInteger ticks = new AtomicInteger();
            vertx.setPeriodic(PROGRESS_TICK_MS, timerId -> {
                int tick = ticks.incrementAndGet();
                cancellation.progressReporter().report(tick, (double) PROGRESS_TICKS, null);
                if (tick >= PROGRESS_TICKS) {
                    vertx.cancelTimer(timerId);
                    result.tryComplete(McpToolResult.text("streamed"));
                }
            });
            return result.future();
        }

        void release() {
            gate.tryComplete(McpToolResult.text("late"));
        }

        void awaitInvoked() throws Exception {
            firstInvoke.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        McpCancellationSignal signal() {
            return signal;
        }
    }

    private static final class Recorder implements McpRequestLifecycleObserver {
        private final CompletableFuture<Session> firstNonDiscoverCompletion = new CompletableFuture<>();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return new Session(this);
        }

        Session awaitNonDiscoverCompletion() throws Exception {
            return firstNonDiscoverCompletion.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class Session implements McpCompletionScope, McpToolValueObservation {
        private final Recorder recorder;
        private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();
        private final List<McpRequestCompletedEvent> completions = new CopyOnWriteArrayList<>();

        Session(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.add(observation.event());
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completions.add(event);
            if (event.terminal().method() != McpMethod.SERVER_DISCOVER) {
                recorder.firstNonDiscoverCompletion.complete(this);
            }
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {}

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {}

        @Override
        public AutoCloseable openCompletionScope() {
            return () -> {};
        }

        List<McpRequestTerminalEvent> terminals() {
            return terminals;
        }

        List<McpRequestCompletedEvent> completions() {
            return completions;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Security plumbing
    // ---------------------------------------------------------------------------------------------

    private record OptionalRouteAuthHandler() implements RouteAuthHandler {
        @Override
        public String schemeName() {
            return SCHEME_NAME;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return RoutingContext::next;
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(RoutingContext::next);
        }
    }

    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

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
        public void clearCurrent() {
            bound = null;
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
