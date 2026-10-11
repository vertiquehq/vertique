// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.resilience.Resilience;
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
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves that a tool's result becomes visible through the request view only when its own terminal
 * write won settlement: a request that settled by disconnect before the tool's gated result resolved
 * reports no tool output, and the plain written path reports exactly one.
 *
 * <p>When the client has already disconnected before the tool's result future resolves, the terminal
 * writer's settlement claim is guaranteed to lose, so the write is correctly suppressed and the
 * result must never be reported as the tool output. This proof settles the request via disconnect
 * strictly before releasing the tool's gated result, then releases it so {@code writeToolResult} runs
 * against an already-settled coordinator, and asserts that the one view the listener received
 * reported no output and that no second view arrives. The sibling "plain path" case in the same class
 * proves the opposite half — a request that is never disconnected must still report its output —
 * stays green throughout.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolOutputObservationOrderingIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String TOOL_NAME = "output.observation.ordering";
    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /**
     * The bounded confirmation window checked only after the request-owning context has already
     * been deterministically drained — never the sole synchronization for the negative assertion in
     * the disconnect row, only a safety margin for any legitimately asynchronous continuation the
     * eventual fix might still introduce.
     */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofMillis(500);

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
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
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("the view must report no tool output once the client disconnected before the tool's "
            + "gated result was released")
    void shouldReportNoToolOutputWhenTheClientDisconnectsBeforeTheToolResultIsReleased() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody())
                .onComplete(ignored -> {});

        fixture.tool().awaitInvoked();
        await(rawClient.close());

        assertThat(fixture.observer().awaitSettlement())
                .as("the server must settle the disconnected request while the tool's result is still " + "unreleased")
                .isTrue();
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();
        McpRequestCompletedEvent settled = fixture.observer().completed();
        assertThat(settled.responseCommitted())
                .as("no response byte was ever committed before disconnect settlement")
                .isFalse();
        assertThat(settled.transportOutcome()).isEqualTo(McpTransportOutcome.DISCONNECTED);

        // The listener runs right after the observer's completion on the same settlement, so its one
        // view is awaited before the tool's result is released.
        List<Completion> settledViews = fixture.listener().await(1);
        assertThat(settledViews.get(0).toolInput())
                .as("the call was prepared before the disconnect, so its arguments are reported")
                .isPresent();
        assertThat(settledViews.get(0).toolOutput())
                .as("DECISIVE: no tool output is reported for a request that settled by disconnect")
                .isEmpty();
        assertThat(settledViews.get(0).hasResponseBody())
                .as("no terminal write happened, so no response body is reported")
                .isFalse();

        // Only now — strictly after settlement was already observed — does the tool's result resolve,
        // so writeToolResult runs against a coordinator that has already lost the race.
        fixture.tool().complete(McpToolResult.text("late"));
        drainRequestContext(fixture.observer().expectedContext());

        assertThat(fixture.listener().arrivesWithin(2, CONFIRMATION_WINDOW))
                .as("DECISIVE: the late result must not produce a second view")
                .isFalse();
        assertThat(fixture.listener().completions())
                .as("the one view already delivered is unchanged")
                .hasSize(1);
        assertThat(fixture.listener().completions().get(0).toolOutput())
                .as("DECISIVE: a result that resolved after the client had already disconnected must "
                        + "never be reported as the tool output")
                .isEmpty();

        fixture.observer().assertExactlyOneTerminalThenOneCompletion(); // no further settlement occurred
    }

    @Test
    @DisplayName("the plain written path must still report exactly one tool output")
    void shouldReportExactlyOneToolOutputOnThePlainWrittenPath() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        Future<HttpResponse<Buffer>> responseFuture = client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody());

        fixture.tool().awaitInvoked();
        fixture.tool().complete(McpToolResult.text("done"));

        HttpResponse<Buffer> response = await(responseFuture);
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(fixture.observer().awaitSettlement()).isTrue();
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();

        List<Completion> completions = fixture.listener().await(1);
        assertThat(completions)
                .as("NON-VACUITY / regression: the plain written path must still report exactly one "
                        + "view once the tool's result actually reached the wire")
                .hasSize(1);
        assertThat(completions.get(0).toolOutput())
                .as("the result's own write won settlement, so the tool output is reported")
                .isPresent();
        assertThat(completions.get(0).responseBody())
                .as("the view's response is the bytes the client received")
                .isEqualTo(response.bodyAsString())
                .contains("done");
        assertThat(completions.get(0).event().transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
    }

    /**
     * Deterministically drains the request-owning Vert.x context: a marker task queued on it only
     * resolves once every task the tool's completion could have queued ahead of it — including any
     * on-context redispatch a fix might introduce — has itself already run.
     */
    private static void drainRequestContext(Context requestContext) throws Exception {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        requestContext.runOnContext(ignored -> marker.complete(null));
        marker.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Buffer toolCallBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", TOOL_NAME).put("arguments", new JsonObject());
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

    /** A {@code PermitAll} tool whose result the test releases explicitly and independently of dispatch. */
    private static final class ControlledToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R32 defect 3 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        private final Promise<McpToolResult<?>> result = Promise.promise();
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();

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
                    return result.future();
                }
            };
        }

        private void awaitInvoked() throws Exception {
            invoked.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void complete(McpToolResult<?> value) {
            result.tryComplete(value);
        }
    }

    /** Records this request's lifecycle facts. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private volatile Context expectedContext;
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completionCount = new AtomicInteger();
        private volatile McpRequestCompletedEvent completed;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            expectedContext = Vertx.currentContext();
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount.incrementAndGet();
            order.add("terminal");
            settlement.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount.incrementAndGet();
            completed = event;
            order.add("completed");
            settlement.countDown();
        }

        private Context expectedContext() {
            return expectedContext;
        }

        private boolean awaitSettlement() throws InterruptedException {
            return settlement.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private McpRequestCompletedEvent completed() {
            return completed;
        }

        private void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount.get()).isOne();
            assertThat(completionCount.get()).isOne();
            assertThat(order).containsExactly("terminal", "completed");
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

    /** One real port-0 mount and one {@code PermitAll} tool whose result the test releases explicitly. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final ControlledToolInvoker tool = new ControlledToolInvoker();
        private final RecordingObserver observer = new RecordingObserver();
        private final McpRecordingCompletedListener listener = new McpRecordingCompletedListener();

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty(),
                    Resilience.create(vertx)));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(observer),
                            Set.of(listener),
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
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
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

        ControlledToolInvoker tool() {
            return tool;
        }

        RecordingObserver observer() {
            return observer;
        }

        McpRecordingCompletedListener listener() {
            return listener;
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
}
