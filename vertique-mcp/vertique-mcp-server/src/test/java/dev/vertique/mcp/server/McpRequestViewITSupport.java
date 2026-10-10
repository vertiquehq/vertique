// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpValueTrees;
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
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wiring shared by the request-view ITs: one real port-0 server bound on {@code 127.0.0.1} with a
 * caller-supplied set of completion listeners and tools, the fixture tools, and the wire helpers.
 * Nothing decisive lives here.
 */
final class McpRequestViewITSupport {

    static final String LOOPBACK = "127.0.0.1";
    static final String REQUEST_PATH = "/mcp/";
    static final String PROTOCOL_VERSION = "2026-07-28";
    static final String SSE_PREFIX = "event: message\ndata: ";

    /** A public tool that retains its arguments and reports the previous call's retained tree. */
    static final String RETAINING_TOOL = "view.fixture.retaining";

    /** A public tool whose normalized arguments are built with the framework's explicit-stack wrapper. */
    static final String PASS_THROUGH_TOOL = "view.fixture.passthrough";

    /** A tool no caller may invoke. */
    static final String DENIED_TOOL = "view.fixture.denied";

    /** Text the retaining tool puts in every result, so the response carries a distinctive marker. */
    static final String RESPONSE_MARKER = "response-marker-4242";

    private McpRequestViewITSupport() {}

    /** The started server and its bound loopback port. */
    record Started(HttpServer server, int port) {}

    /**
     * Starts a real port-0 server exposing {@code tools}, notifying {@code listeners} on every
     * completion. Identity resolution fails while {@code failIdentity} is {@code true}.
     */
    static Started start(
            Vertx vertx,
            Set<McpRequestCompletedListener> listeners,
            Set<McpToolInvoker> tools,
            AtomicBoolean failIdentity)
            throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .build();
        McpToolRegistry registry = McpToolRegistry.build(tools);
        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.empty(),
                TestResilience.shared()));
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(),
                listeners,
                Set.of(),
                Set.of(),
                httpConfig,
                registry,
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                dispatcher,
                Set.of(),
                new IdentityResolutionMiddleware(
                        Set.of(new FixtureIdentityResolver(failIdentity)),
                        Optional.of(new DefaultSecurityClaimMapper()),
                        new SecurityEventEmitter(Set.of()),
                        securityRuntime,
                        NO_OP_CONTEXT_HOLDER),
                httpConfig,
                registry);
        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        HttpServer server =
                await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
        return new Started(server, server.actualPort());
    }

    // --- Wire helpers ---

    /** A well-formed {@code tools/call} body whose {@code arguments} is the given JSON text. */
    static String callBody(String tool, String argumentsJson, int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"" + tool
                + "\",\"arguments\":" + argumentsJson + "}}";
    }

    /** Posts {@code body} to the MCP mount with the headers of an ordinary {@code tools/call}. */
    static Future<HttpResponse<Buffer>> post(
            WebClient client, int port, String tool, String body, Map<String, String> extraHeaders) {
        HttpRequest<Buffer> request = client.post(port, LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", tool);
        extraHeaders.forEach(request::putHeader);
        return request.sendBuffer(Buffer.buffer(body.getBytes(StandardCharsets.UTF_8)));
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture tools ---

    private static McpToolDescriptor descriptorOf(String name, McpAccessMode mode) {
        return new McpToolDescriptor(
                name,
                null,
                "Request view fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(mode, List.of(), null));
    }

    /**
     * A hand-written invoker whose normalized arguments are a deliberately <em>mutable</em> tree it
     * retains, so the framework's read-only view is the only thing standing between a listener and the
     * tool's live state. Each result reports the tree retained from the previous call: a listener that
     * managed to write through the view would change the next response.
     */
    static final class RetainingTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile Map<String, Object> current;
        private volatile Map<String, Object> retained;

        RetainingTool(String name) {
            this.descriptor = descriptorOf(name, McpAccessMode.PERMIT_ALL);
        }

        int invocationCount() {
            return invocations.get();
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            Map<String, Object> mutable = mutableCopy(arguments);
            this.current = mutable;
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return mutable;
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocations.incrementAndGet();
                    String seen = String.valueOf(retained);
                    retained = mutable;
                    return Future.succeededFuture(
                            McpToolResult.structured(Map.of("marker", RESPONSE_MARKER, "seen", seen)));
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> mutableCopy(Map<String, Object> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, mutableValue(value)));
            return copy;
        }

        @SuppressWarnings("unchecked")
        private static Object mutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                return mutableCopy((Map<String, Object>) map);
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new java.util.ArrayList<>(list.size());
                list.forEach(item -> copy.add(mutableValue(item)));
                return copy;
            }
            return value;
        }
    }

    /** A tool whose prepared call reports the arguments through the framework's explicit-stack wrapper. */
    static final class PassThroughTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor = descriptorOf(PASS_THROUGH_TOOL, McpAccessMode.PERMIT_ALL);

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            Map<String, Object> normalized = McpValueTrees.deepUnmodifiableMap(arguments);
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return normalized;
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
        }
    }

    /** A tool every caller is denied. */
    static final class DeniedTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor = descriptorOf(DENIED_TOOL, McpAccessMode.DENY_ALL);

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            throw new AssertionError("a denied tool must never be prepared");
        }
    }

    // --- Framework wiring ---

    private record FixtureIdentityResolver(AtomicBoolean fail) implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (fail.get()) {
                return Future.failedFuture(new RuntimeException("identity resolution failed by fixture"));
            }
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
        public void clearCurrent() {
            bound = null;
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
