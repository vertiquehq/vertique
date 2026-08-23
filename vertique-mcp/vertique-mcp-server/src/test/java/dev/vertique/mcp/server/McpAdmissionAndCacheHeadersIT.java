// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

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
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * P04 remediation proof for issues W9 (Origin deny-by-default and mandatory Content-Type) and W10
 * (identity-filtered responses must carry HTTP cache directives), against the real port-0 mount with
 * its <strong>default</strong> configuration: an empty {@code mcp.allowedOrigins}, no authentication
 * scheme, and one {@code @PermitAll} tool.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpAdmissionAndCacheHeadersIT {

    private static final String ORIGIN_DENY_BY_DEFAULT_ROW = "shouldRejectPresentOriginWithEmptyDefaultAllowlist";
    private static final String CONTENT_TYPE_ABSENT_ROW = "shouldRejectAbsentContentTypeAsUnsupportedMediaType";
    private static final String DISCOVERY_CACHE_HEADERS_ROW = "shouldCarryPrivateCacheHeadersOnDiscovery";
    private static final String TOOLS_LIST_CACHE_HEADERS_ROW = "shouldCarryPrivateCacheHeadersOnToolsList";

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PUBLIC_TOOL = "admission.publicTool";
    private static final String PRESENT_ORIGIN = "https://untrusted.example";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> rows() {
        return Stream.of(
                ORIGIN_DENY_BY_DEFAULT_ROW,
                CONTENT_TYPE_ABSENT_ROW,
                DISCOVERY_CACHE_HEADERS_ROW,
                TOOLS_LIST_CACHE_HEADERS_ROW);
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
    @DisplayName("default-configuration admission and cache-header contract")
    void shouldEnforceDefaultAdmissionAndCacheHeaderContract(String row) throws Exception {
        startServer();
        switch (row) {
            case ORIGIN_DENY_BY_DEFAULT_ROW -> shouldRejectPresentOriginWithEmptyDefaultAllowlist();
            case CONTENT_TYPE_ABSENT_ROW -> shouldRejectAbsentContentTypeAsUnsupportedMediaType();
            case DISCOVERY_CACHE_HEADERS_ROW -> shouldCarryPrivateCacheHeadersOnDiscovery();
            case TOOLS_LIST_CACHE_HEADERS_ROW -> shouldCarryPrivateCacheHeadersOnToolsList();
            default -> fail("unknown row: " + row);
        }
    }

    // --- Row 1 (W9a): a present Origin is denied by default when mcp.allowedOrigins is empty ---

    private void shouldRejectPresentOriginWithEmptyDefaultAllowlist() throws Exception {
        HttpRequest<Buffer> request = discoverRequest().putHeader("Origin", PRESENT_ORIGIN);
        HttpResponse<Buffer> response = await(request.sendBuffer(discoverBody().toBuffer()));

        assertThat(response.statusCode())
                .as("DECISIVE: a present Origin must be denied by default when mcp.allowedOrigins is empty "
                        + "— the previous permissive default left an unconfigured mount reachable from any "
                        + "page a browser visited")
                .isEqualTo(403);
    }

    // --- Row 2 (W9b): an absent Content-Type is now rejected, not admitted ---

    private void shouldRejectAbsentContentTypeAsUnsupportedMediaType() throws Exception {
        HttpResponse<Buffer> response = await(client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .sendBuffer(discoverBody().toBuffer()));

        assertThat(response.statusCode())
                .as("DECISIVE: every admitted request is a POST carrying the protocol's required JSON body, "
                        + "so an absent Content-Type must now be HTTP 415, not silently admitted (which "
                        + "would reopen the CORS simple-request path)")
                .isEqualTo(415);
    }

    // --- Row 3 (W10): discovery carries private, no-store cache directives ---

    private void shouldCarryPrivateCacheHeadersOnDiscovery() throws Exception {
        HttpResponse<Buffer> response =
                await(discoverRequest().sendBuffer(discoverBody().toBuffer()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control"))
                .as("DECISIVE: a per-identity filtered discovery response must forbid shared-cache storage")
                .isEqualTo("private, no-store");
        assertThat(response.getHeader("Vary"))
                .as("DECISIVE: a cache keying on the caller must be told authorization is part of the key")
                .isEqualTo("Authorization");
    }

    // --- Row 4 (W10): tools/list carries private, no-store cache directives ---

    private void shouldCarryPrivateCacheHeadersOnToolsList() throws Exception {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", new JsonObject().put("_meta", meta));
        HttpResponse<Buffer> response = await(client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .sendBuffer(body.toBuffer()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control"))
                .as("DECISIVE: a per-identity filtered tools/list response must forbid shared-cache storage")
                .isEqualTo("private, no-store");
        assertThat(response.getHeader("Vary"))
                .as("DECISIVE: a cache keying on the caller must be told authorization is part of the key")
                .isEqualTo("Authorization");
    }

    // --- Wire helpers ---

    private void startServer() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private HttpRequest<Buffer> discoverRequest() {
        return client.post(fixture.port(), LOOPBACK, REQUEST_PATH).putHeader("content-type", "application/json");
    }

    private static JsonObject discoverBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", meta));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A zero-argument public tool, invoked by no row in this matrix. */
    private static final class PublicToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        PublicToolInvoker(McpToolDescriptor descriptor) {
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
                    throw new AssertionError("no row in this matrix invokes a tool");
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

    /** Framework wiring: one real port-0 mount, default (empty) allowedOrigins, no auth scheme. */
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
                    PUBLIC_TOOL,
                    null,
                    "W9/W10 fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            McpToolRegistry registry = McpToolRegistry.build(Set.of(new PublicToolInvoker(descriptor)));

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
