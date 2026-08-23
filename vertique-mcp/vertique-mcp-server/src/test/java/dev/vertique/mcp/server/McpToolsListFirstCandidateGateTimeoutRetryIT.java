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
 * R13 item 2 — {@code shouldRetryTheFirstCandidateOnTheFollowingPageInsteadOfSkippingItForever},
 * retracting R07 item 7's "unavoidable" residual.
 *
 * <p>R07 item 7 fixed every gate-timeout self-anchor <em>except</em> one: a timeout on the very
 * first candidate examined in a cursor-less scan, where {@code scan()} had no previously-examined
 * candidate to fall back to and so still anchored to the timed-out candidate itself — permanently
 * excluding it, exactly the R07 item 7 defect, just narrowed to one specific candidate. R07's
 * evidence called this residual "unavoidable" because "the cursor grammar has no anchor that means
 * before the beginning". That claim was wrong: the cursor is this codec's own opaque wire format, so
 * {@code McpCursorCodec.BEFORE_FIRST_ANCHOR} — a reserved value no real tool name can ever equal —
 * now expresses exactly that, and {@code McpCursorCodec#decode} recognizes it without a
 * registry-membership check.
 *
 * <p>Sibling to {@link McpToolsListGateTimeoutAnchorRetryIT}, which proves the retry for a timeout on
 * the <em>second</em> candidate (where a genuine previous-candidate anchor already existed) — this
 * proof is decisive for the one case that sibling cannot reach: a timeout on the very first candidate
 * of the very first page, where {@code previousAnchor}/{@code currentLastExaminedName} is {@code
 * null}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolsListFirstCandidateGateTimeoutRetryIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SCHEME_NAME = "test-scheme";

    /** Alphabetically first — the very first candidate examined; its gate times out on the first call. */
    private static final String TIMED_OUT_TOOL = "gate.timeout.first00";

    /** Alphabetically second — never reached on the first page, since the first candidate's timeout stops the scan. */
    private static final String SECOND_TOOL = "gate.timeout.first01";

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
    @DisplayName(
            "a gate timeout on the very first candidate is retried on the following page, not " + "permanently skipped")
    void shouldRetryTheFirstCandidateOnTheFollowingPageInsteadOfSkippingItForever() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- First page: the very first candidate examined times out immediately, so nothing is ever
        // permitted and the scan stops there with no previously-examined candidate to anchor to ---
        HttpResponse<Buffer> first = await(listTools(null));
        assertThat(first.statusCode()).isEqualTo(200);
        JsonObject firstResult = new JsonObject(first.bodyAsString()).getJsonObject("result");
        JsonArray firstTools = firstResult.getJsonArray("tools");
        assertThat(toolNames(firstTools))
                .as("nothing was ever examined and permitted before the very first candidate's own timeout")
                .isEmpty();
        String nextCursor = firstResult.getString("nextCursor");
        assertThat(nextCursor)
                .as("candidates remain unexamined after the timeout, so a cursor must still be returned — "
                        + "this must never silently disappear, R07's own module.md contract")
                .isNotNull();

        // --- Second page, using the returned cursor: the gate now resolves for every candidate ---
        HttpResponse<Buffer> second = await(listTools(nextCursor));
        assertThat(second.statusCode()).isEqualTo(200);
        JsonObject secondResult = new JsonObject(second.bodyAsString()).getJsonObject("result");
        JsonArray secondTools = secondResult.getJsonArray("tools");

        // DECISIVE (R13 item 2, retracting R07 item 7's "unavoidable" wording): the very first
        // candidate, whose gate timed out with no previous-candidate anchor available, must be
        // re-examined — not permanently excluded — once the client follows the returned cursor. Before
        // this fix, the cursor's anchor would have been the timed-out candidate itself, and because the
        // anchor is exclusive, this candidate could never be reached by any future page.
        assertThat(toolNames(secondTools))
                .as("DECISIVE: the timed-out first candidate must be retried, not permanently skipped")
                .contains(TIMED_OUT_TOOL);
        assertThat(toolNames(secondTools))
                .as("scanning also proceeds past the retried first candidate to the next one")
                .contains(SECOND_TOOL);

        assertThat(fixture.decisionPointCallCount())
                .as("exactly 1 call on the first page (the first candidate's timeout) plus one call per "
                        + "candidate on the second page (the retried first candidate, then the second) — "
                        + "never a second timeout")
                .isEqualTo(3);
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
     * An {@link AuthorizationDecisionPoint} whose very first call (the scan's first candidate) never
     * completes; every later call — including the retried first candidate on the second page — permits
     * immediately.
     */
    private static final class TimeoutOnFirstCallDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            int call = callCount.incrementAndGet();
            if (call == 1) {
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

    /** One real port-0 mount, two {@code Constrained} tools, and one once-hanging gate on the first call. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final TimeoutOnFirstCallDecisionPoint decisionPoint;

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
                        "R13 item 2 first-candidate anchor-retry fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\"}",
                        null,
                        new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
                invokers.add(new RestrictedToolInvoker(descriptor));
            }
            McpToolRegistry registry = McpToolRegistry.build(invokers);

            this.decisionPoint = new TimeoutOnFirstCallDecisionPoint();
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
