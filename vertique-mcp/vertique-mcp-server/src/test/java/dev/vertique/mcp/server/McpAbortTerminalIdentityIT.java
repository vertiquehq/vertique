// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R48 (W3) — an abort settlement terminal must carry the identity the dispatcher legitimately
 * already knows at the point of disconnect, never an invented one and never the caller-supplied raw
 * string.
 *
 * <p>Before this repair, {@code McpRequestDispatcher#settlementTerminal} unconditionally synthesizes
 * every disconnect/reset abort terminal with {@code McpMethod.OTHER} and {@code
 * McpRequestTerminalEvent#UNKNOWN_TOOL_NAME}, regardless of how far the request actually progressed —
 * so a disconnect that happens strictly after a real {@code tools/call} was resolved to a real,
 * registered tool still reports the same unresolved identity as a disconnect that happens before the
 * envelope was even classified.
 *
 * <ul>
 *   <li><strong>Row 1 (RED today):</strong> disconnect strictly after tool resolution — gated during
 *       the post-validation tool-interceptor stage, the same technique {@code
 *       McpDisconnectBeforeInvocationIT} uses to hold that seam open — must carry {@code
 *       McpMethod#TOOLS_CALL} and the real, resolved tool name.
 *   <li><strong>Row 2 (RED today):</strong> disconnect strictly before tool resolution — gated during
 *       the pre-dispatch request-interceptor stage, which {@code McpRequestDispatcher#dispatch} always
 *       runs strictly after {@code classifyMethod} but strictly before {@code writeToolsCall}'s
 *       registry lookup — must carry the already-classified {@code McpMethod#TOOLS_CALL} (legitimately
 *       known at this stage; inventing {@code OTHER} here would be a regression of its own), but
 *       {@code UNKNOWN_TOOL_NAME} for the tool name: no real {@link McpToolDescriptor} was ever
 *       resolved, so the never-invent rule (W3) forbids retaining the raw, caller-supplied candidate
 *       string.
 * </ul>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpAbortTerminalIdentityIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String TOOL_NAME = "abort.terminal.identity";
    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    private final Vertx vertx = Vertx.vertx();
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
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
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("Row 1 (RED): a disconnect after tool resolution carries TOOLS_CALL and the real "
            + "resolved tool name on the abort terminal")
    void shouldCarryClassifiedMethodAndResolvedToolNameWhenDisconnectHappensAfterToolResolution() throws Exception {
        GatedToolInterceptor toolInterceptor = new GatedToolInterceptor();
        RecordingToolInvoker tool = new RecordingToolInvoker();
        RecordingObserver observer = new RecordingObserver();

        server = startServer(Set.of(tool), Set.of(), Set.of(toolInterceptor), observer);
        int port = server.actualPort();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        client.post(port, LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody())
                .onComplete(ignored -> {});

        toolInterceptor.awaitInvoked();
        await(rawClient.close());

        assertThat(observer.awaitSettlement())
                .as("the server must settle the disconnected request while the tool-interceptor gate "
                        + "is still pending")
                .isTrue();
        McpRequestTerminalEvent terminal = observer.terminalEvent();
        assertThat(terminal.method())
                .as("DECISIVE: tool resolution had already succeeded, so the abort terminal must carry "
                        + "TOOLS_CALL, not the invented OTHER")
                .isEqualTo(McpMethod.TOOLS_CALL);
        assertThat(terminal.toolName())
                .as("DECISIVE: the abort terminal must carry the real, resolved tool name, not " + "UNKNOWN_TOOL_NAME")
                .isEqualTo(TOOL_NAME);

        toolInterceptor.releaseWithPermit();
    }

    @Test
    @DisplayName("Row 2 (RED): a disconnect before tool resolution keeps the already-classified TOOLS_CALL "
            + "but never invents a tool name")
    void shouldKeepMethodKnownButToolNameUnresolvedWhenDisconnectHappensDuringRequestInterception() throws Exception {
        GatedRequestInterceptor requestInterceptor = new GatedRequestInterceptor();
        RecordingObserver observer = new RecordingObserver();

        server = startServer(Set.of(), Set.of(requestInterceptor), Set.of(), observer);
        int port = server.actualPort();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        client.post(port, LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(toolCallBody())
                .onComplete(ignored -> {});

        requestInterceptor.awaitInvoked();
        await(rawClient.close());

        assertThat(observer.awaitSettlement())
                .as("the server must settle the disconnected request while the request-interceptor "
                        + "gate is still pending — strictly before tools/call ever resolves a tool")
                .isTrue();
        McpRequestTerminalEvent terminal = observer.terminalEvent();
        assertThat(terminal.method())
                .as("DECISIVE: McpRequestDispatcher#dispatch classifies the method strictly before "
                        + "running any request interceptor, so the abort terminal must already carry "
                        + "TOOLS_CALL at this stage")
                .isEqualTo(McpMethod.TOOLS_CALL);
        assertThat(terminal.toolName())
                .as("DECISIVE: no tool was ever resolved at this stage — the never-invent rule (W3) "
                        + "forbids retaining the raw caller-supplied name, so this must stay "
                        + "UNKNOWN_TOOL_NAME")
                .isEqualTo(McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);

        requestInterceptor.releaseWithPermit();
    }

    // --- Shared fixture wiring ---

    private HttpServer startServer(
            Set<McpToolInvoker> invokers,
            Set<McpRequestInterceptor> requestInterceptors,
            Set<McpToolInterceptor> toolInterceptors,
            RecordingObserver observer)
            throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
        McpToolRegistry registry = McpToolRegistry.build(invokers);
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
                        Set.of(observer),
                        Set.of(),
                        requestInterceptors,
                        toolInterceptors,
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
        return await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
    }

    private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
        return new IdentityResolutionMiddleware(
                Set.of(new AnonymousIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                securityRuntime,
                NO_OP_CONTEXT_HOLDER);
    }

    private static Buffer toolCallBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", TOOL_NAME).put("arguments", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // --- Test doubles ---

    /** Gates the post-validation tool-interceptor stage — tool resolution has already succeeded. */
    private static final class GatedToolInterceptor implements McpToolInterceptor {
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();
        private final Promise<Void> gate = Promise.promise();

        @Override
        public Future<Void> beforeInvocation(McpToolInvocationContext context) {
            invoked.complete(null);
            return gate.future();
        }

        private void awaitInvoked() throws Exception {
            invoked.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void releaseWithPermit() {
            gate.complete(null);
        }
    }

    /** Gates the pre-dispatch request-interceptor stage — strictly before any tool is ever resolved. */
    private static final class GatedRequestInterceptor implements McpRequestInterceptor {
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();
        private final Promise<Void> gate = Promise.promise();

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            invoked.complete(null);
            return gate.future();
        }

        private void awaitInvoked() throws Exception {
            invoked.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void releaseWithPermit() {
            gate.complete(null);
        }
    }

    /** A {@code PermitAll} tool whose handler body is never reached by either proof in this class. */
    private static final class RecordingToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R48 W3 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

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
                    return Future.succeededFuture(McpToolResult.text("must never be observed"));
                }
            };
        }
    }

    /** Records the one terminal event this request settles as, and the transport completion latch. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private final CountDownLatch settlement = new CountDownLatch(2);
        private volatile McpRequestTerminalEvent terminalEvent;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalEvent = observation.event();
            settlement.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            settlement.countDown();
        }

        private boolean awaitSettlement() throws InterruptedException {
            return settlement.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private McpRequestTerminalEvent terminalEvent() {
            return terminalEvent;
        }
    }

    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {
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
}
