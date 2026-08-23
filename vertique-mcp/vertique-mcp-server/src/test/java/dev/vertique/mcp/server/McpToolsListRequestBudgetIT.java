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
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R10 (merge blocker 3, issue #438) — the request-scoped authorization budget on {@code tools/list}.
 *
 * <p>R01 bounded a <em>hung</em> per-candidate gate; it never bounded a <em>slow</em> one, and never
 * bounded the aggregate. A gate answering just under its own deadline never trips the
 * {@code gateTimedOut} stop {@link McpToolsListGateTimeoutTerminatesScanIT} proves, so nothing
 * previously stopped the walk, and nothing previously consulted the completion coordinator's
 * cancellation signal at all. Every proof below therefore uses a decision point that answers
 * <strong>promptly</strong> — well inside {@link SecurityPolicyEnforcer#DEFAULT_GATE_DEADLINE_MS} —
 * so none of them could be satisfied by the pre-existing hung-gate mechanism.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class McpToolsListRequestBudgetIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SCHEME_NAME = "test-scheme";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .onComplete(ignored -> vertx.close().onComplete(result -> closed.complete(null)));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    // --- Proof 1: the aggregate deadline stops a scan whose individual gates all answer promptly ---

    private static final int DEADLINE_TOOL_COUNT = 12;
    private static final long DEADLINE_DECISION_DELAY_MS = 300L;

    /** The validator's documented minimum for {@code mcp.tools.listDeadlineMs}. */
    private static final long SCAN_DEADLINE_MS = 1_000L;

    @Test
    @DisplayName("the aggregate deadline stops the scan even though every gate answers well inside its own limit")
    void shouldStopTheScanWhenTheAggregateDeadlineElapsesEvenThoughEveryGateAnswersPromptly() throws Exception {
        fixture = Fixture.startWithPromptDecisionPoint(
                vertx, DEADLINE_TOOL_COUNT, DEADLINE_DECISION_DELAY_MS, SCAN_DEADLINE_MS);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> first = await(listTools(null));
        assertThat(first.statusCode()).isEqualTo(200);
        JsonObject firstResult = new JsonObject(first.bodyAsString()).getJsonObject("result");
        JsonArray firstTools = firstResult.getJsonArray("tools");

        // DECISIVE: every one of DEADLINE_DECISION_DELAY_MS's answers lands well inside
        // SecurityPolicyEnforcer.DEFAULT_GATE_DEADLINE_MS (5s), so the pre-existing gateTimedOut stop
        // (R01/#417) never fires here — this is exactly the case its own proof cannot see. The scan
        // must still stop before examining every candidate, purely on the aggregate deadline.
        assertThat(fixture.decisionPointCallCount())
                .as("DECISIVE: the aggregate deadline must stop the scan before it examines every "
                        + "candidate, even though no individual gate ever came close to timing out")
                .isBetween(1, DEADLINE_TOOL_COUNT - 1);
        assertThat(firstTools.size())
                .as("every examined candidate permitted (no gate timeout occurred): the tools array and "
                        + "the decision-point call count must match exactly")
                .isEqualTo(fixture.decisionPointCallCount());
        String nextCursor = firstResult.getString("nextCursor");
        assertThat(nextCursor)
                .as("candidates remain past the truncated page, so a cursor must be returned")
                .isNotNull();

        // A truncated page still returns a usable cursor, and the unexamined tools are reachable
        // through it — the very next unexamined candidate (by scanned order) must appear on page two.
        int examinedOnFirstPage = firstTools.size();
        String expectedNextName = String.format("budget.tool%02d", examinedOnFirstPage);

        HttpResponse<Buffer> second = await(listTools(nextCursor));
        assertThat(second.statusCode()).isEqualTo(200);
        JsonArray secondTools =
                new JsonObject(second.bodyAsString()).getJsonObject("result").getJsonArray("tools");
        assertThat(toolNames(secondTools))
                .as("DECISIVE: the first candidate left unexamined by the deadline-truncated first page "
                        + "must be reachable through the returned cursor, not silently dropped")
                .contains(expectedNextName);
    }

    // --- Proof 2: a disconnect stops the scan — the invocation count itself stops climbing ---

    private static final int DISCONNECT_TOOL_COUNT = 40;
    private static final long DISCONNECT_DECISION_DELAY_MS = 80L;
    private static final int DISCONNECT_AFTER_CALL_COUNT = 3;
    private static final long SETTLEMENT_TIMEOUT_SECONDS = 10L;

    /**
     * Long enough, relative to {@link #DISCONNECT_DECISION_DELAY_MS}, that several more decisions
     * would have started by now if the fix regressed — a settled request alone proves nothing here,
     * since the pre-R10 code also settles the request while continuing to scan.
     */
    private static final long POST_SETTLEMENT_GRACE_MS = 600L;

    @Test
    @DisplayName("a disconnect stops the scan: the decision-point invocation count itself stops climbing")
    void shouldStopIssuingNewDecisionsAfterDisconnectEvenThoughEachGateAnswersPromptly() throws Exception {
        fixture = Fixture.startWithPromptDecisionPointAndObserver(
                vertx,
                DISCONNECT_TOOL_COUNT,
                DISCONNECT_DECISION_DELAY_MS,
                McpServerConfig.defaults().toolsListDeadlineMs());

        try (Socket socket = rawSocket()) {
            sendToolsListRequest(socket);
            assertThat(fixture.decisionPoint().awaitCallCount(DISCONNECT_AFTER_CALL_COUNT, SETTLEMENT_TIMEOUT_SECONDS))
                    .as("the scan must have started several decisions before the disconnect below")
                    .isTrue();
            // A hard reset (SO_LINGER 0), like the sibling T013 proofs, so the server sees the
            // transport event immediately rather than waiting on an orderly FIN.
            socket.setSoLinger(true, 0);
        }

        assertThat(fixture.observer().awaitSettlement(SETTLEMENT_TIMEOUT_SECONDS))
                .as("the coordinator must settle (terminal + completion) once the disconnect is observed")
                .isTrue();
        int countAtSettlement = fixture.decisionPointCallCount();

        Thread.sleep(POST_SETTLEMENT_GRACE_MS);

        assertThat(fixture.decisionPointCallCount())
                .as("DECISIVE: the invocation count must not climb further after settlement — a request "
                        + "that merely settled (as the pre-R10 code also does) proves nothing; only a count "
                        + "that stops climbing does")
                .isEqualTo(countAtSettlement);
    }

    // --- Proof 3: cancellation is observed after an in-flight decision resolves, post-disconnect ---

    private static final int AFTER_TOOL_COUNT = 5;

    /** The candidate index (1-based call count) whose decision this proof holds open manually. */
    private static final int HELD_CALL_NUMBER = 2;

    @Test
    @DisplayName("cancellation is observed after an in-flight decision resolves, even long after the client left")
    void shouldStopTheScanAfterAnInFlightDecisionResolvesPostDisconnect() throws Exception {
        fixture = Fixture.startWithHoldableDecisionPoint(vertx, AFTER_TOOL_COUNT, HELD_CALL_NUMBER);

        try (Socket socket = rawSocket()) {
            sendToolsListRequest(socket);
            assertThat(fixture.holdableDecisionPoint().awaitHeld(SETTLEMENT_TIMEOUT_SECONDS))
                    .as("the second candidate's decision must be in flight (held) before the disconnect below")
                    .isTrue();
            socket.setSoLinger(true, 0);
        }

        // The coordinator settles independently of the still-pending decision: T013's cancellation
        // signal fires on the transport event itself, not on anything the scan does.
        assertThat(fixture.observer().awaitSettlement(SETTLEMENT_TIMEOUT_SECONDS))
                .as("the coordinator must settle even though the second candidate's decision is still "
                        + "unresolved at this point")
                .isTrue();
        assertThat(fixture.decisionPointCallCount())
                .as("only the held candidate's decision has been invoked so far")
                .isEqualTo(HELD_CALL_NUMBER);

        // Now the slow decision point finally answers — long after the client left, exactly the
        // scenario this proof exists for.
        fixture.holdableDecisionPoint().releaseHeldWithPermit();
        Thread.sleep(POST_SETTLEMENT_GRACE_MS);

        assertThat(fixture.decisionPointCallCount())
                .as("DECISIVE: cancellation must be observed immediately after the in-flight decision "
                        + "resolves — a third decision must never be started")
                .isEqualTo(HELD_CALL_NUMBER);
    }

    // --- Shared helpers ---

    private Future<HttpResponse<Buffer>> listTools(String cursor) {
        return client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(listToolsBody(cursor));
    }

    private static Buffer listToolsBody(String cursor) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta);
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", params)
                .toBuffer();
    }

    private static List<String> toolNames(JsonArray tools) {
        return tools.stream()
                .map(JsonObject.class::cast)
                .map(tool -> tool.getString("name"))
                .toList();
    }

    private Socket rawSocket() throws IOException {
        Socket socket = new Socket(LOOPBACK, fixture.port());
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(SETTLEMENT_TIMEOUT_SECONDS));
        return socket;
    }

    private static void sendToolsListRequest(Socket socket) throws IOException {
        byte[] body = listToolsBody(null).getBytes();
        String head = "POST " + REQUEST_PATH + " HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + "\r\n"
                + "Content-Type: application/json\r\n"
                + "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                + "Mcp-Method: tools/list\r\n"
                + "Mcp-Name: tools/list\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /** A zero-argument, {@code Constrained} tool, never invoked by any proof in this class. */
    private static final class RestrictedToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        RestrictedToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
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
                    throw new AssertionError("this proof never invokes a tool");
                }
            };
        }
    }

    /**
     * An {@link AuthorizationDecisionPoint} that always permits, resolving after a fixed real delay —
     * scheduled through the fixture's own {@link Vertx} timer, so it is a genuinely asynchronous
     * decision (never {@link Future#isComplete()} synchronously) — well inside {@link
     * SecurityPolicyEnforcer#DEFAULT_GATE_DEADLINE_MS}, so the pre-existing gate-timeout stop never
     * fires. Counts invocations and exposes a threshold-reached signal for the disconnect proof.
     */
    private static final class PromptDecisionPoint implements AuthorizationDecisionPoint {
        private final Vertx vertx;
        private final long delayMs;
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile int awaitedThreshold = Integer.MAX_VALUE;
        private final CompletableFuture<Void> thresholdReached = new CompletableFuture<>();

        PromptDecisionPoint(Vertx vertx, long delayMs) {
            this.vertx = vertx;
            this.delayMs = delayMs;
        }

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            int call = callCount.incrementAndGet();
            if (call >= awaitedThreshold) {
                thresholdReached.complete(null);
            }
            Promise<AuthorizationDecision> promise = Promise.promise();
            vertx.setTimer(delayMs, ignored -> promise.complete(AuthorizationDecision.permit("PERMITTED")));
            return promise.future();
        }

        int callCount() {
            return callCount.get();
        }

        boolean awaitCallCount(int threshold, long timeoutSeconds) throws Exception {
            awaitedThreshold = threshold;
            if (callCount.get() >= threshold) {
                return true;
            }
            try {
                thresholdReached.get(timeoutSeconds, TimeUnit.SECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException timedOut) {
                return false;
            }
        }
    }

    /**
     * An {@link AuthorizationDecisionPoint} whose {@code holdOnCall}-numbered invocation returns a
     * promise this fixture holds open manually (never scheduled, never timer-driven) until {@link
     * #releaseHeldWithPermit()} is called; every other call permits immediately.
     */
    private static final class HoldableDecisionPoint implements AuthorizationDecisionPoint {
        private final int holdOnCall;
        private final AtomicInteger callCount = new AtomicInteger();
        private final CompletableFuture<Void> held = new CompletableFuture<>();
        private volatile Promise<AuthorizationDecision> heldPromise;

        HoldableDecisionPoint(int holdOnCall) {
            this.holdOnCall = holdOnCall;
        }

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            int call = callCount.incrementAndGet();
            if (call == holdOnCall) {
                Promise<AuthorizationDecision> promise = Promise.promise();
                heldPromise = promise;
                held.complete(null);
                return promise.future();
            }
            return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
        }

        int callCount() {
            return callCount.get();
        }

        boolean awaitHeld(long timeoutSeconds) throws Exception {
            try {
                held.get(timeoutSeconds, TimeUnit.SECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException timedOut) {
                return false;
            }
        }

        void releaseHeldWithPermit() {
            heldPromise.complete(AuthorizationDecision.permit("PERMITTED"));
        }
    }

    /** A {@value #SCHEME_NAME}-named handler exposing the optional-authentication capability. */
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

    private record AnonymousOnlyIdentityResolver() implements SecurityIdentityResolver {
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

    /** Records terminal and completion callbacks, mirroring {@code McpCancellationIT}'s own fixture. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch callbacks = new CountDownLatch(2);

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            callbacks.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            callbacks.countDown();
        }

        boolean awaitSettlement(long timeoutSeconds) throws InterruptedException {
            return callbacks.await(timeoutSeconds, TimeUnit.SECONDS);
        }
    }

    /** One real port-0 mount, configurable tool count, and one of this class's decision points. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final PromptDecisionPoint promptDecisionPoint;
        private final HoldableDecisionPoint holdableDecisionPoint;
        private final RecordingObserver observer;

        private Fixture(
                Vertx vertx,
                int toolCount,
                long deadlineMs,
                AuthorizationDecisionPoint decisionPoint,
                PromptDecisionPoint promptDecisionPoint,
                HoldableDecisionPoint holdableDecisionPoint,
                boolean withObserver)
                throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .toolsListDeadlineMs(deadlineMs)
                    .build();
            Set<RouteAuthHandler> routeAuthHandlers = Set.of(new OptionalRouteAuthHandler());

            Set<McpToolInvoker> invokers = new HashSet<>();
            for (int i = 0; i < toolCount; i++) {
                String name = String.format("budget.tool%02d", i);
                McpToolDescriptor descriptor = new McpToolDescriptor(
                        name,
                        null,
                        "R10 request-budget fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\"}",
                        null,
                        new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
                invokers.add(new RestrictedToolInvoker(descriptor));
            }
            McpToolRegistry registry = McpToolRegistry.build(invokers);

            this.promptDecisionPoint = promptDecisionPoint;
            this.holdableDecisionPoint = holdableDecisionPoint;
            this.observer = withObserver ? new RecordingObserver() : null;
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.of(decisionPoint),
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
                            this.observer != null ? Set.of(this.observer) : Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    routeAuthHandlers,
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
            this.port = server.actualPort();
        }

        static Fixture startWithPromptDecisionPoint(Vertx vertx, int toolCount, long delayMs, long deadlineMs)
                throws Exception {
            PromptDecisionPoint decisionPoint = new PromptDecisionPoint(vertx, delayMs);
            return new Fixture(vertx, toolCount, deadlineMs, decisionPoint, decisionPoint, null, false);
        }

        static Fixture startWithPromptDecisionPointAndObserver(
                Vertx vertx, int toolCount, long delayMs, long deadlineMs) throws Exception {
            PromptDecisionPoint decisionPoint = new PromptDecisionPoint(vertx, delayMs);
            return new Fixture(vertx, toolCount, deadlineMs, decisionPoint, decisionPoint, null, true);
        }

        static Fixture startWithHoldableDecisionPoint(Vertx vertx, int toolCount, int holdOnCall) throws Exception {
            HoldableDecisionPoint decisionPoint = new HoldableDecisionPoint(holdOnCall);
            return new Fixture(
                    vertx,
                    toolCount,
                    McpServerConfig.defaults().toolsListDeadlineMs(),
                    decisionPoint,
                    null,
                    decisionPoint,
                    true);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        int decisionPointCallCount() {
            return promptDecisionPoint != null ? promptDecisionPoint.callCount() : holdableDecisionPoint.callCount();
        }

        PromptDecisionPoint decisionPoint() {
            return promptDecisionPoint;
        }

        HoldableDecisionPoint holdableDecisionPoint() {
            return holdableDecisionPoint;
        }

        RecordingObserver observer() {
            return observer;
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousOnlyIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }
}
