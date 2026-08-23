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
import io.vertx.core.json.JsonArray;
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
 * R07 item 7 (security review, post-R01) —
 * {@code shouldRetryTheTimedOutCandidateOnTheFollowingPageInsteadOfSkippingItForever}.
 *
 * <p>Before this fix, {@code McpRequestDispatcher#scan} anchored a gate-timeout page break to the
 * <em>timed-out candidate itself</em>. Because {@code McpCursorCodec} decodes a page's start index as
 * {@code indexOf(anchor) + 1} (exclusive), that candidate could never be re-examined on any subsequent
 * page — fail-closed (denying it for this request) is correct, but permanently excluding it from every
 * future {@code tools/list} call is not.
 *
 * <p>Sibling to {@link McpToolsListGateTimeoutTerminatesScanIT} (which proves the scan stops after
 * exactly one timeout, not one per remaining candidate) but decisive for a different fact: this proof
 * drives a <em>second</em> {@code tools/list} call using the {@code nextCursor} the first response
 * returned, and asserts the previously timed-out tool is present in that second page's {@code tools}
 * array — the only assertion that can distinguish "retried" from "silently and permanently skipped",
 * which neither a response-status check nor an invocation-count check (as
 * {@code McpToolsListGateTimeoutTerminatesScanIT} already covers) can tell apart.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolsListGateTimeoutAnchorRetryIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SCHEME_NAME = "test-scheme";

    /** Alphabetically first — permitted immediately, establishing a non-null previous-candidate anchor. */
    private static final String PERMITTED_FIRST_TOOL = "gate.timeout.tool00";

    /** Alphabetically second — the candidate whose gate times out on the first request. */
    private static final String TIMED_OUT_TOOL = "gate.timeout.tool01";

    private static final String THIRD_TOOL = "gate.timeout.tool02";

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
    @DisplayName("a gate-timed-out candidate is retried on the following page, not permanently skipped")
    void shouldRetryTheTimedOutCandidateOnTheFollowingPageInsteadOfSkippingItForever() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- First page: tool00 permits, tool01's gate times out, the scan stops there ---
        HttpResponse<Buffer> first = await(listTools(null));
        assertThat(first.statusCode()).isEqualTo(200);
        JsonObject firstResult = new JsonObject(first.bodyAsString()).getJsonObject("result");
        JsonArray firstTools = firstResult.getJsonArray("tools");
        assertThat(toolNames(firstTools))
                .as("the first page must show the permitted candidate examined before the timeout")
                .containsExactly(PERMITTED_FIRST_TOOL);
        String nextCursor = firstResult.getString("nextCursor");
        assertThat(nextCursor)
                .as("candidates remain unexamined after the timeout, so a cursor must be returned")
                .isNotNull();

        // --- Second page, using the returned cursor: the gate now resolves for every candidate ---
        HttpResponse<Buffer> second = await(listTools(nextCursor));
        assertThat(second.statusCode()).isEqualTo(200);
        JsonObject secondResult = new JsonObject(second.bodyAsString()).getJsonObject("result");
        JsonArray secondTools = secondResult.getJsonArray("tools");

        // DECISIVE: the tool whose gate timed out on the first page must be examined again — not
        // permanently excluded — once the client follows the returned cursor.
        assertThat(toolNames(secondTools))
                .as("DECISIVE: the previously timed-out candidate must be retried, not permanently skipped")
                .contains(TIMED_OUT_TOOL);
        assertThat(toolNames(secondTools))
                .as("scanning also proceeds past the previously timed-out candidate to the next one")
                .contains(THIRD_TOOL);

        assertThat(fixture.decisionPointCallCount())
                .as("exactly 2 calls on the first page (tool00 permit, tool01 timeout) plus one call per "
                        + "remaining candidate on the second page (tool01, tool02) — never a second timeout")
                .isEqualTo(4);
    }

    private static List<String> toolNames(JsonArray tools) {
        return tools.stream()
                .map(JsonObject.class::cast)
                .map(tool -> tool.getString("name"))
                .toList();
    }

    private Future<HttpResponse<Buffer>> listTools(String cursor) {
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
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

    /**
     * An {@link AuthorizationDecisionPoint} whose second call (across the whole test — the {@code
     * TIMED_OUT_TOOL} candidate on the first page) never completes; every other call permits
     * immediately, including a later re-examination of that same candidate on the second page.
     */
    private static final class TimeoutOnSecondCallDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            int call = callCount.incrementAndGet();
            if (call == 2) {
                return Promise.<AuthorizationDecision>promise().future(); // never completed
            }
            return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
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

    /** One real port-0 mount, three {@code Constrained} tools, and one once-hanging gate. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final TimeoutOnSecondCallDecisionPoint decisionPoint;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            Set<RouteAuthHandler> routeAuthHandlers = Set.of(new OptionalRouteAuthHandler());

            Set<McpToolInvoker> invokers = new HashSet<>();
            for (String name : List.of(PERMITTED_FIRST_TOOL, TIMED_OUT_TOOL, THIRD_TOOL)) {
                McpToolDescriptor descriptor = new McpToolDescriptor(
                        name,
                        null,
                        "R07 item 7 anchor-retry fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\"}",
                        null,
                        new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
                invokers.add(new RestrictedToolInvoker(descriptor));
            }
            McpToolRegistry registry = McpToolRegistry.build(invokers);

            this.decisionPoint = new TimeoutOnSecondCallDecisionPoint();
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
