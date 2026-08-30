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
 * <p>Drives a real port-0 server against both failure modes and asserts, in both, that the request
 * still settles rather than hanging — which is what P05 finding #5 is about.
 *
 * <p><strong>R14 item 3 changed the settled outcome of the callback row, and this is deliberate.</strong>
 * This proof used to assert that a {@code StackOverflowError} from a session's {@code onToolInput}
 * degraded the whole request to a bounded {@code 500}, and its javadoc explained why: {@code
 * McpCompletionCoordinator#invoke} caught only {@code RuntimeException}, so an {@code Error} escaped
 * the coordinator and stage 5's dispatcher guard caught it instead. That made one callback class
 * behave two ways — a {@code RuntimeException} from the same callback was isolated and the request
 * succeeded, an {@code Error} turned it into a 500 — and contradicted this module's own frozen
 * contract, which says observer callback failures "are isolated per observer and never change the
 * protocol or business outcome". R14 item 3 applies the project's narrow {@code RuntimeException |
 * StackOverflowError} policy at {@code invoke}, so the {@code Error} is now isolated exactly like the
 * {@code RuntimeException} always was and the tool call returns its ordinary {@code 200} result. The
 * request still settles; it settles better.
 *
 * <p>Stage 5's dispatcher guard is not thereby left unproven. The second row exercises it directly,
 * through a <em>framework-side</em> stage-5 failure that {@code invoke} neither sees nor can isolate:
 * {@code McpToolInputObservation}'s compact constructor rejecting a {@code null} normalized-argument
 * tree, thrown while building the observation, before any session is reached. That still degrades to
 * the bounded, SSE-framed internal-error response, which is the correct answer there — the framework,
 * not an observer, failed.
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
    @DisplayName("isolates a StackOverflowError from a value-observer's onToolInput, settling the tool "
            + "call normally instead of stranding the request")
    void shouldIsolateAStackOverflowFromOnToolInputAndStillSettleTheRequest() throws Exception {
        fixture = Fixture.start(vertx, false);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        // DECISIVE (P05 finding #5, unchanged): the request must settle — a real HTTP response within
        // the test's bounded await, not a hang. Before that fix, the StackOverflowError escaping
        // publishToolInput's call site (before selectSse's headers were ever followed by a beginWrite)
        // left this await to time out instead.
        //
        // DECISIVE (R14 item 3): the settled outcome is the tool's own successful result, not a 500.
        // A misbehaving observer must not change the protocol outcome — this module's frozen contract
        // — and a RuntimeException from this exact callback always behaved this way. Reverting
        // McpCompletionCoordinator#invoke to RuntimeException-only turns this row red with a 500.
        assertThat(response.statusCode())
                .as("DECISIVE: an observer callback failure must not change the protocol outcome, and "
                        + "must not hang either")
                .isEqualTo(200);
        String rawBody = response.bodyAsString();
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject decoded =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        assertThat(decoded.containsKey("error"))
                .as("the observer's failure must not be reported to the client at all")
                .isFalse();
        assertThat(decoded.getJsonObject("result").getBoolean("isError"))
                .as("the tool's own result must be delivered untouched")
                .isFalse();
    }

    @Test
    @DisplayName("degrades a framework-side stage-5 failure to the bounded internal-error fallback")
    void shouldDegradeAFrameworkSideStage5FailureToTheBoundedFallback() throws Exception {
        fixture = Fixture.start(vertx, true);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(1));

        // DECISIVE (P05 finding #5): this failure happens while the framework builds the observation —
        // McpToolInputObservation's compact constructor rejecting a null normalized-argument tree —
        // so no session is reached and McpCompletionCoordinator#invoke can neither see nor isolate it.
        // Only stage 5's own dispatcher guard stands between it and a stranded request with no
        // response, no terminal and no completion. Removing that guard turns this row red.
        assertThat(response.statusCode())
                .as("DECISIVE: a framework-side stage-5 failure must degrade to a bounded response, " + "never hang")
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

    /**
     * A zero-argument, zero-result tool — the value under test is the observer, not the tool.
     *
     * <p>{@code nullNormalizedArguments} makes {@code normalizedArguments()} return {@code null},
     * which {@code McpToolInputObservation}'s compact constructor rejects. That is a deterministic,
     * framework-side stage-5 failure: it is thrown while the dispatcher builds the observation, before
     * any session is reached, so it isolates stage 5's own guard from the coordinator's per-session
     * isolation. Chosen over a pathologically deep argument tree, which would depend on the JVM's
     * actual stack depth and be flaky.
     */
    private static final class NoopToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final boolean nullNormalizedArguments;

        NoopToolInvoker(McpToolDescriptor descriptor, boolean nullNormalizedArguments) {
            this.descriptor = descriptor;
            this.nullNormalizedArguments = nullNormalizedArguments;
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
                    return nullNormalizedArguments ? null : Map.of();
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

        private Fixture(Vertx vertx, boolean frameworkSideFailure) throws Exception {
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
            McpToolRegistry registry =
                    McpToolRegistry.build(Set.of(new NoopToolInvoker(descriptor, frameworkSideFailure)));

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

        static Fixture start(Vertx vertx, boolean frameworkSideFailure) throws Exception {
            return new Fixture(vertx, frameworkSideFailure);
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
