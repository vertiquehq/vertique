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
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T012 TP-001 — exercises the zero-argument authorized {@code tools/call} vertical through the real
 * port-0 identity-establishment and dispatch pipeline: one public zero-argument tool, one {@code
 * @RolesAllowed} zero-argument tool, and one {@code @DenyAll} zero-argument tool, with callers
 * anonymous and bearer {@code alice} (role {@code ops}).
 *
 * <p>Modeled on {@link McpToolPaginationIT}'s and {@link McpAuthorizationIT}'s harness: bind and
 * connect explicitly to {@code 127.0.0.1} (never the default host or {@code "localhost"}), and close
 * every owned resource on every teardown path.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolCallIT {

    private static final String SHOULD_INVOKE_PUBLIC = "shouldInvokeAPublicZeroArgumentTool";
    private static final String SHOULD_MATCH_UNKNOWN_AND_DENIED =
            "shouldReturnTheSameMinusThirtyTwoSixZeroTwoForUnknownAndDeniedTools";
    private static final String SHOULD_SELECT_SSE_BEFORE_INVOCATION =
            "shouldSelectSseBeforeInvocationAndWriteNoByteUntilTerminal";
    private static final String SHOULD_NOT_INVOKE_ON_DENIAL = "shouldNotInvokeOnDenial";

    private static final String PUBLIC_TOOL = "call.publicTool";
    private static final String ROLES_TOOL = "call.rolesTool";
    private static final String DENY_TOOL = "call.denyTool";
    private static final String UNKNOWN_TOOL = "call.doesNotExist";

    private static final String REQUEST_PATH = "/mcp/";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String BEARER_ALICE = "Bearer alice";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String DEFAULT_PUBLIC_TOOL_TEXT = "hello from call.publicTool";

    private final Vertx vertx = Vertx.vertx();

    private McpToolCallITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t012ContractRows() {
        return Stream.of(
                SHOULD_INVOKE_PUBLIC,
                SHOULD_MATCH_UNKNOWN_AND_DENIED,
                SHOULD_SELECT_SSE_BEFORE_INVOCATION,
                SHOULD_NOT_INVOKE_ON_DENIAL);
    }

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback, mirroring {@link McpToolPaginationIT}'s teardown.
     *
     * @throws Exception if teardown does not complete within its bound
     */
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
    @MethodSource("t012ContractRows")
    @DisplayName("T012 tools/call matrix: zero-argument dispatch, indistinguishable denial, and SSE selection")
    void shouldEnforceT012ContractMatrix(String row) throws Exception {
        startServer();
        switch (row) {
            case SHOULD_INVOKE_PUBLIC -> {
                // Given/When: an anonymous caller invokes the public zero-argument tool once.
                HttpResponse<Buffer> response = await(callTool(null, PUBLIC_TOOL, 1));

                // Then: the call terminates with the tool's result, delivered over the SSE the call
                // selected, and the counter that DENIAL rows rely on to prove non-vacuousness moves.
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.getHeader("content-type")).isEqualTo("text/event-stream");
                JsonObject result = sseResult(response.bodyAsString());
                assertThat(result.getBoolean("isError")).isFalse();
                assertThat(result.getJsonArray("content").getJsonObject(0).getString("text"))
                        .isEqualTo(DEFAULT_PUBLIC_TOOL_TEXT);
                assertThat(fixture.publicTool().invocationCount())
                        .as("a permitted zero-argument call must reach the guarded invocation path")
                        .isEqualTo(1);
            }
            case SHOULD_MATCH_UNKNOWN_AND_DENIED -> {
                // Given/When: the same request id calls an unknown tool name and the @DenyAll tool.
                int decisionsBeforeUnknown = fixture.decisionEvents().size();
                HttpResponse<Buffer> unknown = await(callTool(null, UNKNOWN_TOOL, 7));
                int decisionsAfterUnknown = fixture.decisionEvents().size();
                HttpResponse<Buffer> denied = await(callTool(null, DENY_TOOL, 7));
                int decisionsAfterDenied = fixture.decisionEvents().size();

                // Then (DECISIVE — P04 remediation, issue W8): an unknown name must reach the same
                // McpPolicyEnforcer#decide decision point a denied name reaches — exactly one emitted
                // AuthorizationDecisionEvent each — closing the timing/observability side channel a
                // direct-to-response short-circuit for "unknown" would otherwise leave open (a caller
                // could distinguish "no such tool" from "denied" by whether a decision was ever made,
                // independent of the wire-identical bytes asserted below).
                assertThat(decisionsAfterUnknown - decisionsBeforeUnknown)
                        .as("DECISIVE: an unresolved tool name must itself produce exactly one authorization "
                                + "decision, evaluated against the synthetic @DenyAll placeholder")
                        .isEqualTo(1);
                assertThat(decisionsAfterDenied - decisionsAfterUnknown)
                        .as("a genuinely denied tool must also produce exactly one authorization decision")
                        .isEqualTo(1);

                // Then (DECISIVE — wire-identical, bytes and headers, not parsed JSON): absence and
                // denial must be indistinguishable at the wire. Both must be -32602/JSON, never SSE.
                assertThat(unknown.statusCode()).isEqualTo(denied.statusCode()).isEqualTo(400);
                assertThat(unknown.body().getBytes()).isEqualTo(denied.body().getBytes());
                assertThat(normalizedHeaders(unknown)).isEqualTo(normalizedHeaders(denied));
                assertThat(unknown.getHeader("content-type")).isEqualTo("application/json");
                JsonObject error = new JsonObject(unknown.bodyAsString()).getJsonObject("error");
                assertThat(error.getInteger("code")).isEqualTo(-32602);
                assertThat(error.getString("message")).isEqualTo("Invalid params");
                assertThat(error.containsKey("data")).isFalse();
                assertThat(fixture.denyTool().invocationCount())
                        .as("a @DenyAll tool must never be invoked")
                        .isZero();
            }
            case SHOULD_SELECT_SSE_BEFORE_INVOCATION -> {
                // Sub-check 1 (DECISIVE — ordering): make the invocation itself throw. If SSE
                // selection ran only AFTER a successful invocation (the realistic reordering
                // regression the frozen contract forbids — "never falls back to JSON after SSE is
                // selected"), this failing call would answer JSON instead of SSE. It does not: the
                // response is still SSE-framed, proving selection is unconditional and precedes
                // knowledge of the invocation's outcome.
                fixture.publicTool().behavior(() -> {
                    throw new RuntimeException("boom");
                });
                HttpResponse<Buffer> failure = await(callTool(null, PUBLIC_TOOL, 2));
                assertThat(failure.getHeader("content-type"))
                        .as("SSE selection must not depend on invocation outcome")
                        .isEqualTo("text/event-stream");
                assertThat(failure.bodyAsString()).startsWith(SSE_PREFIX);
                assertThat(sseError(failure.bodyAsString()).getInteger("code")).isEqualTo(-32603);
                assertThat(fixture.publicTool().invocationCount())
                        .as("the tool must genuinely have been invoked for this to be decisive")
                        .isEqualTo(1);

                // Sub-check 2 (DECISIVE — zero bytes until terminal): gate the invocation on a promise
                // this test controls. request.response() resolves the instant response HEADERS are
                // received — before any body byte — so while the gate is unresolved, that future being
                // incomplete proves literally zero response bytes (not even a status line) have reached
                // the client, because the server writes the status line, headers, and full SSE body in
                // one atomic end(Buffer) call and never any earlier.
                CountDownLatch invokedLatch = new CountDownLatch(1);
                AtomicReference<Context> owningContext = new AtomicReference<>();
                Promise<McpToolResult<?>> gate = Promise.promise();
                fixture.publicTool().onInvoked(() -> {
                    owningContext.set(Vertx.currentContext());
                    invokedLatch.countDown();
                });
                fixture.publicTool().behavior(gate::future);

                HttpClientRequest request =
                        await(rawClient.request(HttpMethod.POST, fixture.port(), "127.0.0.1", REQUEST_PATH));
                request.putHeader("content-type", "application/json");
                request.putHeader("MCP-Protocol-Version", PROTOCOL_VERSION);
                request.putHeader("Mcp-Method", "tools/call");
                request.putHeader("Mcp-Name", PUBLIC_TOOL);
                Future<HttpClientResponse> responseFuture = request.response();
                // Compose the body future BEFORE sending. Attaching body() only after awaiting the
                // headers leaves a window in which the SSE body buffers arrive with no handler
                // attached and are discarded — which surfaces as a correct 200 with an empty body,
                // and only under load. The headers future is still the decisive premature-byte
                // assertion below; this just makes sure the body is captured from the first buffer.
                Future<Buffer> bodyFuture = responseFuture.compose(HttpClientResponse::body);
                request.end(callBody(PUBLIC_TOOL, 3));

                assertThat(invokedLatch.await(5, TimeUnit.SECONDS))
                        .as("the invocation must be reached before checking for premature bytes")
                        .isTrue();
                assertThat(responseFuture.isComplete())
                        .as("DECISIVE: no byte — not even a status line — may reach the client while the"
                                + " pending invocation gate is unresolved")
                        .isFalse();

                Context context = owningContext.get();
                assertThat(context)
                        .as("the invocation must capture the request-owning Vert.x context")
                        .isNotNull();
                CompletableFuture<Void> gateCompleted = new CompletableFuture<>();
                context.runOnContext(ignored -> {
                    gate.complete(McpToolResult.text("late"));
                    gateCompleted.complete(null);
                });
                gateCompleted.get(5, TimeUnit.SECONDS);

                HttpClientResponse lateResponse = await(responseFuture);
                Buffer lateBody = await(bodyFuture);
                assertThat(lateResponse.statusCode()).isEqualTo(200);
                JsonObject lateResult = sseResult(lateBody.toString());
                assertThat(lateResult.getJsonArray("content").getJsonObject(0).getString("text"))
                        .isEqualTo("late");
            }
            case SHOULD_NOT_INVOKE_ON_DENIAL -> {
                // Given/When: an anonymous caller (never authenticated) calls the @RolesAllowed tool.
                HttpResponse<Buffer> response = await(callTool(null, ROLES_TOOL, 4));

                // Then: denied exactly like an unknown tool, and never invoked. The public-tool row's
                // counter, on the identical ControllableToolInvoker mechanism, proves this counter type
                // is genuinely live — a counter nothing ever increments would make this vacuous.
                assertThat(response.statusCode()).isEqualTo(400);
                JsonObject error = new JsonObject(response.bodyAsString()).getJsonObject("error");
                assertThat(error.getInteger("code")).isEqualTo(-32602);
                assertThat(fixture.rolesTool().invocationCount())
                        .as("a denied @RolesAllowed tool must never be invoked")
                        .isZero();
            }
            default -> fail("unknown T012 tools/call matrix row: " + row);
        }
    }

    // --- Wire helpers ---

    private void startServer() throws Exception {
        fixture = McpToolCallITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private Future<HttpResponse<Buffer>> callTool(String bearer, String toolName, Object id) {
        HttpRequest<Buffer> request = client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName);
        if (bearer != null) {
            request = request.putHeader("Authorization", bearer);
        }
        return request.sendBuffer(callBody(toolName, id));
    }

    private static Buffer callBody(String toolName, Object id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("name", toolName);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    /** Extracts and parses the JSON payload framed by the frozen {@code event: message}/{@code data:} block. */
    private static JsonObject sseData(String rawBody) {
        assertThat(rawBody)
                .as("a known, authorized tools/call response must use the frozen SSE framing")
                .startsWith(SSE_PREFIX);
        return new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
    }

    private static JsonObject sseResult(String rawBody) {
        JsonObject result = sseData(rawBody).getJsonObject("result");
        assertThat(result).as("a successful SSE response must carry a result").isNotNull();
        return result;
    }

    private static JsonObject sseError(String rawBody) {
        JsonObject error = sseData(rawBody).getJsonObject("error");
        assertThat(error)
                .as("a failed SSE response must still carry a JSON-RPC error")
                .isNotNull();
        return error;
    }

    /** Header snapshot with {@code date} excluded: a wall-clock header, not part of the -32602 contract. */
    private static Map<String, List<String>> normalizedHeaders(HttpResponse<Buffer> response) {
        return response.headers().entries().stream()
                .filter(entry -> !"date".equalsIgnoreCase(entry.getKey()))
                .collect(Collectors.groupingBy(
                        entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /**
     * Builds and starts one real MCP mount over three zero-argument tools (public, {@code
     * @RolesAllowed}, {@code @DenyAll}), a real {@link McpPolicyEnforcer}/{@link
     * SecurityPolicyEnforcer} pair (default decision point), and a bearer scheme recognizing exactly
     * {@code "Bearer alice"} (role {@code ops}) — mirrors {@link McpToolPaginationIT}'s fixture.
     */
    private static final class McpToolCallITFixture {

        private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        private final HttpServer server;
        private final int port;
        private final ControllableToolInvoker publicTool;
        private final ControllableToolInvoker rolesTool;
        private final ControllableToolInvoker denyTool;
        private final List<dev.vertique.security.events.AuthorizationDecisionEvent> decisionEvents =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        private McpToolCallITFixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme("bearer")
                    .build();

            this.publicTool = new ControllableToolInvoker(
                    descriptor(PUBLIC_TOOL, permitAllAccess()),
                    () -> Future.succeededFuture(McpToolResult.text(DEFAULT_PUBLIC_TOOL_TEXT)));
            this.rolesTool = new ControllableToolInvoker(descriptor(ROLES_TOOL, rolesAccess()), () -> {
                throw new AssertionError("T012 must never invoke a denied @RolesAllowed tool");
            });
            this.denyTool = new ControllableToolInvoker(descriptor(DENY_TOOL, denyAllAccess()), () -> {
                throw new AssertionError("T012 must never invoke a @DenyAll tool");
            });
            McpToolRegistry registry = McpToolRegistry.build(Set.of(publicTool, rolesTool, denyTool));

            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            dev.vertique.security.events.SecurityEventObserver decisionObserver =
                    new dev.vertique.security.events.SecurityEventObserver() {
                        @Override
                        public Future<Void> onAuthorizationDecided(
                                dev.vertique.security.events.AuthorizationDecisionEvent event) {
                            decisionEvents.add(event);
                            return Future.succeededFuture();
                        }
                    };
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of(decisionObserver)),
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
                    Set.of(new BearerRouteAuthHandler()),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static McpToolCallITFixture start(Vertx vertx) throws Exception {
            return new McpToolCallITFixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        ControllableToolInvoker publicTool() {
            return publicTool;
        }

        ControllableToolInvoker rolesTool() {
            return rolesTool;
        }

        ControllableToolInvoker denyTool() {
            return denyTool;
        }

        List<dev.vertique.security.events.AuthorizationDecisionEvent> decisionEvents() {
            return decisionEvents;
        }

        private static McpToolDescriptor descriptor(String name, McpToolAccess access) {
            return new McpToolDescriptor(
                    name, null, "T012 fixture tool " + name + ".", ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, access);
        }

        private static McpToolAccess permitAllAccess() {
            return new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
        }

        private static McpToolAccess denyAllAccess() {
            return new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null);
        }

        private static McpToolAccess rolesAccess() {
            return new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null);
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new SubjectRoleIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * A zero-argument {@link McpToolInvoker} whose invocation behavior and pre-invocation hook are
     * swappable at test time, and whose invocation count is the decisive, provably-live counter every
     * denial row's zero-invocation claim rests on.
     */
    private static final class ControllableToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile Supplier<Future<McpToolResult<?>>> behavior;
        private volatile Runnable onInvoked = () -> {};

        ControllableToolInvoker(McpToolDescriptor descriptor, Supplier<Future<McpToolResult<?>>> defaultBehavior) {
            this.descriptor = descriptor;
            this.behavior = defaultBehavior;
        }

        void behavior(Supplier<Future<McpToolResult<?>>> behavior) {
            this.behavior = behavior;
        }

        void onInvoked(Runnable onInvoked) {
            this.onInvoked = onInvoked;
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
                    onInvoked.run();
                    return behavior.get();
                }
            };
        }
    }

    /**
     * Resolves the canonical anonymous identity from empty evidence and the {@code sub}-named,
     * {@code roles}-claimed user otherwise — mirrors {@link McpToolPaginationIT}'s identical fixture
     * resolver.
     */
    private record SubjectRoleIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
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

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} without asserting on it. */
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

    /**
     * A hand-written optional-authentication bearer scheme recognizing exactly {@code "Bearer alice"}
     * (role {@code ops}); any other present credential fails 401 without ever calling {@code next()};
     * an absent credential continues anonymously. Mirrors {@link McpToolPaginationIT}'s identical
     * fixture handler.
     */
    private static final class BearerRouteAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "bearer";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    context.next();
                    return;
                }
                if (!BEARER_ALICE.equals(credential)) {
                    context.fail(401);
                    return;
                }
                RestAuthenticationEvidence.append(
                        context,
                        new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("alice"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", "alice")));
                ((UserContextInternal) context.userContext())
                        .setUser(User.create(
                                new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("ops"))));
                context.next();
            });
        }
    }
}
