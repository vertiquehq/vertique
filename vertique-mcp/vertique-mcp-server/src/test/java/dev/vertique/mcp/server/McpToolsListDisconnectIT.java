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
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Characterizes D008 disconnect handling without claiming that the shared authorization SPI cancels. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpToolsListDisconnectIT {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String SCHEME_NAME = "test-scheme";

    private final Vertx vertx = Vertx.vertx();
    private Fixture fixture;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server.close() : Future.succeededFuture();
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
    }

    @Test
    @DisplayName("schedules no further authorization after a client disconnect")
    void shouldScheduleNoFurtherAuthorizationAfterDisconnect() throws Exception {
        fixture = Fixture.start(vertx);
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // WebClient registers buffered response-body handling as part of sendBuffer before this future
        // is observed; this avoids the raw HttpClient send-to-body-handler race.
        client.post(fixture.port, "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(toolsListBody())
                .onComplete(ignored -> {});

        assertThat(fixture.decisionPoint.awaitHeld()).isTrue();
        await(rawClient.close()); // a real Vert.x client disconnect; its WebClient wrapper owns the request
        assertThat(fixture.observer.awaitSettlement())
                .as("the server must settle the disconnected request before the held decision resolves")
                .isTrue();
        fixture.observer.assertOneTerminalThenOneCompletion();
        McpRequestCompletedEvent settled = fixture.observer.completed();
        assertThat(settled.transportOutcome())
                .as("a peer close may surface as a disconnect or stream reset")
                .isIn(McpTransportOutcome.DISCONNECTED, McpTransportOutcome.RESET);
        assertThat(settled.responseCommitted())
                .as("the held authorization decision prevented any response write before disconnect settlement")
                .isFalse();

        fixture.decisionPoint.releaseWithPermit();
        assertThat(fixture.decisionPoint.awaitLateDecisionHandler()).isTrue();

        assertThat(fixture.decisionPoint.callCount())
                .as("the late first decision handler ran, but must not schedule authorization for the second candidate")
                .isEqualTo(1);
    }

    private static Buffer toolsListBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put(
                        "params",
                        new JsonObject()
                                .put(
                                        "_meta",
                                        new JsonObject()
                                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject())))
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final HeldDecisionPoint decisionPoint;
        private final RecordingObserver observer;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-test")
                    .serverVersion("1.0")
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            decisionPoint = new HeldDecisionPoint();
            observer = new RecordingObserver();
            RecordingSecurityRuntime runtime = new RecordingSecurityRuntime();
            McpToolRegistry registry =
                    McpToolRegistry.build(Set.of(tool("disconnect.tool01"), tool("disconnect.tool02")));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpPolicyEnforcer enforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.of(decisionPoint),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT,
                    runtime,
                    Optional.empty()));
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            runtime,
                            Set.of(observer),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            enforcer,
                            NO_OP_CONTEXT,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(new OptionalRouteAuthHandler()),
                    identityResolution(runtime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            port = server.actualPort();
        }

        static Fixture start(Vertx vertx) throws Exception {
            return new Fixture(vertx);
        }
    }

    private static McpToolInvoker tool(String name) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                name,
                null,
                "disconnect fixture",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
        return new McpToolInvoker() {
            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                throw new AssertionError("tools/list never invokes a tool");
            }
        };
    }

    private static IdentityResolutionMiddleware identityResolution(SecurityRuntime runtime) {
        return new IdentityResolutionMiddleware(
                Set.of(new AnonymousIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                runtime,
                NO_OP_CONTEXT);
    }

    private static final class HeldDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger calls = new AtomicInteger();
        private final CompletableFuture<Void> held = new CompletableFuture<>();
        private final CompletableFuture<Void> lateDecisionHandlerDrained = new CompletableFuture<>();
        private volatile Promise<AuthorizationDecision> promise;
        private volatile Context decisionContext;

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            if (calls.incrementAndGet() > 1) {
                return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
            }
            decisionContext = Vertx.currentContext();
            promise = Promise.promise();
            held.complete(null);
            return promise.future();
        }

        boolean awaitHeld() throws Exception {
            try {
                held.get(10, TimeUnit.SECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException timedOut) {
                return false;
            }
        }

        void releaseWithPermit() {
            promise.complete(AuthorizationDecision.permit("PERMITTED"));
            // Completion first schedules SecurityPolicyEnforcer's continuation on this context. The
            // first marker runs after that work; the second runs after any dispatcher task that first
            // marker's predecessor queued. This gives the late first-decision handler a real chance to
            // start another authorization decision without a wall-clock wait or poll.
            decisionContext.runOnContext(
                    ignored -> decisionContext.runOnContext(ignored2 -> lateDecisionHandlerDrained.complete(null)));
        }

        boolean awaitLateDecisionHandler() throws Exception {
            try {
                lateDecisionHandlerDrained.get(10, TimeUnit.SECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException timedOut) {
                return false;
            }
        }

        int callCount() {
            return calls.get();
        }
    }

    /** Records the server's logical settlement and transport completion as separate ordered facts. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CompletableFuture<Void> settlement = new CompletableFuture<>();
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminals = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private volatile McpRequestCompletedEvent completed;

        @Override
        public McpRequestObservation open(java.time.Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.incrementAndGet();
            order.add("terminal");
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completions.incrementAndGet();
            completed = event;
            order.add("completion");
            settlement.complete(null);
        }

        boolean awaitSettlement() throws Exception {
            try {
                settlement.get(10, TimeUnit.SECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException timedOut) {
                return false;
            }
        }

        void assertOneTerminalThenOneCompletion() {
            assertThat(terminals.get()).isOne();
            assertThat(completions.get()).isOne();
            assertThat(order).containsExactly("terminal", "completion");
        }

        McpRequestCompletedEvent completed() {
            return completed;
        }
    }

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

    private static final ContextHolder NO_OP_CONTEXT = new ContextHolder() {
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
        private volatile SecurityContext current;

        @Override
        public SecurityContext current() {
            return current;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            current = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
