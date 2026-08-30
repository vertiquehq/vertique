// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpErrorType;
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
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves D008's fail-whole behavior when the shared authorization infrastructure cannot decide. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpToolsListAuthorizationInfrastructureFailureIT {

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
    @DisplayName("aborts the whole list on an internal authorization error")
    void shouldAbortTheWholeListOnInternalAuthorizationError() throws Exception {
        fixture = Fixture.start(vertx);
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(client.post(fixture.port, "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(toolsListBody()));

        assertThat(response.statusCode()).isEqualTo(500);
        JsonObject body = new JsonObject(response.bodyAsString());
        assertThat(body.getJsonObject("error").getInteger("code")).isEqualTo(-32603);
        assertThat(body.containsKey("result"))
                .as("a failed list must not expose a partial result")
                .isFalse();
        assertThat(response.getHeader("cache-control"))
                .as("a failed list must not advertise a cacheable page")
                .isNull();
        assertThat(fixture.decisionPoint.callCount())
                .as("the third candidate must not be authorized")
                .isEqualTo(2);

        McpRequestCompletedEvent completed = fixture.observer.awaitCompleted();
        assertThat(completed.terminal().errorType()).isEqualTo(McpErrorType.AUTHORIZATION);
        assertThat(fixture.observer.terminalCount())
                .as("one request has exactly one terminal event")
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
        private final SequenceDecisionPoint decisionPoint;
        private final RecordingObserver observer;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-test")
                    .serverVersion("1.0")
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            decisionPoint = new SequenceDecisionPoint();
            observer = new RecordingObserver();
            RecordingSecurityRuntime runtime = new RecordingSecurityRuntime();
            McpToolRegistry registry = McpToolRegistry.build(
                    Set.of(tool("failure.tool01"), tool("failure.tool02"), tool("failure.tool03")));
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
                "authorization failure fixture",
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

    private static final class SequenceDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            return calls.incrementAndGet() == 2
                    ? Future.failedFuture("simulated authorization infrastructure failure")
                    : Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
        }

        int callCount() {
            return calls.get();
        }
    }

    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CompletableFuture<McpRequestCompletedEvent> completed = new CompletableFuture<>();
        private final AtomicInteger terminals = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.incrementAndGet();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completed.complete(event);
        }

        McpRequestCompletedEvent awaitCompleted() throws Exception {
            return completed.get(10, TimeUnit.SECONDS);
        }

        int terminalCount() {
            return terminals.get();
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
