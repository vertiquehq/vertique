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
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Repair task R50 (issue #458) — real-transport characterization of the shared {@link HttpConfig}
 * liveness bound that {@link McpRequestDispatcher#write} and {@link McpServerConfigValidator}'s
 * startup gate both document as the <em>only</em> mechanism that can ever reclaim a hanging tool
 * handler: does {@code http.idleTimeoutSeconds}/{@code http.readIdleTimeoutSeconds} still reclaim a
 * hung {@code tools/call} when sibling {@code server/discover} traffic on the <strong>same</strong>
 * HTTP/2 multiplexed connection keeps the connection non-idle?
 *
 * <p>Two rows:
 *
 * <ol>
 *   <li>{@link #httpOneDotOneControlRowReclaimsTheHungRequest()} — HTTP/1.1, one connection, one
 *       request, no sibling traffic. The armed timers MUST reclaim the hung request within the
 *       observation window. This validates the harness: if this row is ever red, the fixture itself
 *       is broken, not the HTTP/2 behavior under test.
 *   <li>{@link #http2ExperimentRowPinsTheObservedLivenessOutcome()} — HTTP/2 h2c prior-knowledge, one
 *       hung {@code tools/call} plus periodic {@code server/discover} sibling traffic multiplexed on
 *       the identical connection. This row PINS whichever behavior the real Vert.x/Netty stack
 *       actually produces; see the pinned assertion's comment block for the decision consequence of
 *       each possible outcome.
 * </ol>
 *
 * <p>h2c wiring: the server side needs no special {@link HttpConfig} option — Vert.x's {@link
 * HttpServerOptions#DEFAULT_HTTP2_CLEAR_TEXT_ENABLED} is {@code true}, so a plain, non-SSL {@link
 * HttpServerOptions} (as produced by {@link HttpConfig#toHttpServerOptions()}) already accepts h2c.
 * The client side pins prior-knowledge negotiation explicitly via {@link
 * HttpClientOptions#setProtocolVersion(HttpVersion)} {@code HTTP_2} plus {@link
 * HttpClientOptions#setHttp2ClearTextUpgrade(boolean)} {@code false} (no h2c Upgrade dance — the
 * client sends the HTTP/2 connection preface immediately). Vert.x's default HTTP/2 connection-pool
 * size per host is {@code 1} with an unbounded per-connection multiplexing limit, so every request
 * issued from one shared {@link HttpClient} to the same host:port is multiplexed onto the identical
 * physical connection with no further configuration — confirmed directly against {@code
 * PoolOptions.DEFAULT_HTTP2_MAX_POOL_SIZE} (1) and {@code
 * HttpClientOptions.DEFAULT_HTTP2_MULTIPLEXING_LIMIT} (-1, unbounded) for vertx-core 5.1.6. The test
 * additionally records every request's {@link HttpConnection} object identity at the router level and
 * asserts exactly one distinct connection was ever observed, as direct evidence — not just an
 * assumption from defaults — that the hung request and every sibling call shared one connection.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpHttp2LivenessCharacterizationIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String TOOL_NAME = "r50.http2.liveness.hung";
    private static final String DISCOVER_METHOD = "server/discover";

    /** The armed liveness bound under test: deliberately short so the window can stay bounded. */
    private static final int ARMED_TIMEOUT_SECONDS = 2;

    /**
     * The bounded observation window: {@code 6x} the armed timer, comfortably above the task's {@code
     * 5x} floor, so a reclaim that the real timer fires with normal scheduling jitter is never missed.
     */
    private static final Duration OBSERVATION_WINDOW = Duration.ofSeconds(ARMED_TIMEOUT_SECONDS * 6L);

    /** How often sibling {@code server/discover} traffic fires on the HTTP/2 experiment row. */
    private static final long SIBLING_INTERVAL_MS = 500;

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

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
    @DisplayName("R50 control (HTTP/1.1): the armed idle/read-idle timers reclaim a hung tools/call "
            + "with no sibling traffic — validates the harness")
    void httpOneDotOneControlRowReclaimsTheHungRequest() throws Exception {
        fixture = Fixture.start(vertx, ARMED_TIMEOUT_SECONDS);
        server = fixture.server();
        rawClient = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_1_1));
        client = WebClient.wrap(rawClient);

        client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody())
                .onComplete(ignored -> {});

        fixture.tool().awaitInvoked();

        boolean settledWithinWindow = fixture.observer().awaitSettlement(OBSERVATION_WINDOW);

        assertThat(settledWithinWindow)
                .as("DECISIVE (harness validation): the shared HttpConfig idle/read-idle timers must "
                        + "reclaim a hung tools/call on an otherwise-silent HTTP/1.1 connection within "
                        + OBSERVATION_WINDOW + " (armed at " + ARMED_TIMEOUT_SECONDS + "s) — if this row is "
                        + "red, the fixture is broken, not the HTTP/2 behavior under test")
                .isTrue();
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();
        assertThat(fixture.connections())
                .as("the control row runs on exactly one connection")
                .hasSize(1);
    }

    @Test
    @DisplayName("R50 experiment (HTTP/2 h2c): observed liveness outcome for a hung tools/call while "
            + "sibling server/discover traffic multiplexes on the same connection")
    void http2ExperimentRowPinsTheObservedLivenessOutcome() throws Exception {
        fixture = Fixture.start(vertx, ARMED_TIMEOUT_SECONDS);
        server = fixture.server();
        rawClient = vertx.createHttpClient(
                new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        client = WebClient.wrap(rawClient);

        client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody())
                .onComplete(ignored -> {});

        fixture.tool().awaitInvoked();

        SiblingTrafficRunner sibling = new SiblingTrafficRunner(vertx, client, fixture.port());
        sibling.start(SIBLING_INTERVAL_MS);

        // Bounded, deterministic wait: blocks for up to the full observation window, returning early
        // only if settlement is actually observed.
        boolean settledWithinWindow = fixture.observer().awaitSettlement(OBSERVATION_WINDOW);

        if (!settledWithinWindow) {
            // Deterministic drain (never the sole synchronization by itself, only a confirmation that
            // nothing the real fix would have queued is simply awaiting dispatch): drains the
            // request-owning context exactly as McpDisconnectBeforeInvocationIT does.
            drainRequestContext(fixture.hungRequestContext().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        sibling.stop();
        // A periodic tick can land right at the window boundary with its response callback still
        // in flight; deterministically await every fired request's settlement (bounded, never a bare
        // sleep) before reading the tallies below.
        sibling.awaitAllOutstanding(Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS));

        // Sibling-health evidence, gathered across the FULL window regardless of the hung-request
        // outcome — direct proof the connection genuinely stayed alive throughout the observation,
        // not merely assumed from the sibling loop having been started.
        int minimumExpectedAttempts = (int) (OBSERVATION_WINDOW.toMillis() / SIBLING_INTERVAL_MS - 2);
        assertThat(sibling.attempted())
                .as("sibling server/discover traffic must have actually run through the whole window")
                .isGreaterThanOrEqualTo(minimumExpectedAttempts);
        assertThat(sibling.failures())
                .as("sibling traffic must stay healthy throughout — direct proof the HTTP/2 connection "
                        + "was never reset or torn down while the hung request sat pending")
                .isEmpty();
        assertThat(sibling.succeeded()).isEqualTo(sibling.attempted());
        assertThat(fixture.connections())
                .as("the hung tools/call and every sibling server/discover call must share the exact "
                        + "same multiplexed HTTP/2 connection — not merely be routed to the same port")
                .hasSize(1);

        /*
         * PINNED OBSERVED BEHAVIOR (R50 / issue #458, HTTP/2 h2c, vertx-core 5.1.6):
         *
         * The armed http.idleTimeoutSeconds/readIdleTimeoutSeconds bound is applied at the whole
         * connection's socket level (Netty IdleStateHandler), not per HTTP/2 stream. Continuous
         * sibling server/discover traffic on the same multiplexed connection resets that
         * connection-level idle clock on every sibling exchange, so the connection itself never goes
         * idle long enough to trip — even though one of its streams (the hung tools/call) never
         * produces or consumes another byte for the entire observation window. The hung request is
         * therefore NEVER reclaimed while sibling traffic keeps the shared connection alive.
         *
         * Decision consequence: the rebaseline's assumption that the shared HttpConfig liveness bound
         * alone is sufficient to reclaim any hung MCP request is NOT proven for HTTP/2 multiplexed
         * connections carrying sibling traffic. Per the task contract, this pins an accepted residual
         * and issue #458 stays open, reopening the per-request-deadline design question (candidate
         * mechanism: the resilience module's timeout primitive; MCP-002 admission-control
         * implications). No production code changes accompany this characterization result.
         */
        assertThat(settledWithinWindow)
                .as("DECISIVE (issue #458): a hung tools/call is NOT reclaimed by the shared HttpConfig "
                        + "idle/read-idle timers while sibling server/discover traffic keeps the same "
                        + "multiplexed HTTP/2 connection non-idle — see the comment above this assertion "
                        + "for the pinned decision consequence")
                .isFalse();
        assertThat(fixture.observer().terminalCount())
                .as("no terminal was ever published for the hung request within the observation window")
                .isZero();
        assertThat(fixture.observer().completionCount())
                .as("no completion was ever published for the hung request within the observation window")
                .isZero();
    }

    /**
     * Deterministically drains the request-owning Vert.x context: a marker task queued on it only
     * resolves once every task any pending continuation could have queued ahead of it has itself
     * already run.
     */
    private static void drainRequestContext(Context requestContext) throws Exception {
        if (requestContext == null) {
            return;
        }
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

    private static Buffer discoverBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", DISCOVER_METHOD)
                .put("params", params)
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Periodically fires {@code server/discover} sibling traffic and tallies attempts/successes/failures. */
    private static final class SiblingTrafficRunner {
        private final Vertx vertx;
        private final WebClient client;
        private final int port;
        private final AtomicInteger attempted = new AtomicInteger();
        private final AtomicInteger succeeded = new AtomicInteger();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Void>> outstanding = new CopyOnWriteArrayList<>();
        private volatile long timerId = -1;

        SiblingTrafficRunner(Vertx vertx, WebClient client, int port) {
            this.vertx = vertx;
            this.client = client;
            this.port = port;
        }

        void start(long intervalMs) {
            timerId = vertx.setPeriodic(intervalMs, ignored -> fireOnce());
        }

        void stop() {
            if (timerId >= 0) {
                vertx.cancelTimer(timerId);
                timerId = -1;
            }
        }

        /**
         * Deterministically awaits every request already fired, including one whose periodic tick
         * landed right at the window boundary and whose async response callback had not yet run when
         * {@link #stop()} was called — never a bare sleep, only a bounded join on futures this class
         * itself completes from the response callbacks below.
         */
        void awaitAllOutstanding(Duration timeout) throws Exception {
            CompletableFuture.allOf(outstanding.toArray(CompletableFuture[]::new))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void fireOnce() {
            attempted.incrementAndGet();
            CompletableFuture<Void> settled = new CompletableFuture<>();
            outstanding.add(settled);
            client.post(port, LOOPBACK, REQUEST_PATH)
                    .putHeader("content-type", "application/json")
                    .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .putHeader("Mcp-Method", DISCOVER_METHOD)
                    .putHeader("Mcp-Name", DISCOVER_METHOD)
                    .sendBuffer(discoverBody())
                    .onSuccess(response -> {
                        if (response.statusCode() == 200) {
                            succeeded.incrementAndGet();
                        } else {
                            failures.add("unexpected status " + response.statusCode());
                        }
                        settled.complete(null);
                    })
                    .onFailure(cause -> {
                        failures.add(cause.toString());
                        settled.complete(null);
                    });
        }

        int attempted() {
            return attempted.get();
        }

        int succeeded() {
            return succeeded.get();
        }

        List<String> failures() {
            return failures;
        }
    }

    /** A {@code PermitAll} tool whose invocation future is gated behind a promise this test never releases. */
    private static final class HangingToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R50 characterization fixture tool: gated, deliberately never released.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();
        private final Promise<McpToolResult<?>> neverReleased = Promise.promise();

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
                    return neverReleased.future();
                }
            };
        }

        private void awaitInvoked() throws Exception {
            invoked.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Records settlement order and every completed event's transport facts for the hung {@code
     * tools/call} ONLY.
     *
     * <p>{@link #open} is a per-request session factory (one call per MCP request), but sibling
     * {@code server/discover} traffic on the HTTP/2 experiment row settles independently and almost
     * immediately — a first exploratory run of this fixture proved that a single shared instance
     * naively counting every request's terminal/completion, with no per-request filter, misattributes
     * the sibling's own fast settlement to the hung request. Each opened session therefore checks the
     * terminal event's {@code toolName} and only forwards facts whose {@code toolName} is the hung
     * request's {@link #TOOL_NAME} into this shared latch/order/count state; every sibling discover
     * settlement (whose {@code toolName} is the well-known {@code UNKNOWN} literal) is deliberately
     * ignored here.
     */
    private static final class RecordingObserver implements McpRequestLifecycleObserver {
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completionCount = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return new McpRequestObservation() {
                @Override
                public void onTerminal(McpRequestTerminalObservation observation) {
                    if (!TOOL_NAME.equals(observation.event().toolName())) {
                        return;
                    }
                    terminalCount.incrementAndGet();
                    order.add("terminal");
                    settlement.countDown();
                }

                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    if (!TOOL_NAME.equals(event.terminal().toolName())) {
                        return;
                    }
                    completionCount.incrementAndGet();
                    order.add("completed");
                    settlement.countDown();
                }
            };
        }

        private boolean awaitSettlement(Duration window) throws InterruptedException {
            return settlement.await(window.toMillis(), TimeUnit.MILLISECONDS);
        }

        private int terminalCount() {
            return terminalCount.get();
        }

        private int completionCount() {
            return completionCount.get();
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
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    /**
     * One real port-0 mount with the armed liveness bound applied to the actual {@link HttpServer}
     * (not merely threaded into the dispatcher), one gated {@link HangingToolInvoker}, and a
     * router-level handler recording every request's {@link HttpConnection} object identity.
     */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final HangingToolInvoker tool = new HangingToolInvoker();
        private final RecordingObserver observer = new RecordingObserver();
        private final Set<HttpConnection> observedConnections = ConcurrentHashMap.newKeySet();
        private final CompletableFuture<Context> hungRequestContext = new CompletableFuture<>();

        private Fixture(Vertx vertx, int armedTimeoutSeconds) throws Exception {
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
                    Optional.empty()));
            HttpConfig httpConfig = HttpConfig.builder()
                    .idleTimeoutSeconds(armedTimeoutSeconds)
                    .readIdleTimeoutSeconds(armedTimeoutSeconds)
                    .build();
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
            router.route().handler(ctx -> {
                observedConnections.add(ctx.request().connection());
                if (TOOL_NAME.equals(ctx.request().getHeader("Mcp-Name"))) {
                    hungRequestContext.complete(Vertx.currentContext());
                }
                ctx.next();
            });
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            // The armed idle/read-idle bound must reach the real transport, not only the dispatcher:
            // toHttpServerOptions() is what actually installs Netty's IdleStateHandler.
            HttpServerOptions serverOptions =
                    httpConfig.toHttpServerOptions().setPort(0).setHost(LOOPBACK);
            this.server = await(
                    vertx.createHttpServer(serverOptions).requestHandler(router).listen());
            this.port = server.actualPort();
        }

        static Fixture start(Vertx vertx, int armedTimeoutSeconds) throws Exception {
            return new Fixture(vertx, armedTimeoutSeconds);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        HangingToolInvoker tool() {
            return tool;
        }

        RecordingObserver observer() {
            return observer;
        }

        Set<HttpConnection> connections() {
            return observedConnections;
        }

        CompletableFuture<Context> hungRequestContext() {
            return hungRequestContext;
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
