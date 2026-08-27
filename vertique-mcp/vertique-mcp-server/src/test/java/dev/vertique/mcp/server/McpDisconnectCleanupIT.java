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
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R14 item 5 — the request-scoped cleanup contract must actually run when a client vanishes.
 *
 * <p>R09's {@code bindCorrelation} javadoc claimed its {@code ContextHolder.Scope} is torn down on
 * "every exit path — ... a client disconnect, or a stream reset". It was not. {@code
 * McpRequestDispatcher#registerSettlementHooks} called {@code response().closeHandler(...)} and
 * {@code .exceptionHandler(...)}; both are single-slot setters, and Vert.x Web's own {@code
 * RoutingContextImpl} had already installed <em>its</em> handlers there (on the first {@code
 * addEndHandler} call, which {@link RequestContextLifecycle} makes for every request). MCP's
 * registration replaced them, so no routing-context end handler fired for a disconnect or a reset on
 * this mount and {@code RequestContextLifecycle.Handle#closeAll()} never ran — the correlation
 * binding, every other holder binding, and the mount's own upload cleanup all silently skipped.
 *
 * <p>Settlement now runs through the multicast {@code addEndHandler}. This proof watches the effect
 * rather than the mechanism: a real request is left in flight (its authorization decision never
 * answers), the client's socket is closed, and the recorded {@code ContextHolder} scope must close.
 * Reverting {@code registerSettlementHooks} to the two response handlers turns it red — nothing else
 * in the suite does, which is why the false claim survived R09 through R13.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpDisconnectCleanupIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SCHEME_NAME = "test-scheme";
    private static final String CORRELATION_SCOPE_CLOSE = "close:CorrelationContext";

    /** Bounded poll for the post-disconnect cleanup; the class-level timeout is the backstop. */
    private static final long CLEANUP_WAIT_MS = 10_000;

    /** Alphabetically first — the candidate whose authorization decision never answers. */
    private static final String TIMED_OUT_TOOL = "cleanup.first00";

    /** Alphabetically second — never reached, since the first candidate's hanging gate stops the scan. */
    private static final String SECOND_TOOL = "cleanup.first01";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(joined -> vertx.close().onComplete(vertxResult -> {
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
    }

    @Test
    @DisplayName("R14 item 5: a client disconnect runs the request lifecycle's cleanup, not just settlement")
    void shouldRunRequestScopedCleanupWhenTheClientDisconnectsMidRequest() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", fixture.port()), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request().getBytes(StandardCharsets.UTF_8));
            out.flush();
            // The request must genuinely be in flight — its authorization decision hanging — before the
            // disconnect, or the proof would only be measuring a request that never started.
            awaitDecisionStarted();
        }

        assertThat(awaitCorrelationScopeClosed())
                .as("DECISIVE: RequestContextLifecycle.Handle#closeAll() must run for a client that "
                        + "disconnected mid-request, so the correlation scope bindCorrelation registered "
                        + "with it is genuinely closed. Recorded holder events: " + fixture.holderEvents())
                .isTrue();
        assertThat(fixture.holderEvents())
                .as("NON-VACUITY: the correlation binding must genuinely have been made, or the close "
                        + "assertion above would be trivially satisfiable by never binding at all")
                .contains("bind:CorrelationContext");
    }

    private void awaitDecisionStarted() throws Exception {
        long deadline = System.currentTimeMillis() + CLEANUP_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (fixture.decisionPointCallCount() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the authorization decision never started; the request was never in flight");
    }

    private boolean awaitCorrelationScopeClosed() throws Exception {
        long deadline = System.currentTimeMillis() + CLEANUP_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (fixture.holderEvents().contains(CORRELATION_SCOPE_CLOSE)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** One raw HTTP/1.1 {@code tools/list} frame, written directly so the socket close is fully controlled. */
    private static String request() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        String body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", new JsonObject().put("_meta", meta))
                .encode();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return "POST " + REQUEST_PATH + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "content-type: application/json\r\n"
                + "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                + "Mcp-Method: tools/list\r\n"
                + "Mcp-Name: tools/list\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n\r\n"
                + body;
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
     * An {@link AuthorizationDecisionPoint} that never completes any decision — the persistently slow or
     * unavailable policy backend the shared gate deadline exists for, and the condition under which a
     * self-anchoring cursor makes no forward progress on any page.
     */
    private static final class NeverCompletingDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            callCount.incrementAndGet();
            return Promise.<AuthorizationDecision>promise().future(); // never completed
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

    /**
     * Records every {@code bind} and every {@code Scope#close} by bound type. The correlation scope
     * {@code McpRequestDispatcher#bindCorrelation} registers with the request lifecycle handle is the
     * one this proof watches: it is closed only if {@code RequestContextLifecycle.Handle#closeAll()}
     * genuinely runs for this request.
     */
    private static final class RecordingContextHolder implements ContextHolder {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            events.add("bind:" + type.getSimpleName());
            return () -> events.add("close:" + type.getSimpleName());
        }

        List<String> events() {
            return events;
        }
    }

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
        private final NeverCompletingDecisionPoint decisionPoint;
        private final RecordingContextHolder contextHolder = new RecordingContextHolder();

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
                        "R14 item 5 disconnect-cleanup fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\"}",
                        null,
                        new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
                invokers.add(new RestrictedToolInvoker(descriptor));
            }
            McpToolRegistry registry = McpToolRegistry.build(invokers);

            this.decisionPoint = new NeverCompletingDecisionPoint();
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.of(decisionPoint),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    contextHolder,
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
                            contextHolder,
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

        List<String> holderEvents() {
            return contextHolder.events();
        }

        private IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousOnlyIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    contextHolder);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }
}
