// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
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
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Framework wiring for {@code dev.vertique.mcp.server.McpOutputPipelineIT} (T020 TP-001): fixture
 * server construction, the three fixture tools the contract matrix requires, and the two fixture
 * observer shapes. Nothing decisive lives here.
 *
 * <p>Lives in {@code dev.vertique.mcp.server} for exactly the reason {@link
 * McpInputLifecycleObservationITFixture}'s Javadoc records: starting a real port-0 server requires
 * this module's package-private composition types.
 */
public final class McpOutputPipelineITFixture {

    private McpOutputPipelineITFixture() {}

    /**
     * Starts a real port-0 server, bound and connected only on the literal {@code 127.0.0.1}, exposing
     * the three fixture tools this task's contract matrix requires plus the two observer sessions.
     */
    public static Started start(
            Vertx vertx,
            int outputMaxBytes,
            MetricsShapedObserver metrics,
            CapableObserver capable,
            StructuredResultToolInvoker structuredTool,
            InvalidOutputToolInvoker invalidTool,
            OversizedResultToolInvoker oversizedTool)
            throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .outputMaxBytes(outputMaxBytes)
                .build();

        McpToolRegistry registry = McpToolRegistry.build(Set.of(structuredTool, invalidTool, oversizedTool));

        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.empty()));
        HttpConfig httpConfig = HttpConfig.builder().build();

        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(metrics, capable),
                Set.of(),
                Set.of(),
                Set.of(),
                httpConfig,
                registry,
                policyEnforcer);

        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                dispatcher,
                Set.of(),
                identityResolution(securityRuntime),
                httpConfig);
        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        HttpServer server =
                await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        return new Started(server, server.actualPort());
    }

    private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
        return new IdentityResolutionMiddleware(
                Set.of(new AnonymousIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                securityRuntime,
                NO_OP_CONTEXT_HOLDER);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** The started fixture server and its bound loopback port. */
    public record Started(HttpServer server, int port) {}

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

    /** Resolves the canonical anonymous identity, exactly like the sibling zero-argument-call fixtures. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {
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

    // --- Fixture tools ---

    /**
     * A structured-result tool declaring a real output schema ({@code {"name": string}}, required).
     * Its handler returns a fresh {@link CountingSubject} on every call, so {@link
     * #normalizeInvocationCount()} decisively counts exactly how many times the server's output stage
     * converted the raw application value — never the already-normalized {@code Map} downstream code
     * reuses.
     */
    public static final class StructuredResultToolInvoker implements McpToolInvoker {
        public static final String TOOL_NAME = "output.pipeline.structured";

        private final McpToolDescriptor descriptor;
        private final AtomicInteger normalizeInvocationCount = new AtomicInteger();

        public StructuredResultToolInvoker() {
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "T020 TP-001 structured-result fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        }

        /**
         * Returns how many times this tool's raw application value was converted to its normalized
         * shape — decisive for "normalized exactly once": a count of 2 would mean the server
         * re-serialized the original application object a second time.
         */
        public int normalizeInvocationCount() {
            return normalizeInvocationCount.get();
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
                    return Future.succeededFuture(
                            McpToolResult.structured(new CountingSubject(normalizeInvocationCount)));
                }
            };
        }
    }

    /**
     * The application value {@link StructuredResultToolInvoker} returns: a plain Java class (not a
     * {@code Map}/{@code List}/scalar) whose one getter increments a caller-supplied counter every time
     * Jackson invokes it. The server's output-normalization pass is the only place that can ever touch
     * this getter — once normalized, downstream code (schema validation, the observation callback, the
     * wire embed) operates only on the resulting plain {@code Map}, which carries no reference back to
     * this object.
     */
    public static final class CountingSubject {
        private final AtomicInteger counter;

        CountingSubject(AtomicInteger counter) {
            this.counter = counter;
        }

        public String getName() {
            counter.incrementAndGet();
            return "Ada";
        }
    }

    /**
     * A schema-violating tool: its output schema requires a {@code value} string property, but the
     * handler returns a structured value that omits it and instead carries {@link #LEAKED_SENTINEL} —
     * a marker the wire response must never contain.
     */
    public static final class InvalidOutputToolInvoker implements McpToolInvoker {
        public static final String TOOL_NAME = "output.pipeline.invalid";
        public static final String LEAKED_SENTINEL = "LEAKED_SENTINEL_TOKEN_9f8e7d";

        private final McpToolDescriptor descriptor;

        public InvalidOutputToolInvoker() {
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "T020 TP-001 schema-violating fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}",
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
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
                    return Future.succeededFuture(McpToolResult.structured(Map.of("other", LEAKED_SENTINEL)));
                }
            };
        }
    }

    /**
     * A tool with no declared output schema whose handler returns a structured value large enough that
     * its canonical JSON representation exceeds a small {@code mcp.output.maxBytes} cap (200 sentinel
     * elements, each individually identifiable by {@link #elementAt(int)}, comfortably over 1024 bytes
     * once framed).
     */
    public static final class OversizedResultToolInvoker implements McpToolInvoker {
        public static final String TOOL_NAME = "output.pipeline.oversized";
        public static final String SENTINEL_PREFIX = "OVERSIZED_ELEMENT_";
        public static final int ELEMENT_COUNT = 200;

        private final McpToolDescriptor descriptor;

        public OversizedResultToolInvoker() {
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "T020 TP-001 oversized-result fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        }

        public static String elementAt(int index) {
            return SENTINEL_PREFIX + index;
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
                    List<String> elements = new ArrayList<>(ELEMENT_COUNT);
                    for (int i = 0; i < ELEMENT_COUNT; i++) {
                        elements.add(elementAt(i));
                    }
                    return Future.succeededFuture(McpToolResult.structured(elements));
                }
            };
        }
    }

    // --- Fixture observer shapes ---

    /** A metrics-shaped observer: implements only the plain, capability-free {@link McpRequestObservation}. */
    public static final class MetricsShapedObserver implements McpRequestLifecycleObserver {
        private final PlainSession session = new PlainSession();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return session;
        }

        public PlainSession session() {
            return session;
        }
    }

    /** A plain session recording only what {@link McpRequestObservation} itself can ever receive. */
    public static final class PlainSession implements McpRequestObservation {
        private int terminalCount;
        private int completedCount;

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completedCount++;
        }

        public int terminalCount() {
            return terminalCount;
        }

        public int completedCount() {
            return completedCount;
        }
    }

    /** The capability-implementing observer, recording both the input and output value callbacks. */
    public static final class CapableObserver implements McpRequestLifecycleObserver {
        private final CapableSession session = new CapableSession();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return session;
        }

        public CapableSession session() {
            return session;
        }
    }

    /** The capability-implementing session. */
    public static final class CapableSession implements McpToolValueObservation {
        private int terminalCount;
        private int completedCount;
        private int toolInputCount;
        private int toolOutputCount;
        private McpToolOutputObservation observedOutput;

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount++;
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completedCount++;
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            toolInputCount++;
        }

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            toolOutputCount++;
            observedOutput = observation;
        }

        public int terminalCount() {
            return terminalCount;
        }

        public int completedCount() {
            return completedCount;
        }

        public int toolInputCount() {
            return toolInputCount;
        }

        public int toolOutputCount() {
            return toolOutputCount;
        }

        public McpToolOutputObservation observedOutput() {
            return observedOutput;
        }
    }
}
