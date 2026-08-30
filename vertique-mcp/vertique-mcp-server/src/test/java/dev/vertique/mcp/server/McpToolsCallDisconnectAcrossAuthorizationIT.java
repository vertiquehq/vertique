// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
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
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R48 (C1) — red-first, real-transport proof that the {@code tools/call} async-stage chain guards
 * every seam against post-settlement work, not merely the single {@code isCancelled()} check
 * currently placed just ahead of {@code prepared.invoke()} inside {@code invokeAndRespond}.
 *
 * <p>Gates the authorization decision for a RESTRICTED tool (the established technique {@code
 * McpToolsListDisconnectIT} already uses on the {@code tools/list} path, reused here on {@code
 * tools/call}: a custom {@link AuthorizationDecisionPoint} whose {@code decide()} future this test
 * holds open), disconnects the client while that decision is pending, awaits the disconnect
 * settlement deterministically (terminal then completion observed), and only then releases the held
 * decision with a permit — exactly like a slow remote PDP answering after the caller already left.
 *
 * <p>Before this repair, {@code invokeAndRespond} composes SSE selection, the full stage-1..4 input
 * pipeline (schema validation, {@code prepare()}), the opt-in {@code onToolInput} value observation
 * ({@code publishToolInput}), and the ordered tool-interceptor chain — all of it — strictly before
 * its own first {@code cancellation.isCancelled()} check, so every one of those side effects runs for
 * a request the settlement path already published terminal and completion for. This proof asserts
 * zero of them ever happen, and — the seam-order pin (R48 test proof item 2) — that {@code
 * onCompleted} remains the last callback the lifecycle observer ever receives, exactly as {@code
 * McpToolsListDisconnectIT}/{@code McpDisconnectBeforeInvocationIT} already pin for their own seams.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolsCallDisconnectAcrossAuthorizationIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String TOOL_NAME = "disconnect.across.authorization";
    private static final String SCHEME_NAME = "test-scheme";
    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /**
     * The bounded confirmation window checked only after the request-owning context has already been
     * deterministically drained (see {@link #drainRequestContext}) — never the sole synchronization
     * for a negative assertion, only a safety margin for any legitimately asynchronous continuation a
     * fix might still introduce. Mirrors {@code McpDisconnectBeforeInvocationIT}'s identical idiom.
     */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofMillis(500);

    private final Vertx vertx = Vertx.vertx();
    private Fixture fixture;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
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
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("R48 C1: a tools/call whose authorization decision settles after the client already "
            + "disconnected must produce zero downstream work, and onCompleted must stay the final "
            + "callback (RED today at multiple seams)")
    void shouldProduceNoDownstreamWorkAndKeepOnCompletedLastWhenAuthorizationSettlesAfterDisconnect() throws Exception {
        fixture = Fixture.start(vertx);
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // Fire-and-forget: the connection is closed out from under this request before any response
        // could ever arrive, so the send future itself is never observed.
        client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody())
                .onComplete(ignored -> {});

        assertThat(fixture.decisionPoint().awaitHeld()).isTrue();
        await(rawClient.close());

        assertThat(fixture.observer().awaitSettlement())
                .as("the server must settle the disconnected request while the authorization decision "
                        + "is still pending")
                .isTrue();
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();
        McpRequestCompletedEvent settled = fixture.observer().completed();
        assertThat(settled.responseCommitted())
                .as("the pending authorization decision prevented any response write before disconnect " + "settlement")
                .isFalse();

        // Only now — strictly after settlement was already observed — does the decision resolve,
        // exactly as an eventually-slow remote PDP would answer.
        fixture.decisionPoint().releaseWithPermit();
        drainRequestContext(fixture.observer().expectedContext());

        // DECISIVE: no seam past the (still-pending, at disconnect time) authorization decision may
        // ever run once the late permit finally arrives.
        assertThat(fixture.tool().prepareInvokedWithin(CONFIRMATION_WINDOW))
                .as("DECISIVE: the input pipeline (prepare(), stage 1-4 including the Bean Validation "
                        + "carrier) must never run once the client disconnected before authorization "
                        + "settled")
                .isFalse();
        assertThat(fixture.observer().toolInputCount())
                .as("DECISIVE: onToolInput must never be observed for a request that already settled "
                        + "before authorization resolved")
                .isZero();
        assertThat(fixture.toolInterceptor().invokedWithin(CONFIRMATION_WINDOW))
                .as("DECISIVE: the tool-interceptor chain must never run for an already-settled request")
                .isFalse();
        assertThat(fixture.tool().invokedWithin(CONFIRMATION_WINDOW))
                .as("DECISIVE: the tool body must never execute for an already-settled request")
                .isFalse();

        // R48 test proof item 2 — the seam-order pin, a distinct decisive assertion so a mutation that
        // removes only the guard at this seam (or only the invokeAndRespond entry guard) can be
        // targeted precisely: no callback of any kind may arrive after onCompleted.
        fixture.observer().assertOnCompletedIsTheFinalCallback();
        fixture.observer().assertExactlyOneTerminalThenOneCompletion(); // no further settlement was ever produced
    }

    /**
     * Deterministically drains the request-owning Vert.x context: a marker task queued on it only
     * resolves once every task the decision's release could have queued ahead of it — including any
     * on-context redispatch a fix might introduce — has itself already run. Mirrors {@code
     * McpDisconnectBeforeInvocationIT#drainRequestContext}.
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

    /**
     * Gates the role/scope authorization decision behind a promise this test controls, releasing it
     * with a permit. Mirrors {@code McpToolsListDisconnectIT.HeldDecisionPoint}, simplified: {@code
     * tools/call} issues exactly one {@code decide()} per request, so there is no second-candidate
     * scan to guard against.
     */
    private static final class HeldDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger calls = new AtomicInteger();
        private final CompletableFuture<Void> held = new CompletableFuture<>();
        private volatile Promise<AuthorizationDecision> promise;

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            calls.incrementAndGet();
            promise = Promise.promise();
            held.complete(null);
            return promise.future();
        }

        private boolean awaitHeld() throws Exception {
            try {
                held.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return true;
            } catch (TimeoutException timedOut) {
                return false;
            }
        }

        private void releaseWithPermit() {
            promise.complete(AuthorizationDecision.permit("PERMITTED"));
        }
    }

    /** A tool-interceptor that records whether it was ever invoked; permits immediately when it is. */
    private static final class RecordingToolInterceptor implements McpToolInterceptor {
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();

        @Override
        public Future<Void> beforeInvocation(McpToolInvocationContext context) {
            invoked.complete(null);
            return Future.succeededFuture();
        }

        private boolean invokedWithin(Duration window) throws Exception {
            try {
                invoked.get(window.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException neverInvoked) {
                return false;
            }
        }
    }

    /** A {@code RESTRICTED} tool whose {@code prepare()} and handler body must never run once settled. */
    private static final class RecordingToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R48 C1 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
        private final CompletableFuture<Void> prepareInvoked = new CompletableFuture<>();
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            prepareInvoked.complete(null);
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invoked.complete(null);
                    return Future.succeededFuture(McpToolResult.text("must never be observed"));
                }
            };
        }

        private boolean prepareInvokedWithin(Duration window) throws Exception {
            try {
                prepareInvoked.get(window.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException neverInvoked) {
                return false;
            }
        }

        private boolean invokedWithin(Duration window) throws Exception {
            try {
                invoked.get(window.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException neverInvoked) {
                return false;
            }
        }
    }

    /**
     * Records the request-owning context, the settlement order, the completed event's transport
     * facts, and — because this session also implements {@link McpToolValueObservation} — any {@code
     * onToolInput} delivery in the very same order list, so the seam-order pin can assert {@code
     * onCompleted} is genuinely the last callback of any kind, not merely the last of the two plain
     * {@link McpRequestObservation} callbacks.
     */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpToolValueObservation {
        private volatile Context expectedContext;
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completedCount = new AtomicInteger();
        private final AtomicInteger toolInputCount = new AtomicInteger();
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
            completedCount.incrementAndGet();
            completed = event;
            order.add("completed");
            settlement.countDown();
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            toolInputCount.incrementAndGet();
            order.add("toolInput");
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

        private int toolInputCount() {
            return toolInputCount.get();
        }

        private void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount.get()).isOne();
            assertThat(completedCount.get()).isOne();
        }

        /** The seam-order pin (R48 test proof item 2): {@code onCompleted} is the final callback. */
        private void assertOnCompletedIsTheFinalCallback() {
            assertThat(order)
                    .as(
                            "DECISIVE: no lifecycle callback of any kind (terminal, completion, or a tool "
                                    + "value observation) may arrive after onCompleted — full recorded order: %s",
                            order)
                    .isNotEmpty()
                    .last()
                    .isEqualTo("completed");
        }
    }

    /**
     * A permissive optional auth handler for {@link #SCHEME_NAME}: {@link McpServerConfigValidator}
     * requires an authentication scheme for a mount publishing any RESTRICTED tool, but this proof's
     * caller identity is irrelevant — {@link HeldDecisionPoint} replaces real role/scope evaluation
     * entirely — so both handlers simply admit every request, exactly like {@code
     * McpToolsListDisconnectIT}'s identical fixture.
     */
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
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    /** One real port-0 mount, one RESTRICTED tool, and the held authorization decision under proof. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final HeldDecisionPoint decisionPoint = new HeldDecisionPoint();
        private final RecordingToolInterceptor toolInterceptor = new RecordingToolInterceptor();
        private final RecordingToolInvoker tool = new RecordingToolInvoker();
        private final RecordingObserver observer = new RecordingObserver();

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));
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
                            Set.of(observer),
                            Set.of(),
                            Set.of(),
                            Set.of(toolInterceptor),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(new OptionalRouteAuthHandler()),
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

        HeldDecisionPoint decisionPoint() {
            return decisionPoint;
        }

        RecordingToolInterceptor toolInterceptor() {
            return toolInterceptor;
        }

        RecordingToolInvoker tool() {
            return tool;
        }

        RecordingObserver observer() {
            return observer;
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
