// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Review-finding proof (round-14 remediation): {@code writeUnknownOrUnauthorized} — the shared
 * {@code -32602} writer for an unresolved {@code tools/call} name, a denied {@code tools/call}, and
 * an invalid {@code tools/list} cursor — must bound its response at {@code mcp.output.maxBytes}
 * exactly like every sibling terminal writer, degrading to the bounded id-less internal error when
 * the client-controlled echoed request id alone would blow the cap.
 *
 * <p>Modeled directly on {@link McpOutputCapIT}: a deliberately tiny {@code outputMaxBytes} and a
 * request id whose length alone exceeds it. All three rows reach the exact same writer, so one
 * shared fixture (one {@code DENY_ALL} tool, no {@code PERMIT_ALL} tool) proves it for the whole
 * indistinguishable {@code -32602} family in one pass.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpUnknownOrUnauthorizedOutputCapIT {

    private static final String UNKNOWN_TOOL_ROW = "shouldCapUnknownToolResponseWithHugeId";
    private static final String DENIED_TOOL_ROW = "shouldCapDeniedToolResponseWithHugeId";
    private static final String INVALID_CURSOR_ROW = "shouldCapInvalidCursorResponseWithHugeId";

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String DENIED_TOOL = "cap.deniedTool";
    private static final String UNKNOWN_TOOL = "cap.doesNotExist";

    /** The minimum permitted output cap, below the size of the echoed huge id. */
    private static final int OUTPUT_MAX_BYTES = 1_024;

    /** A string id well above the cap yet under {@code jsonMaxStringChars}; only its length matters. */
    private static final String HUGE_ID = "x".repeat(4_000);

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> rows() {
        return Stream.of(UNKNOWN_TOOL_ROW, DENIED_TOOL_ROW, INVALID_CURSOR_ROW);
    }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("writeUnknownOrUnauthorized bounds its response at mcp.output.maxBytes for a huge echoed id")
    void shouldCapEveryPathThroughWriteUnknownOrUnauthorized(String row) throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        switch (row) {
            case UNKNOWN_TOOL_ROW -> shouldCapUnknownToolResponseWithHugeId();
            case DENIED_TOOL_ROW -> shouldCapDeniedToolResponseWithHugeId();
            case INVALID_CURSOR_ROW -> shouldCapInvalidCursorResponseWithHugeId();
            default -> fail("unknown row: " + row);
        }
    }

    // --- Row 1: an unresolved tools/call name ---

    private void shouldCapUnknownToolResponseWithHugeId() throws Exception {
        HttpResponse<Buffer> response = await(callTool(UNKNOWN_TOOL, HUGE_ID));
        assertCappedInternalErrorDegrade(response);
    }

    // --- Row 2: a resolved, denied tools/call ---

    private void shouldCapDeniedToolResponseWithHugeId() throws Exception {
        HttpResponse<Buffer> response = await(callTool(DENIED_TOOL, HUGE_ID));
        assertCappedInternalErrorDegrade(response);
    }

    // --- Row 3: an invalid tools/list cursor ---

    private void shouldCapInvalidCursorResponseWithHugeId() throws Exception {
        HttpResponse<Buffer> response = await(listTools("not-a-valid-cursor", HUGE_ID));
        assertCappedInternalErrorDegrade(response);
    }

    /**
     * DECISIVE: pre-fix, {@code writeUnknownOrUnauthorized} called the unbounded {@code codec.encode}
     * and would echo the full 4,000-byte id, blowing past the 1,024-byte cap this fixture configures.
     * Post-fix it degrades to the bounded, id-less internal error every sibling terminal writer already
     * degrades to.
     */
    private static void assertCappedInternalErrorDegrade(HttpResponse<Buffer> response) {
        assertThat(response.body().length())
                .as("an over-cap -32602 response must be degraded to stay within mcp.output.maxBytes")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);
        JsonObject body = new JsonObject(response.bodyAsString());
        assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
        assertThat(body.getValue("id"))
                .as("the degraded error drops the unbounded id to stay under the cap")
                .isNull();
        assertThat(body.getJsonObject("error").getInteger("code"))
                .as("the degraded error is a bounded internal error, not the original -32602")
                .isEqualTo(-32603);
        assertThat(response.statusCode())
                .as("the degraded internal error carries the matching HTTP 500 status")
                .isEqualTo(500);
    }

    private Future<HttpResponse<Buffer>> callTool(String toolName, String id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("name", toolName);
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName)
                .sendBuffer(body.toBuffer());
    }

    private Future<HttpResponse<Buffer>> listTools(String cursor, String id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("cursor", cursor);
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/list")
                .put("params", params);
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(body.toBuffer());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /** A zero-argument {@code DENY_ALL} tool, invoked by no row in this matrix. */
    private static final class DeniedToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        DeniedToolInvoker(McpToolDescriptor descriptor) {
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
                    throw new AssertionError("no row in this matrix invokes a tool");
                }
            };
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

    /** One real port-0 mount, a tiny {@code outputMaxBytes}, and one {@code DENY_ALL} tool. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .outputMaxBytes(OUTPUT_MAX_BYTES)
                    .build();
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    DENIED_TOOL,
                    null,
                    "Output-cap fixture tool, never permitted.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null));
            McpToolRegistry registry = McpToolRegistry.build(Set.of(new DeniedToolInvoker(descriptor)));

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
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
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
