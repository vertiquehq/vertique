// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.HashSet;
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

/**
 * R01 TP-001 — the {@code tools/list} scan-termination half (issue #417).
 *
 * <p>{@code SecurityPolicyEnforcer#decide} now bounds a hanging {@link AuthorizationDecisionPoint}
 * with {@link SecurityPolicyEnforcer#DEFAULT_GATE_DEADLINE_MS}, so a {@code tools/list} scan no longer
 * hangs forever on one bad candidate. But a per-candidate deadline is itself an amplification risk on
 * the budget (up to 2,000 candidates, issue #416): this proof registers several {@link
 * McpAccessMode#RESTRICTED} candidates behind one decision point that never completes, and asserts the
 * decision point is invoked <strong>exactly once</strong> across the whole scan — not once per
 * candidate — proving {@code McpRequestDispatcher#scan} stops at the first timeout instead of paying
 * the deadline again for every remaining one.
 *
 * <p>Uses the production {@link SecurityPolicyEnforcer#DEFAULT_GATE_DEADLINE_MS} default (the
 * package-private small-deadline test seam lives in {@code vertique-rest-security} and is not visible
 * across the module boundary), so this proof pays the deadline once — about five real seconds — rather
 * than {@code candidateCount} times. The decisive assertion is the invocation count, per R01's standing
 * requirement, not the elapsed time.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class McpToolsListGateTimeoutTerminatesScanIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    /** More than one candidate, so a per-candidate deadline would be observable as amplification. */
    private static final int TOOL_COUNT = 5;

    /**
     * The configured authentication scheme, with a matching optional-capable {@link RouteAuthHandler}
     * always installed, so the pre-existing registry-visibility rule (§4.5) — which would otherwise
     * reject any {@link McpAccessMode#RESTRICTED} tool under an unconfigured scheme — never fires here
     * and does not stand in for the one concern this proof exists to test.
     */
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

    @Test
    @DisplayName("tools/list must stop scanning after the first gate timeout, not pay the deadline per candidate")
    void shouldTerminateTheScanAfterOneGateTimeout() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(listToolsBody()));

        assertThat(response.statusCode())
                .as("a fail-closed deny is not a transport error")
                .isEqualTo(200);
        JsonObject result = new JsonObject(response.bodyAsString()).getJsonObject("result");
        assertThat(result.getJsonArray("tools"))
                .as("the one examined candidate's gate timed out and denied")
                .isEmpty();
        assertThat(result.getString("nextCursor"))
                .as("only one of " + TOOL_COUNT + " candidates was examined, so candidates remain")
                .isNotNull();
        assertThat(fixture.decisionPointCallCount())
                .as("DECISIVE: the hanging decision point must be invoked exactly once across the whole "
                        + "scan, not once per remaining candidate — this is the assertion that would fail "
                        + "(by never returning within this test's @Timeout) if the per-candidate "
                        + "amplification regressed")
                .isEqualTo(1);
    }

    private static Buffer listToolsBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", params)
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /** A {@code Constrained}, zero-argument tool, never invoked by this proof. */
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

    /** An {@link AuthorizationDecisionPoint} whose returned future never completes, counting calls. */
    private static final class NeverCompletingDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            callCount.incrementAndGet();
            return Promise.<AuthorizationDecision>promise().future(); // never completed
        }

        int callCount() {
            return callCount.get();
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

    /** One real port-0 mount, {@link #TOOL_COUNT} {@code Constrained} tools, and one hanging gate. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final NeverCompletingDecisionPoint decisionPoint;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            Set<RouteAuthHandler> routeAuthHandlers = Set.of(new OptionalRouteAuthHandler());

            Set<McpToolInvoker> invokers = new HashSet<>();
            for (int i = 0; i < TOOL_COUNT; i++) {
                String name = String.format("gate.timeout.tool%02d", i);
                McpToolDescriptor descriptor = new McpToolDescriptor(
                        name,
                        null,
                        "R01 TP-001 scan-termination fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\"}",
                        null,
                        new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
                invokers.add(new RestrictedToolInvoker(descriptor));
            }
            McpToolRegistry registry = McpToolRegistry.build(invokers);

            this.decisionPoint = new NeverCompletingDecisionPoint();
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
                            Set.of(),
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
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
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

        int decisionPointCallCount() {
            return decisionPoint.callCount();
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
