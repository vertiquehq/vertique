// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
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
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * Exercises the T011 authorized {@code tools/list} pagination contract (§4.7) through the real
 * port-0 identity-establishment and dispatch pipeline: a twelve-tool registry, {@code
 * mcp.tools.pageSize=2} (examination budget {@code 4 * 2 = 8}), and callers anonymous and bearer
 * {@code alice} (role {@code ops}).
 *
 * <p><strong>Registry layout (global name order):</strong> {@code tool01}–{@code tool09} are {@code
 * @DenyAll} (nine consecutive hidden candidates), {@code tool10} and {@code tool12} are public, and
 * {@code tool11} is {@code @RolesAllowed("ops")}. Positions 1–8 alone exhaust the {@code 4 * pageSize}
 * budget before any visible tool is found, which is the decisive scenario the frozen contract
 * describes but that an eight-tool registry — where the budget equals the registry size — can never
 * produce: with budget == total candidates, a scan from any anchor always reaches the registry's end
 * at or before the budget is exhausted, so "budget exhausted while candidates remain" is
 * mathematically unreachable. The registry is extended to twelve tools solely so this row has a real,
 * non-vacuous proof (bounded-discretion fixture sizing).
 *
 * <p>Reauthorization and the examination budget are proven with a counting {@link
 * SecurityPolicyEnforcer} subclass: {@link McpPolicyEnforcer#decide} unconditionally delegates to
 * {@link SecurityPolicyEnforcer#decide} for every candidate regardless of policy shape (including
 * {@code PermitAll}, which emits no event), so counting invocations of that one method is an exact
 * per-candidate-examined count — unlike counting emitted {@code AuthorizationDecisionEvent}s, which
 * would silently under-count a {@code PermitAll} candidate.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolPaginationIT {

    private static final String VISIBLE_ROW = "shouldReturnOnlyVisibleToolsPerPage";
    private static final String UNDERFILLED_ROW = "shouldReturnAnUnderfilledPageWithACursorWhenCandidatesRemain";
    private static final String BUDGET_ROW = "shouldExamineAtMostFourTimesPageSizeCandidatesPerPage";
    private static final String REAUTHORIZE_ROW = "shouldReauthorizeEveryCandidateOnEveryPage";
    private static final String CACHE_ROW = "shouldIncludeMandatoryTtlAndPrivateCacheScope";

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String BEARER_ALICE = "Bearer alice";
    private static final int PAGE_SIZE = 2;
    private static final int EXAMINATION_BUDGET = 4 * PAGE_SIZE;
    private static final long TOOLS_TTL_MS = 300_000;

    private final Vertx vertx = Vertx.vertx();

    private McpToolPaginationITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t011ContractRows() {
        return Stream.of(VISIBLE_ROW, UNDERFILLED_ROW, BUDGET_ROW, REAUTHORIZE_ROW, CACHE_ROW);
    }

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback, mirroring {@link McpDiscoverIT}'s teardown.
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
    @MethodSource("t011ContractRows")
    @DisplayName("T011 tools/list matrix: bounded, authorized pagination and cache hints")
    void shouldEnforceT011ContractMatrix(String row) throws Exception {
        startServer();
        switch (row) {
            case VISIBLE_ROW -> {
                JsonObject page1 = listToolsResult(await(listTools(BEARER_ALICE, null)));
                String cursor = page1.getString("nextCursor");
                assertThat(cursor)
                        .as("page 1 must carry a cursor while tool10–tool12 remain")
                        .isNotNull();

                // SENSITIVITY PROOF (TP-001): the caller literal on the next line is the mutation
                // point. Replacing only BEARER_ALICE with null (anonymous) drops tool11 (role-gated)
                // from this page's visible set, shifting the composition to the public subset
                // ([tool10, tool12]) while the examined-candidate budget assertion in BUDGET_ROW's own
                // scan stays satisfied — verified manually and restored.
                JsonObject page2 = listToolsResult(await(listTools(BEARER_ALICE, cursor)));
                List<String> visibleNames = toolNames(page2);
                // DECISIVE: tool09 (@DenyAll) is examined in this page's scan window but must never
                // appear in the returned tools — only the two visible candidates do.
                assertThat(visibleNames)
                        .as("only visible tools are returned; the examined-but-hidden tool09 is absent")
                        .containsExactly("tool10", "tool11");
            }
            case UNDERFILLED_ROW -> {
                HttpResponse<Buffer> response1 = await(listTools(BEARER_ALICE, null));
                JsonObject page1 = listToolsResult(response1);
                assertThat(page1.getJsonArray("tools"))
                        .as("a page that exhausts its budget before finding any visible tool is empty")
                        .isEmpty();
                String cursor = page1.getString("nextCursor");
                assertThat(cursor)
                        .as("an underfilled/empty page must still carry nextCursor while candidates remain")
                        .isNotNull();

                // Closes the T005/T011 -32602 indistinguishability obligation (issue #420) for the
                // cursor path: a tampered cursor must yield the exact same body and HTTP status as
                // McpPolicyEnforcer#unknownOrUnauthorizedError(), never a distinct code/status that
                // would let a caller distinguish "malformed cursor" from any other -32602 cause.
                String tamperedCursor = McpToolPaginationITFixture.tamperDigest(cursor);
                HttpResponse<Buffer> tamperedResponse = await(listTools(BEARER_ALICE, tamperedCursor));
                assertThat(tamperedResponse.statusCode())
                        .as("an invalid cursor must map through the shared httpStatusFor(-32602) status")
                        .isEqualTo(400);
                JsonObject tamperedBody = new JsonObject(tamperedResponse.bodyAsString());
                JsonObject error = tamperedBody.getJsonObject("error");
                assertThat(error.getInteger("code")).isEqualTo(-32602);
                assertThat(error.getString("message")).isEqualTo("Invalid params");
                assertThat(error.containsKey("data"))
                        .as("the indistinguishable response must carry no identifying data")
                        .isFalse();
            }
            case BUDGET_ROW -> {
                int before = fixture.decideCount();
                JsonObject page1 = listToolsResult(await(listTools(BEARER_ALICE, null)));
                int examined = fixture.decideCount() - before;

                assertThat(examined)
                        .as("candidate examination must never exceed 4 * pageSize")
                        .isLessThanOrEqualTo(EXAMINATION_BUDGET);
                // DECISIVE (both sides): a budget of 1 would also satisfy "not exceeded" — proving the
                // implementation actually crosses one page's worth of examination is required too.
                assertThat(examined)
                        .as("the budget-bound scan must examine more than one page's worth of candidates")
                        .isGreaterThan(PAGE_SIZE);
                assertThat(page1.getJsonArray("tools"))
                        .as("all nine leading @DenyAll candidates are exhausted before any visible tool")
                        .isEmpty();
            }
            case REAUTHORIZE_ROW -> {
                int beforeFirst = fixture.decideCount();
                await(listTools(BEARER_ALICE, null));
                int firstCallExamined = fixture.decideCount() - beforeFirst;

                int beforeSecond = fixture.decideCount();
                await(listTools(BEARER_ALICE, null));
                int secondCallExamined = fixture.decideCount() - beforeSecond;

                // DECISIVE: an implementation that cached the first call's authorization results would
                // examine zero candidates (or fewer) on the identical second call; every page must
                // reauthorize every candidate it examines, every time.
                assertThat(firstCallExamined).isEqualTo(EXAMINATION_BUDGET);
                assertThat(secondCallExamined)
                        .as(
                                "an identical repeated request must reauthorize every candidate again, not reuse a cached result")
                        .isEqualTo(firstCallExamined);
            }
            case CACHE_ROW -> {
                JsonObject page1 = listToolsResult(await(listTools(BEARER_ALICE, null)));
                assertThat(page1.getLong("ttlMs")).isEqualTo(TOOLS_TTL_MS);
                assertThat(page1.getString("cacheScope")).isEqualTo("private");
            }
            default -> fail("unknown T011 pagination matrix row: " + row);
        }
    }

    // --- Wire helpers ---

    private void startServer() throws Exception {
        fixture = McpToolPaginationITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private HttpRequest<Buffer> post() {
        return client.post(fixture.port(), "127.0.0.1", McpToolPaginationITFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list");
    }

    private Future<HttpResponse<Buffer>> listTools(String bearer, String cursor) {
        HttpRequest<Buffer> request = post();
        if (bearer != null) {
            request = request.putHeader("Authorization", bearer);
        }
        return request.sendBuffer(listToolsBody(cursor));
    }

    private static Buffer listToolsBody(String cursor) {
        JsonObject params = new JsonObject()
                .put(
                        "_meta",
                        new JsonObject()
                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", params)
                .toBuffer();
    }

    private static JsonObject listToolsResult(HttpResponse<Buffer> response) {
        assertThat(response.statusCode())
                .as("a successful tools/list call must answer HTTP 200")
                .isEqualTo(200);
        JsonObject body = new JsonObject(response.bodyAsString());
        JsonObject result = body.getJsonObject("result");
        assertThat(result).as("a successful response must carry a result").isNotNull();
        return result;
    }

    private static List<String> toolNames(JsonObject result) {
        List<String> names = new ArrayList<>();
        for (Object element : result.getJsonArray("tools")) {
            names.add(((JsonObject) element).getString("name"));
        }
        return names;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /**
     * Builds and starts one real MCP mount over a twelve-tool registry, a real {@link
     * McpPolicyEnforcer}/{@link SecurityPolicyEnforcer} pair (default decision point), and a bearer
     * scheme recognizing exactly {@code "Bearer alice"} (role {@code ops}).
     */
    private static final class McpToolPaginationITFixture {

        static final String REQUEST_PATH = "/mcp/";

        private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        private final HttpServer server;
        private final int port;
        private final CountingSecurityPolicyEnforcer securityPolicyEnforcer;

        private McpToolPaginationITFixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme("bearer")
                    .toolsPageSize(PAGE_SIZE)
                    .toolsTtlMs(TOOLS_TTL_MS)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(twelveToolRegistry());
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            this.securityPolicyEnforcer = new CountingSecurityPolicyEnforcer();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(securityPolicyEnforcer);
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

        static McpToolPaginationITFixture start(Vertx vertx) throws Exception {
            return new McpToolPaginationITFixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        int decideCount() {
            return securityPolicyEnforcer.count();
        }

        /**
         * Base64url-decodes {@code cursor}, flips the registry {@code digest} field to an unrelated
         * value, and re-encodes — the minimal single-field tamper that must be rejected identically
         * to any other invalid cursor (§4.7, issue #420).
         */
        static String tamperDigest(String cursor) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(cursor);
            ObjectNode node = (ObjectNode) mapper.readTree(decoded);
            node.put("digest", "0".repeat(64));
            byte[] tampered = mapper.writeValueAsBytes((JsonNode) node);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tampered);
        }

        /**
         * Builds the twelve-tool registry (global name order): {@code tool01}–{@code tool09} deny-all,
         * {@code tool10} and {@code tool12} public, {@code tool11} restricted to role {@code ops}.
         */
        private static Set<McpToolInvoker> twelveToolRegistry() {
            Set<McpToolInvoker> invokers = new LinkedHashSet<>();
            for (int i = 1; i <= 9; i++) {
                invokers.add(invoker(descriptor(toolName(i), denyAllAccess())));
            }
            invokers.add(invoker(descriptor(toolName(10), permitAllAccess())));
            invokers.add(invoker(descriptor(toolName(11), rolesAccess())));
            invokers.add(invoker(descriptor(toolName(12), permitAllAccess())));
            return invokers;
        }

        private static String toolName(int index) {
            return String.format("tool%02d", index);
        }

        private static McpToolAccess denyAllAccess() {
            return new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null);
        }

        private static McpToolAccess permitAllAccess() {
            return new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
        }

        private static McpToolAccess rolesAccess() {
            return new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null);
        }

        private static McpToolDescriptor descriptor(String name, McpToolAccess access) {
            return new McpToolDescriptor(
                    name, null, "Fixture tool " + name + ".", ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, access);
        }

        private static McpToolInvoker invoker(McpToolDescriptor descriptor) {
            return new FixtureToolInvoker(descriptor);
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

        /** A tool-invoker double that only publishes a fixed descriptor; T011 never invokes a tool. */
        private static final class FixtureToolInvoker implements McpToolInvoker {
            private final McpToolDescriptor descriptor;

            private FixtureToolInvoker(McpToolDescriptor descriptor) {
                this.descriptor = descriptor;
            }

            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                throw new AssertionError("T011 tools/list never invokes a tool");
            }
        }
    }

    /**
     * Resolves the canonical anonymous identity from empty evidence and the {@code sub}-named,
     * {@code roles}-claimed user otherwise — mirrors {@link McpAuthorizationIT}'s identical fixture
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
     * an absent credential continues anonymously. Mirrors {@link McpAuthorizationIT}'s identical
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

    /**
     * Counts every {@link #decide} invocation before delegating to the real default {@link
     * SecurityPolicyEnforcer} evaluation — an exact per-candidate-examined counter, since {@link
     * McpPolicyEnforcer#decide} calls this method unconditionally for every scanned candidate
     * regardless of its access mode.
     */
    private static final class CountingSecurityPolicyEnforcer extends SecurityPolicyEnforcer {
        private final AtomicInteger count = new AtomicInteger();

        CountingSecurityPolicyEnforcer() {
            super(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    new RecordingSecurityRuntime(),
                    Optional.empty());
        }

        @Override
        public Future<AuthorizationDecision> decide(
                SecurityContext securityContext,
                SecurityPolicy policy,
                Optional<ActionRef> requiredAction,
                ResourceRef resource,
                InvocationOrigin origin) {
            count.incrementAndGet();
            return super.decide(securityContext, policy, requiredAction, resource, origin);
        }

        int count() {
            return count.get();
        }
    }
}
