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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Framework wiring for {@code dev.vertique.mcp.lifecycle.McpInputLifecycleObservationIT} (T018
 * TP-001): fixture server construction, the fixture tool, and the three fixture observer shapes.
 * Nothing decisive lives here.
 *
 * <p>Lives in {@code dev.vertique.mcp.server} — not the IT's own {@code dev.vertique.mcp.lifecycle}
 * package — because starting a real port-0 server requires this module's package-private composition
 * types ({@link McpRequestDispatcher}, {@link McpRouterMount}, {@link McpServerConfigValidator},
 * {@link McpToolRegistry}, {@link McpPolicyEnforcer}), exactly like every sibling IT ({@link
 * McpToolCallIT}, {@link McpInputPipelineIT}, {@link McpToolPaginationIT}) already does from this same
 * package. Only public types cross the package boundary back to the IT: an {@link HttpServer}, a
 * port, and the public {@code dev.vertique.mcp.lifecycle} observation types.
 */
public final class McpInputLifecycleObservationITFixture {

    public static final String TOOL_NAME = "value.observation.fixture.tool";

    private McpInputLifecycleObservationITFixture() {}

    /**
     * Starts a real port-0 server, bound and connected only on the literal {@code 127.0.0.1}, exposing
     * one public record-argument tool and the three contributed observers TP-001 requires.
     */
    public static Started start(
            Vertx vertx,
            MetricsShapedObserver metrics,
            TracingShapedObserver tracing,
            CapableObserver capable,
            FixtureToolInvoker tool)
            throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .build();

        McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));

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
                Set.of(metrics, tracing, capable),
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
                Set.of(new SubjectRoleIdentityResolver()),
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

    // --- Fixture tool ---

    /**
     * Stands in for a real {@code @McpTool}-generated invoker: {@code prepare()} recursively trims
     * every string leaf of the raw argument tree — a faithful stand-in for the INP-001
     * canonicalization stage a real generated invoker's {@code prepare()} already runs (see {@code
     * McpInputPipelineIT}: "the materialized name is blank once INP-001 canonicalization trims it") —
     * so the delivered {@code normalizedArguments()} genuinely differs from the raw wire values this
     * fixture sends.
     */
    public static final class FixtureToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final AtomicInteger invocationCount = new AtomicInteger();

        public FixtureToolInvoker() {
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "T018 TP-001 fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        }

        public int invocationCount() {
            return invocationCount.get();
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            Map<String, Object> normalized = deepTrim(arguments);
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return normalized;
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocationCount.incrementAndGet();
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> deepTrim(Map<String, Object> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, deepTrimValue(value)));
            return copy;
        }

        @SuppressWarnings("unchecked")
        private static Object deepTrimValue(Object value) {
            if (value instanceof String string) {
                return string.trim();
            }
            if (value instanceof Map<?, ?> map) {
                return deepTrim((Map<String, Object>) map);
            }
            if (value instanceof List<?> list) {
                List<Object> trimmed = new ArrayList<>(list.size());
                for (Object item : list) {
                    trimmed.add(deepTrimValue(item));
                }
                return trimmed;
            }
            return value;
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

    /** A tracing-shaped observer: implements only the plain, capability-free {@link McpRequestObservation}. */
    public static final class TracingShapedObserver implements McpRequestLifecycleObserver {
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

    /**
     * The capability-implementing observer. The sensitivity mutation removes {@code
     * McpToolValueObservation} from this declaration alone.
     */
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

    /** The capability-implementing session. The sensitivity mutation removes the capability here. */
    public static final class CapableSession implements McpToolValueObservation {
        private int terminalCount;
        private int completedCount;
        private int toolInputCount;
        private McpToolInputObservation observed;

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
            observed = observation;
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

        public McpToolInputObservation observed() {
            return observed;
        }
    }
}
