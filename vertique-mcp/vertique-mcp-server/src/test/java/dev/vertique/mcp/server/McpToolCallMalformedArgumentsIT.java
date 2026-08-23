// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Review-finding proof (round-14 remediation): a present, non-null, non-object {@code
 * tools/call.arguments} member ({@code []}, {@code "x"}, {@code 3}) must be rejected as a bounded
 * invalid-arguments tool error, never silently coerced to {@code {}} — the pre-fix behavior, which let
 * a zero-argument tool execute from a schema-invalid call.
 *
 * <p>Every malformed row asserts on the tool's own invocation counter, never only on the response
 * shape, so a regression that answered the right JSON but still ran the handler cannot pass. The
 * control row (absent {@code arguments}) proves that same counter is genuinely live: a zero count
 * nothing ever increments would make the malformed rows' zero-count assertions vacuous.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolCallMalformedArgumentsIT {

    private static final String ARRAY_ARGUMENTS_ROW = "shouldRejectArrayArgumentsWithoutInvoking";
    private static final String STRING_ARGUMENTS_ROW = "shouldRejectStringArgumentsWithoutInvoking";
    private static final String NUMBER_ARGUMENTS_ROW = "shouldRejectNumberArgumentsWithoutInvoking";
    private static final String ABSENT_ARGUMENTS_CONTROL_ROW = "shouldInvokeOnceWithAbsentArguments";

    private static final String REQUEST_PATH = "/mcp/";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String PUBLIC_TOOL = "malformed.publicTool";
    private static final String REJECTION_MESSAGE = "Invalid tool arguments: arguments must be an object";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> rows() {
        return Stream.of(ARRAY_ARGUMENTS_ROW, STRING_ARGUMENTS_ROW, NUMBER_ARGUMENTS_ROW, ABSENT_ARGUMENTS_CONTROL_ROW);
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
    @DisplayName("a present non-object arguments member is rejected, never coerced to {}")
    void shouldRejectNonObjectArgumentsWithoutCoercion(String row) throws Exception {
        startServer();
        switch (row) {
            case ARRAY_ARGUMENTS_ROW -> shouldRejectMalformedArguments("[]");
            case STRING_ARGUMENTS_ROW -> shouldRejectMalformedArguments("\"x\"");
            case NUMBER_ARGUMENTS_ROW -> shouldRejectMalformedArguments("3");
            case ABSENT_ARGUMENTS_CONTROL_ROW -> shouldInvokeOnceWithAbsentArguments();
            default -> fail("unknown row: " + row);
        }
    }

    private void shouldRejectMalformedArguments(String argumentsLiteral) throws Exception {
        HttpResponse<Buffer> response = await(callTool(argumentsLiteral));

        // DECISIVE: the zero-argument tool must never run — a regression that coerced [] / "x" / 3 to
        // {} would execute the tool from a schema-invalid call, exactly the defect this proof catches.
        assertThat(fixture.publicTool().invocationCount())
                .as("a present non-object arguments member must never reach invocation")
                .isZero();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.getHeader("content-type"))
                .as("SSE is already selected before this check runs")
                .isEqualTo("text/event-stream");
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError"))
                .as("a rejected non-object arguments member settles as a bounded tool error")
                .isTrue();
        assertThat(result.getJsonArray("content").getJsonObject(0).getString("text"))
                .isEqualTo(REJECTION_MESSAGE);
    }

    private void shouldInvokeOnceWithAbsentArguments() throws Exception {
        HttpResponse<Buffer> response = await(callTool(null));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError")).isFalse();

        // Non-vacuousness proof: the exact same counter type the malformed rows assert isZero() on
        // genuinely increments for a well-formed call.
        assertThat(fixture.publicTool().invocationCount())
                .as("the control row proves the invocation counter is genuinely live")
                .isEqualTo(1);
    }

    /**
     * Posts one {@code tools/call} for the fixture's public tool. {@code argumentsLiteral} is embedded
     * verbatim as the raw JSON value of the {@code arguments} member ({@code "[]"}, {@code "\"x\""},
     * {@code "3"}), or the member is omitted entirely when {@code argumentsLiteral} is {@code null}.
     */
    private Future<HttpResponse<Buffer>> callTool(String argumentsLiteral) {
        String argumentsMember = argumentsLiteral == null ? "" : ",\"arguments\":" + argumentsLiteral;
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"" + PUBLIC_TOOL + "\""
                + argumentsMember + "}}";
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", PUBLIC_TOOL)
                .sendBuffer(Buffer.buffer(body));
    }

    /** Extracts and parses the JSON payload framed by the frozen {@code event: message}/{@code data:} block. */
    private static JsonObject sseResult(String rawBody) {
        assertThat(rawBody)
                .as("a known, authorized tools/call response must use the frozen SSE framing")
                .startsWith(SSE_PREFIX);
        JsonObject data = new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject result = data.getJsonObject("result");
        assertThat(result).as("a tools/call response must carry a result").isNotNull();
        return result;
    }

    private void startServer() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /** A zero-argument, public tool that counts every genuine invocation. */
    private static final class CountingToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final AtomicInteger invocationCount = new AtomicInteger();

        CountingToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        int invocationCount() {
            return invocationCount.get();
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
                    invocationCount.incrementAndGet();
                    return Future.succeededFuture(McpToolResult.text("ok"));
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

    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final CountingToolInvoker publicTool;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    PUBLIC_TOOL,
                    null,
                    "Malformed-arguments fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            this.publicTool = new CountingToolInvoker(descriptor);
            McpToolRegistry registry = McpToolRegistry.build(Set.of(publicTool));

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
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
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

        CountingToolInvoker publicTool() {
            return publicTool;
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
