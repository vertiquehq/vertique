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
 * R14 item 2 — {@code shouldStopAdvertisingACursorOnceTwoPagesMadeNoProgress}: pagination must
 * guarantee forward progress, not merely a cursor.
 *
 * <p>R13 item 2 removed the last self-anchoring gate-timeout stop, which fixed a permanently-skipped
 * candidate but replaced a defect that still <em>advanced</em> with one that does not advance at all:
 * a scan that stops before examining its first candidate now returns an empty {@code tools} array
 * plus a {@code nextCursor} resolving to the exact state the request started from. The module
 * contract instructs clients that an empty page carrying a cursor must be followed, so against a
 * persistently slow or unavailable decision point — precisely the condition the gate deadline exists
 * for — a conforming client loops forever, each iteration holding the server for a full gate deadline
 * and returning nothing. R10's request-scoped deadline cannot bound it: the loop spans requests.
 *
 * <p>{@link McpToolsListFirstCandidateGateTimeoutRetryIT}, the R13 proof, times out exactly <b>once</b>
 * and then permits everything, which is why this was invisible: with the timeout gone by page 2, that
 * proof never observes a second non-progressing page. This one keeps the stopping condition in place
 * across both pages, which is the shape a real outage has.
 *
 * <p>Both rows drive the same fixture, so the sensitivity claim is internal to this file: the first
 * page must still return a cursor (removing the retry would resurrect R07/R13's permanently-skipped
 * candidate), and the second must not (that is the bound). Each page costs one 5s gate deadline.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpToolsListNonProgressingPaginationIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SCHEME_NAME = "test-scheme";

    /** Alphabetically first — the only candidate either page ever reaches; its gate never answers. */
    private static final String TIMED_OUT_TOOL = "nonprogress.first00";

    /** Alphabetically second — never reached on either page, since the first candidate's timeout stops the scan. */
    private static final String SECOND_TOOL = "nonprogress.first01";

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
    @DisplayName("R14 item 2: a second consecutive page that does not advance carries no nextCursor")
    void shouldStopAdvertisingACursorOnceTwoPagesMadeNoProgress() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- Page 1: the very first candidate's gate times out, so nothing is examined at all ---
        HttpResponse<Buffer> first = await(listTools(null));
        assertThat(first.statusCode()).isEqualTo(200);
        JsonObject firstResult = new JsonObject(first.bodyAsString()).getJsonObject("result");
        assertThat(toolNames(firstResult.getJsonArray("tools")))
                .as("the first candidate's gate never answered, so no tool was permitted")
                .isEmpty();
        String firstCursor = firstResult.getString("nextCursor");
        assertThat(firstCursor)
                .as("the first non-progressing page must still return a cursor — dropping it here would "
                        + "resurrect R07/R13's permanently-skipped first candidate, which is a legitimate "
                        + "retry, not a loop")
                .isNotNull();

        // --- Page 2, following that cursor: the backend is still down, so again nothing is examined ---
        HttpResponse<Buffer> second = await(listTools(firstCursor));
        assertThat(second.statusCode()).isEqualTo(200);
        JsonObject secondResult = new JsonObject(second.bodyAsString()).getJsonObject("result");
        assertThat(toolNames(secondResult.getJsonArray("tools")))
                .as("the second page permitted nothing either — the stopping condition persists")
                .isEmpty();

        // DECISIVE (R14 item 2): the client must be told to stop. Before this fix the second page
        // returned another cursor resolving to the same starting state, and every following page would
        // too — an unbounded loop at one gate deadline per request, with the response body identical
        // every time. This is the only assertion in the suite that distinguishes "makes progress" from
        // "returns a cursor", and it is unreachable by any proof that times out only once.
        assertThat(secondResult.getString("nextCursor"))
                .as("DECISIVE: a second consecutive page that handed back its own resume point must end "
                        + "the cursor chain, so a conforming client stops instead of looping forever")
                .isNull();
        assertThat(secondResult.containsKey("nextCursor"))
                .as("the key must be absent entirely, not present and null — a client checking for the "
                        + "member's presence must see none")
                .isFalse();

        assertThat(fixture.decisionPointCallCount())
                .as("exactly one hanging decision per page: the scan stops at the first candidate's gate "
                        + "timeout and never amplifies across the remaining candidates")
                .isEqualTo(2);
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
     * An {@link AuthorizationDecisionPoint} that never completes any decision — the persistently slow or
     * unavailable policy backend the shared gate deadline exists for, and the condition under which a
     * self-anchoring cursor makes no forward progress on any page.
     */
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

    /** One real port-0 mount, two {@code Constrained} tools, and one once-hanging gate on the first call. */
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
            for (String name : List.of(TIMED_OUT_TOOL, SECOND_TOOL)) {
                McpToolDescriptor descriptor = new McpToolDescriptor(
                        name,
                        null,
                        "R14 item 2 non-progressing-pagination fixture tool.",
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
