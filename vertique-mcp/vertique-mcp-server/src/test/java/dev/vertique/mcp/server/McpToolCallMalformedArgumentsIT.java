// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R15 TP-002 — a present non-object {@code tools/call.arguments} member is an official-method-params
 * violation, rejected at the protocol boundary before lookup, authorization, SSE selection, or tool
 * invocation.
 *
 * <p>The fixture exposes the authorization interaction and invocation counter separately. An HTTP 400
 * JSON body alone would not prove ordering: a later authorization or tool result could produce a
 * failure response after the forbidden application stages had already run.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpToolCallMalformedArgumentsIT {

    private static final String REQUEST_PATH = "/mcp/";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String PUBLIC_TOOL = "malformed.publicTool";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<MalformedArgumentsCase> malformedArgumentsCases() {
        return Stream.of(
                new MalformedArgumentsCase("array arguments", "[]"),
                new MalformedArgumentsCase("explicit null arguments", "null"));
    }

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
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
        server = null;
        rawClient = null;
        client = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedArgumentsCases")
    @DisplayName("R15: malformed arguments are JSON invalid params before authorization or SSE")
    void shouldRejectMalformedArgumentsBeforeLookupAuthorizationAndSseSelection(MalformedArgumentsCase argumentsCase)
            throws Exception {
        startServer();
        HttpResponse<Buffer> response = await(callTool(argumentsCase.rawValue()));

        assertThat(response.statusCode())
                .as(argumentsCase + " must be rejected before tools/call becomes SSE")
                .isEqualTo(400);
        assertThat(response.getHeader("content-type"))
                .as(argumentsCase + " must be a JSON protocol rejection, never an SSE tool result")
                .startsWith("application/json")
                .doesNotContain("text/event-stream");
        assertThat(response.bodyAsJsonObject().getJsonObject("error").getInteger("code"))
                .as(argumentsCase + " must carry the protocol-owned Invalid params error")
                .isEqualTo(-32602);
        verify(fixture.policyEnforcer(), never()).decide(any(), any());
        assertThat(fixture.publicTool().invocationCount())
                .as("DECISIVE (" + argumentsCase + "): malformed official params must not reach tool invocation")
                .isZero();
    }

    @Test
    @DisplayName("R15 control: valid object arguments reach policy and tool invocation")
    void shouldReachPolicyAndToolInvocationForValidObjectArguments() throws Exception {
        startServer();
        HttpResponse<Buffer> response = await(callTool("{}"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.getHeader("content-type")).startsWith("text/event-stream");
        verify(fixture.policyEnforcer()).decide(any(), any());
        assertThat(fixture.publicTool().invocationCount())
                .as("CONTROL: the malformed-case counter must increment for a valid object-valued call")
                .isEqualTo(1);
    }

    /**
     * Posts one {@code tools/call} for the fixture's public tool with {@code rawArgumentsValue} embedded
     * verbatim as the JSON value of its {@code arguments} member. {@link WebClient} aggregates the
     * response body before its future settles, so the JSON body is attached before {@link #await(Future)}
     * observes the result.
     */
    private Future<HttpResponse<Buffer>> callTool(String rawArgumentsValue) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"" + PUBLIC_TOOL + "\""
                + ",\"arguments\":" + rawArgumentsValue + "}}";
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", PUBLIC_TOOL)
                .sendBuffer(Buffer.buffer(body));
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

    private record MalformedArgumentsCase(String description, String rawValue) {
        @Override
        public String toString() {
            return description;
        }
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
        private final McpPolicyEnforcer policyEnforcer;

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
            this.policyEnforcer = spy(new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty())));
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

        McpPolicyEnforcer policyEnforcer() {
            return policyEnforcer;
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
