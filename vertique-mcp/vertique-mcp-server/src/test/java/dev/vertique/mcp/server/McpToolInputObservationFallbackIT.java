// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Review-remediation proof (P05 finding #5): stage 5 ({@code invokeAndRespond}'s {@code
 * publishToolInput} call site, contract §4.7) ran unguarded, unlike stage 7 a few lines later (see
 * {@link McpCyclicOutputFallbackIT}, W6). Both {@code McpToolInputObservation}'s compact constructor
 * (a native-recursion deep copy of the normalized argument tree — the envelope codec permits 1,000
 * levels of nesting) and a capable session's own {@code onToolInput} callback (e.g. an audit
 * adapter's {@code String.valueOf} on a nested container, which also recurses natively) can throw a
 * {@code RuntimeException} or a native-recursion {@code StackOverflowError}. Before the fix, either
 * would escape before any response was begun: no response, no terminal, no completion, permanently
 * stranding the request.
 *
 * <p>Drives a real port-0 server with a lifecycle observer whose session deliberately throws a
 * {@code StackOverflowError} from {@code onToolInput} — the harder of the two failure modes to
 * isolate, since {@code McpCompletionCoordinator#publishToolInput}'s own per-session {@code invoke()}
 * wrapper catches only {@code RuntimeException}, not {@code Error} — and asserts the request still
 * settles through the same bounded, SSE-framed internal-error fallback stage 7 uses, rather than
 * hanging.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolInputObservationFallbackIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String TOOL_NAME = "p05.f5.stage5.tool";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

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
    @DisplayName("degrades a StackOverflowError from a value-observer's onToolInput to the bounded "
            + "internal-error fallback, instead of stranding the request")
    void shouldDegradeAStackOverflowFromOnToolInputToTheBoundedFallback() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        // DECISIVE: the request must settle — a real HTTP response within the test's bounded await,
        // not a hang. Before the fix, the StackOverflowError escaping publishToolInput's call site
        // (before selectSse's headers were ever followed by a beginWrite) would leave this await to
        // time out instead.
        assertThat(response.statusCode())
                .as("DECISIVE: a StackOverflowError from onToolInput must degrade to a bounded response, "
                        + "never hang")
                .isEqualTo(500);
        String rawBody = response.bodyAsString();
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject decoded =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        assertThat(decoded.getJsonObject("error").getInteger("code")).isEqualTo(-32603);
        assertThat(decoded.getJsonObject("error").containsKey("data")).isFalse();
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

    /** A zero-argument, zero-result tool — the value under test is the observer, not the tool. */
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

    /** Opens a capable session whose {@code onToolInput} deliberately throws a StackOverflowError. */
    private static final class StackOverflowOnToolInputObserver implements McpRequestLifecycleObserver {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            return new McpToolValueObservation() {
                @Override
                public void onToolInput(McpToolInputObservation observation) {
                    throw new StackOverflowError("p05-f5: deliberate native-recursion proof");
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

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "P05 finding 5 stage-5 fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            McpToolRegistry registry = McpToolRegistry.build(Set.of(new NoopToolInvoker(descriptor)));

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
                            Set.of(new StackOverflowOnToolInputObserver()),
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
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
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
