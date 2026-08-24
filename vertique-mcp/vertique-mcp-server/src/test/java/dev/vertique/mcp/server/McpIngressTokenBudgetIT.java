// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.server.support.McpJsonTokenCorpus;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R18 proof: configured ingress token exhaustion is a protocol rejection before every dispatch-stage
 * callback. The fixture configures 1,024 tokens and sends the canonical corpus's valid 1,025-token
 * {@code tools/call} document through the real HTTP mount.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpIngressTokenBudgetIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String PUBLIC_TOOL = "token-budget.publicTool";

    private static final int INGRESS_MAX_TOKENS = 1_024;
    private static final int OUTPUT_MAX_TOKENS = 65_536;
    private static final int MAX_BODY_SIZE = 2_097_152;
    private static final String TOKEN_EXHAUSTING_ROW_ID = "valid-tools-call-arguments-plus-one";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    /**
     * Closes the raw client and server together, then closes the Vert.x instance only once that join
     * settles. {@link WebClient#close()} cannot be awaited, so this fixture wraps the raw client and
     * owns its awaitable close future instead.
     */
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

    @Test
    @DisplayName("R18: rejects configured ingress token exhaustion before dispatch")
    void shouldRejectTokenExhaustionBeforeDispatch() throws Exception {
        startServer();
        McpJsonTokenCorpus.Row corpusRow = McpJsonTokenCorpus.ingressIntegrationRows().stream()
                .filter(row -> row.id().equals(TOKEN_EXHAUSTING_ROW_ID))
                .findFirst()
                .orElseThrow();
        Buffer body = Buffer.buffer(corpusRow.renderedUtf8());

        assertThat(body.length())
                .as("the token budget, rather than HTTP's independent byte boundary, must reject this document")
                .isLessThanOrEqualTo(MAX_BODY_SIZE);
        assertThat(corpusRow.maxBodyBytes())
                .as("the corpus declares the independent byte cap that admits this real HTTP document")
                .isEqualTo(MAX_BODY_SIZE);
        assertThat(corpusRow.tokenCount())
                .as("the real HTTP document is exactly one token beyond the configured ingress limit")
                .isEqualTo(INGRESS_MAX_TOKENS + 1);

        // WebClient installs its body aggregation before sendBuffer starts the request; await therefore
        // observes a completed HttpResponse<Buffer>, never a status-only response with a late body read.
        HttpResponse<Buffer> response = await(client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", PUBLIC_TOOL)
                .sendBuffer(body));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.getHeader("content-type")).startsWith("application/json");
        assertThat(response.bodyAsJsonObject().getJsonObject("error").getInteger("code"))
                .isEqualTo(-32700);
        assertThat(fixture.requestInterceptor().invocationCount())
                .as("token exhaustion must occur before the pre-dispatch interceptor chain")
                .isZero();
        verify(fixture.toolRegistry(), never()).invokersByName();
        verify(fixture.policyEnforcer(), never()).decide(any(), any());
        assertThat(fixture.publicTool().prepareCallCount())
                .as("token exhaustion must not prepare a generated tool call")
                .isZero();
        assertThat(fixture.publicTool().invocationCount())
                .as("token exhaustion must not invoke application code")
                .isZero();
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

    /** A public zero-argument tool that independently records generated preparation and invocation. */
    private static final class CountingToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final AtomicInteger prepareCallCount = new AtomicInteger();
        private final AtomicInteger invocationCount = new AtomicInteger();

        CountingToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        int prepareCallCount() {
            return prepareCallCount.get();
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
            prepareCallCount.incrementAndGet();
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

    /** Counts the pre-dispatch application hook that a correctly rejected body cannot reach. */
    private static final class CountingRequestInterceptor implements McpRequestInterceptor {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            invocationCount.incrementAndGet();
            return Future.succeededFuture();
        }

        int invocationCount() {
            return invocationCount.get();
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
        private final CountingRequestInterceptor requestInterceptor;
        private final CountingToolInvoker publicTool;
        private final McpToolRegistry toolRegistry;
        private final McpPolicyEnforcer policyEnforcer;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .ingressMaxTokens(INGRESS_MAX_TOKENS)
                    .outputMaxTokens(OUTPUT_MAX_TOKENS)
                    .build();
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    PUBLIC_TOOL,
                    null,
                    "Ingress token-budget fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            this.publicTool = new CountingToolInvoker(descriptor);
            this.toolRegistry = spy(McpToolRegistry.build(Set.of(publicTool)));
            this.requestInterceptor = new CountingRequestInterceptor();

            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            this.policyEnforcer = spy(new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty())));
            HttpConfig httpConfig = HttpConfig.builder()
                    .maxBodySize(MAX_BODY_SIZE)
                    .idleTimeoutSeconds(60)
                    .build();

            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(),
                            Set.of(),
                            Set.of(requestInterceptor),
                            Set.of(),
                            httpConfig,
                            toolRegistry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig,
                    toolRegistry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
            this.port = server.actualPort();
            // Mount validation inspects descriptors at construction. The proof observes only request-time
            // registry lookup, so it starts its spy ledger after the real port-0 mount is ready.
            clearInvocations(toolRegistry);
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

        CountingRequestInterceptor requestInterceptor() {
            return requestInterceptor;
        }

        CountingToolInvoker publicTool() {
            return publicTool;
        }

        McpToolRegistry toolRegistry() {
            return toolRegistry;
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
