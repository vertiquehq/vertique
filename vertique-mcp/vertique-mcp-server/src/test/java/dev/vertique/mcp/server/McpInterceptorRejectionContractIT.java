// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpResultType;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * P04 remediation proof for issues W2 (terminal-event classification) and W4 (the interceptor-
 * rejection writer's output cap), driven through a real port-0 HTTP round trip so the completion
 * coordinator — and therefore lifecycle observation — is genuinely exercised (mock-driven {@code
 * dispatch()} calls bypass {@code begin()} and never construct a coordinator, so they cannot observe
 * a terminal event at all).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpInterceptorRejectionContractIT {

    private static final String TOOL_INTERCEPTOR_REJECTION_ROW =
            "shouldClassifyAToolInterceptorRejectionAsRejectedInterceptorNone";
    private static final String REQUEST_INTERCEPTOR_OVER_CAP_ROW = "shouldBoundAnOverCapRequestInterceptorRejection";

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String TOOL_NAME = "interceptor.rejection.tool";
    private static final int SMALL_OUTPUT_MAX_BYTES = 1_024;

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> rows() {
        return Stream.of(TOOL_INTERCEPTOR_REJECTION_ROW, REQUEST_INTERCEPTOR_OVER_CAP_ROW);
    }

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("interceptor-rejection classification and output-cap contract")
    void shouldEnforceInterceptorRejectionContract(String row) throws Exception {
        switch (row) {
            case TOOL_INTERCEPTOR_REJECTION_ROW -> shouldClassifyAToolInterceptorRejectionAsRejectedInterceptorNone();
            case REQUEST_INTERCEPTOR_OVER_CAP_ROW -> shouldBoundAnOverCapRequestInterceptorRejection();
            default -> fail("unknown row: " + row);
        }
    }

    // --- Row 1 (W2): a tool-interceptor rejection settles as REJECTED/INTERCEPTOR/NONE ---

    private void shouldClassifyAToolInterceptorRejectionAsRejectedInterceptorNone() throws Exception {
        fixture = Fixture.start(vertx, SMALL_OUTPUT_MAX_BYTES, Set.of(), Set.of(new RejectingToolInterceptor()));
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        assertThat(response.statusCode())
                .as("a tool-interceptor rejection still settles as SSE-framed HTTP 200 — SSE was already "
                        + "selected before this stage runs")
                .isEqualTo(200);
        String rawBody = response.bodyAsString();
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject decoded =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject result = decoded.getJsonObject("result");
        assertThat(result.getBoolean("isError")).isTrue();
        assertThat(result.getJsonArray("content").getJsonObject(0).getString("text"))
                .isEqualTo("Tool call rejected");

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome())
                .as("DECISIVE: no handler ever ran, so the outcome must be REJECTED, never TOOL_ERROR")
                .isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType())
                .as("DECISIVE: must classify as INTERCEPTOR, never HANDLER")
                .isEqualTo(McpErrorType.INTERCEPTOR);
        assertThat(terminal.resultType())
                .as("DECISIVE: REJECTED carries no result — resultType must be NONE, never COMPLETE")
                .isEqualTo(McpResultType.NONE);
    }

    // --- Row 2 (W4): a request-interceptor rejection whose echoed id would exceed the cap degrades ---

    private void shouldBoundAnOverCapRequestInterceptorRejection() throws Exception {
        fixture = Fixture.start(vertx, SMALL_OUTPUT_MAX_BYTES, Set.of(new RejectingRequestInterceptor()), Set.of());
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // A client-controlled string id far larger than the configured 1024-byte output cap.
        String hugeId = "x".repeat(SMALL_OUTPUT_MAX_BYTES * 4);
        HttpResponse<Buffer> response = await(callToolWithId(hugeId));

        assertThat(response.body().length())
                .as("DECISIVE (W4): the interceptor-rejection response must respect mcp.output.maxBytes even "
                        + "when the client-controlled echoed id alone would exceed it")
                .isLessThanOrEqualTo(SMALL_OUTPUT_MAX_BYTES);
        assertThat(response.bodyAsString())
                .as("the oversized id must never appear in the degraded response")
                .doesNotContain(hugeId);
        JsonObject body = new JsonObject(response.bodyAsString());
        assertThat(body.getValue("id"))
                .as("the degraded fallback must be id-less")
                .isNull();
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(Object id) {
        return callToolWithId(String.valueOf(id));
    }

    private Future<HttpResponse<Buffer>> callToolWithId(String id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", TOOL_NAME).put("arguments", new JsonObject());
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(body.toBuffer());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A tool-interceptor that always rejects, carrying no reason. */
    private static final class RejectingToolInterceptor implements McpToolInterceptor {
        @Override
        public Future<Void> beforeInvocation(dev.vertique.mcp.interceptor.McpToolInvocationContext context) {
            return Future.failedFuture(new RuntimeException("rejected by fixture"));
        }
    }

    /** A request-interceptor that always rejects, carrying no reason. */
    private static final class RejectingRequestInterceptor implements McpRequestInterceptor {
        @Override
        public Future<Void> beforeRequest(dev.vertique.mcp.interceptor.McpRequestContext context) {
            return Future.failedFuture(new RuntimeException("rejected by fixture"));
        }
    }

    /** A zero-argument tool the interceptor rows never actually invoke. */
    private static final class NeverInvokedToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        NeverInvokedToolInvoker(McpToolDescriptor descriptor) {
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
                    throw new AssertionError("an interceptor-rejected call must never reach invoke()");
                }
            };
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence; never exercised by these rows. */
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

    /** Framework wiring: one real port-0 mount, one PermitAll tool, and a terminal-recording observer. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();

        private Fixture(
                Vertx vertx,
                int outputMaxBytes,
                Set<McpRequestInterceptor> requestInterceptors,
                Set<McpToolInterceptor> toolInterceptors)
                throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .outputMaxBytes(outputMaxBytes)
                    .build();
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "P04 interceptor-rejection fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            McpToolRegistry registry = McpToolRegistry.build(Set.of(new NeverInvokedToolInvoker(descriptor)));

            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
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
                            Set.of(recordingObserver()),
                            Set.of(),
                            requestInterceptors,
                            toolInterceptors,
                            httpConfig,
                            registry,
                            policyEnforcer),
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

        static Fixture start(
                Vertx vertx,
                int outputMaxBytes,
                Set<McpRequestInterceptor> requestInterceptors,
                Set<McpToolInterceptor> toolInterceptors)
                throws Exception {
            return new Fixture(vertx, outputMaxBytes, requestInterceptors, toolInterceptors);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        List<McpRequestTerminalEvent> terminals() {
            return terminals;
        }

        private McpRequestLifecycleObserver recordingObserver() {
            return startedAt -> new McpRequestObservation() {
                @Override
                public void onTerminal(McpRequestTerminalObservation observation) {
                    terminals.add(observation.event());
                }
            };
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
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
