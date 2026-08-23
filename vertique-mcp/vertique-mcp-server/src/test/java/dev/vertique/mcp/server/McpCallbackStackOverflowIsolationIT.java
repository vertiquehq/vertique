// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
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
 * R13 item 1 proof: the dispatcher's {@code RuntimeException}-only isolation on {@code
 * runRequestInterceptors}, {@code runToolInterceptors}, {@code invoker.prepare()}, and {@code
 * prepared.invoke()} let a native-recursion {@code StackOverflowError} from any of those four
 * application-supplied callback sites escape past this class's own bounded, terminal-event-emitting
 * settlement path — before {@code beginWrite} was ever called for that request. Confirmed by mutation
 * (reverting the request-interceptor site) rather than assumed: the escape does not necessarily hang
 * the connection — a synchronous escape from {@code dispatch()} itself reaches Vert.x's own
 * router-level failure handling first — but it always bypasses this class's bounded, capped,
 * audited response and {@link McpRequestTerminalEvent} entirely, producing a generic, unbounded
 * failure with no terminal observed. Each already carried the narrow {@code RuntimeException |
 * StackOverflowError} isolation policy elsewhere in this same dispatcher (the stage-5/stage-7
 * callback sites {@link McpToolInputObservationFallbackIT} and {@link McpCyclicOutputFallbackIT}
 * already prove); this class proves the same policy now also holds at these four sites.
 *
 * <p>Every row drives a real port-0 server so the completion coordinator is genuinely constructed
 * and a real terminal-event observer genuinely fires — a mock-driven {@code dispatch()} call
 * bypasses {@code begin()} and never observes a terminal event at all (see {@link
 * McpInterceptorRejectionContractIT}'s identical rationale).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpCallbackStackOverflowIsolationIT {

    private static final String REQUEST_INTERCEPTOR_ROW = "shouldDegradeAStackOverflowFromBeforeRequest";
    private static final String TOOL_INTERCEPTOR_ROW = "shouldDegradeAStackOverflowFromBeforeInvocation";
    private static final String PREPARE_ROW = "shouldDegradeAStackOverflowFromPrepare";
    private static final String INVOKE_ROW = "shouldDegradeAStackOverflowFromInvoke";

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String TOOL_NAME = "r13.callback.soe.tool";
    private static final String SOE_MESSAGE = "r13: deliberate native-recursion proof";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> rows() {
        return Stream.of(REQUEST_INTERCEPTOR_ROW, TOOL_INTERCEPTOR_ROW, PREPARE_ROW, INVOKE_ROW);
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
    @DisplayName("a StackOverflowError from any application-supplied callback settles and still emits a terminal")
    void shouldIsolateStackOverflowFromEveryCallbackSite(String row) throws Exception {
        switch (row) {
            case REQUEST_INTERCEPTOR_ROW -> shouldDegradeAStackOverflowFromBeforeRequest();
            case TOOL_INTERCEPTOR_ROW -> shouldDegradeAStackOverflowFromBeforeInvocation();
            case PREPARE_ROW -> shouldDegradeAStackOverflowFromPrepare();
            case INVOKE_ROW -> shouldDegradeAStackOverflowFromInvoke();
            default -> fail("unknown row: " + row);
        }
    }

    // --- Row 1: runRequestInterceptors' interceptor.beforeRequest() call site ---

    private void shouldDegradeAStackOverflowFromBeforeRequest() throws Exception {
        fixture = Fixture.start(
                vertx, Set.of(new StackOverflowRequestInterceptor()), Set.of(), new NoopToolInvoker(descriptor()));
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        // DECISIVE: before the fix (confirmed by mutation), a StackOverflowError escaping
        // interceptor.beforeRequest's RuntimeException-only catch propagated synchronously out of
        // dispatch() itself, past this class's own bounded settlement path entirely, to Vert.x's
        // generic router-level failure handler — an unbounded, unaudited 500 carrying no
        // McpRequestTerminalEvent at all, not the bounded, terminal-event-emitting 403 this class
        // produces for every other interceptor rejection.
        assertThat(response.statusCode())
                .as("DECISIVE: a StackOverflowError from beforeRequest must settle through this class's own "
                        + "bounded interceptor-rejected response, never escape to a generic unaudited 500")
                .isEqualTo(403);
        JsonObject decoded = new JsonObject(response.bodyAsString());
        assertThat(decoded.getJsonObject("error").getInteger("code")).isEqualTo(-32001);

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome())
                .as("DECISIVE: a recursing request interceptor must still emit a REJECTED terminal")
                .isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERCEPTOR);
    }

    // --- Row 2: runToolInterceptors' interceptor.beforeInvocation() call site ---

    private void shouldDegradeAStackOverflowFromBeforeInvocation() throws Exception {
        fixture = Fixture.start(
                vertx, Set.of(), Set.of(new StackOverflowToolInterceptor()), new NoopToolInvoker(descriptor()));
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        assertThat(response.statusCode())
                .as("DECISIVE: a StackOverflowError from beforeInvocation must settle through this class's "
                        + "own bounded tool-interceptor-rejected SSE response, never escape unbounded")
                .isEqualTo(200);
        String rawBody = response.bodyAsString();
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject decoded =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        assertThat(decoded.getJsonObject("result").getBoolean("isError")).isTrue();

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome())
                .as("DECISIVE: a recursing tool interceptor must still emit a REJECTED terminal")
                .isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERCEPTOR);
    }

    // --- Row 3: invokeAndRespond's invoker.prepare() call site ---

    private void shouldDegradeAStackOverflowFromPrepare() throws Exception {
        fixture = Fixture.start(vertx, Set.of(), Set.of(), new PrepareThrowsToolInvoker(descriptor()));
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        assertThat(response.statusCode())
                .as("DECISIVE: a StackOverflowError from prepare() must settle through this class's own "
                        + "bounded internal-error SSE fallback, never escape unbounded")
                .isEqualTo(500);
        String rawBody = response.bodyAsString();
        assertThat(rawBody).startsWith(SSE_PREFIX);

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome())
                .as("DECISIVE: a recursing prepare() must still emit a FAILED terminal")
                .isEqualTo(McpOutcome.FAILED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERNAL);
    }

    // --- Row 4: invokeAndRespond's prepared.invoke() call site ---

    private void shouldDegradeAStackOverflowFromInvoke() throws Exception {
        fixture = Fixture.start(vertx, Set.of(), Set.of(), new InvokeThrowsToolInvoker(descriptor()));
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        assertThat(response.statusCode())
                .as("DECISIVE: a StackOverflowError from invoke() must settle through this class's own "
                        + "bounded internal-error SSE fallback, never escape unbounded")
                .isEqualTo(500);
        String rawBody = response.bodyAsString();
        assertThat(rawBody).startsWith(SSE_PREFIX);

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome())
                .as("DECISIVE: a recursing invoke() must still emit a FAILED terminal")
                .isEqualTo(McpOutcome.FAILED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERNAL);
    }

    // --- Wire helpers ---

    private static McpToolDescriptor descriptor() {
        return new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R13 callback StackOverflowError isolation fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
    }

    private Future<HttpResponse<Buffer>> callTool(Object id) {
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

    /** A request interceptor whose {@code beforeRequest} deliberately throws a StackOverflowError. */
    private static final class StackOverflowRequestInterceptor implements McpRequestInterceptor {
        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            throw new StackOverflowError(SOE_MESSAGE);
        }
    }

    /** A tool interceptor whose {@code beforeInvocation} deliberately throws a StackOverflowError. */
    private static final class StackOverflowToolInterceptor implements McpToolInterceptor {
        @Override
        public Future<Void> beforeInvocation(McpToolInvocationContext context) {
            throw new StackOverflowError(SOE_MESSAGE);
        }
    }

    /** A zero-argument tool whose {@code invoke()} never runs — the value under test is {@code prepare()}. */
    private static final class PrepareThrowsToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        PrepareThrowsToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            throw new StackOverflowError(SOE_MESSAGE);
        }
    }

    /** A zero-argument tool whose {@code prepare()} succeeds but {@code invoke()} deliberately recurses. */
    private static final class InvokeThrowsToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        InvokeThrowsToolInvoker(McpToolDescriptor descriptor) {
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
                    throw new StackOverflowError(SOE_MESSAGE);
                }
            };
        }
    }

    /** A zero-argument, zero-result tool used by the interceptor rows, where the invoker is not under test. */
    private static final class NoopToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        NoopToolInvoker(McpToolDescriptor descriptor) {
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
                    return Future.succeededFuture(McpToolResult.structured(Map.of()));
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

    /** Framework wiring: one real port-0 mount, a configurable interceptor/invoker set, and a terminal recorder. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();

        private Fixture(
                Vertx vertx,
                Set<McpRequestInterceptor> requestInterceptors,
                Set<McpToolInterceptor> toolInterceptors,
                McpToolInvoker invoker)
                throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(invoker));

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
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
            this.port = server.actualPort();
        }

        static Fixture start(
                Vertx vertx,
                Set<McpRequestInterceptor> requestInterceptors,
                Set<McpToolInterceptor> toolInterceptors,
                McpToolInvoker invoker)
                throws Exception {
            return new Fixture(vertx, requestInterceptors, toolInterceptors, invoker);
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
