// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Review-finding proof (round-14 remediation, defect #6): {@code McpRequestDispatcher#scan} used to
 * invoke itself recursively inside {@code Future.compose} for every examined candidate. {@link
 * McpServerConfigValidator} bounds {@code mcp.tools.pageSize} at 500, and the fixed examination
 * budget is {@code 4 * pageSize} (issue #416) — so 2,000 is the true maximum number of candidates one
 * {@code tools/list} scan can ever examine, and every one of {@link McpAccessMode#DENY_ALL} and
 * {@link McpAccessMode#PERMIT_ALL} resolves through {@code SecurityPolicyEnforcer#decide} with an
 * already-completed {@code Future.succeededFuture(...)} — the exact "decisions that complete
 * immediately" shape that turned the old per-candidate recursion into genuine native call-stack
 * recursion up to 2,000 frames deep, with no trampolining guarantee from Vert.x 5.1.6.
 *
 * <p>This fixture configures {@code mcp.tools.pageSize = 500} (the maximum) and registers 2,001
 * {@code DENY_ALL} tools — one more than the budget — so the scan must examine the full 2,000-budget
 * before stopping, entirely through the synchronous per-candidate branch, while a candidate still
 * remains unexamined.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class McpToolListMaxBudgetStackSafetyIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    /** {@link McpServerConfigValidator}'s upper bound for {@code mcp.tools.pageSize}. */
    private static final int MAX_PAGE_SIZE = 500;

    /** The fixed multiplier {@code McpRequestDispatcher} applies — the true maximum budget is 2,000. */
    private static final int EXAMINATION_BUDGET_MULTIPLIER = 4;

    private static final int MAX_BUDGET = MAX_PAGE_SIZE * EXAMINATION_BUDGET_MULTIPLIER;

    /** One more candidate than the budget, so a candidate remains after the scan stops. */
    private static final int TOOL_COUNT = MAX_BUDGET + 1;

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

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

    @Test
    @DisplayName("tools/list must not recurse the native call stack at the maximum examination budget")
    void shouldExamineTheMaximumBudgetWithoutNativeStackRecursion() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // DECISIVE: pre-fix, this call recursed natively up to 2,000 stack frames deep inside the
        // synchronous per-candidate branch (every DENY_ALL decision resolves through an
        // already-completed future); a StackOverflowError there is caught by dispatch()'s outer
        // guard and reported as a 500 internal error, never the correct paginated 200 asserted below.
        HttpResponse<Buffer> response = await(client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(listToolsBody()));

        assertThat(response.statusCode())
                .as("DECISIVE: the maximum-budget scan must complete as an ordinary paginated result, "
                        + "never a StackOverflowError degraded to an internal error")
                .isEqualTo(200);
        JsonObject result = new JsonObject(response.bodyAsString()).getJsonObject("result");
        assertThat(result.getJsonArray("tools"))
                .as("every one of the 2,000 examined candidates is DENY_ALL, so none is visible")
                .isEmpty();
        assertThat(result.getString("nextCursor"))
                .as("2,001 candidates minus a 2,000 budget leaves exactly one candidate unexamined")
                .isNotNull();
    }

    private static Buffer listToolsBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", params)
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /** A zero-argument, {@code DENY_ALL} tool, never invoked by this proof. */
    private static final class DeniedToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        DeniedToolInvoker(McpToolDescriptor descriptor) {
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

    /** One real port-0 mount, {@code mcp.tools.pageSize} at its validated maximum, and 2,001 tools. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .toolsPageSize(MAX_PAGE_SIZE)
                    .build();

            Set<McpToolInvoker> invokers = new HashSet<>();
            for (int i = 0; i < TOOL_COUNT; i++) {
                String name = String.format("budget.tool%05d", i);
                McpToolDescriptor descriptor = new McpToolDescriptor(
                        name,
                        null,
                        "Max-budget stack-safety fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\"}",
                        null,
                        new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null));
                invokers.add(new DeniedToolInvoker(descriptor));
            }
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
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer),
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

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousOnlyIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }
}
