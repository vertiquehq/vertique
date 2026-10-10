// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpRequestView;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.resilience.Resilience;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Framework wiring for {@code dev.vertique.mcp.lifecycle.McpLifecycleObservationIT}: fixture server
 * construction, the two fixture tools, and the ordered-event-recording observer and listener shapes.
 * Nothing decisive lives here.
 *
 * <p>Lives in {@code dev.vertique.mcp.server} for exactly the reason {@link
 * McpInputLifecycleObservationITFixture}'s Javadoc records: starting a real port-0 server requires
 * this module's package-private composition types.
 */
public final class McpLifecycleObservationITFixture {

    private McpLifecycleObservationITFixture() {}

    /**
     * Starts a real port-0 server, bound and connected only on the literal {@code 127.0.0.1}, exposing
     * the two fixture tools the proof requires plus an ordered-event observer, a plain observer and an
     * ordered-event completion listener that reads the request view.
     */
    public static Started start(
            Vertx vertx,
            OrderedObserver ordered,
            PlainObserver plain,
            OrderedListener listener,
            SuccessToolInvoker successTool,
            ErrorToolInvoker errorTool)
            throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .build();

        McpToolRegistry registry = McpToolRegistry.build(Set.of(successTool, errorTool));

        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.empty(),
                Resilience.create(vertx)));
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();

        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(ordered, plain),
                Set.of(listener),
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
                identityResolution(securityRuntime),
                httpConfig,
                registry);
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
        public void clearCurrent() {
            bound = null;
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
     * The successful-call tool: declares a real output schema ({@code {"status": string}}, required)
     * and returns a structured value that satisfies it.
     *
     */
    public static final class SuccessToolInvoker implements McpToolInvoker {
        public static final String TOOL_NAME = "lifecycle.observation.success";

        private final McpToolDescriptor descriptor;

        public SuccessToolInvoker() {
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "Successful-call fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}},\"required\":[\"status\"]}",
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
                    return Future.succeededFuture(McpToolResult.structured(Map.of("status", "ok")));
                }
            };
        }
    }

    /** The tool-error-call tool: a completed, schema-independent {@code isError=true} result. */
    public static final class ErrorToolInvoker implements McpToolInvoker {
        public static final String TOOL_NAME = "lifecycle.observation.error";

        private final McpToolDescriptor descriptor;

        public ErrorToolInvoker() {
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "Tool-error-call fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
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
                    return Future.succeededFuture(McpToolResult.error("bounded tool-error message"));
                }
            };
        }
    }

    // --- Fixture observer shapes ---

    /**
     * Records the exact ordered callback sequence of every request into one shared journal: a fresh
     * session is created on every {@link #open} call (the frozen contract's per-request observation
     * scoping), so each request contributes its own {@code open}, {@code onTerminal} and {@code
     * onCompleted} entries, in call order, interleaved with the {@link OrderedListener}'s entry for
     * the same request.
     */
    public static final class OrderedObserver implements McpRequestLifecycleObserver {
        private final List<String> journal;

        public OrderedObserver(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public McpRequestObservation open(Instant startedAt) {
            journal.add("open");
            return new McpRequestObservation() {
                @Override
                public void onTerminal(McpRequestTerminalObservation observation) {
                    journal.add("onTerminal");
                }

                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    journal.add("onCompleted");
                }
            };
        }
    }

    /**
     * A completion listener that notes its callback in the same journal as {@link OrderedObserver},
     * then records the request view through a {@link McpRecordingCompletedListener}.
     */
    public static final class OrderedListener implements McpRequestCompletedListener {
        private final List<String> journal;
        private final McpRecordingCompletedListener recording = new McpRecordingCompletedListener();

        public OrderedListener(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {}

        @Override
        public void onCompleted(McpRequestCompletedEvent event, McpRequestView request) {
            journal.add("listener");
            recording.onCompleted(event, request);
        }

        /** Returns the recording listener holding each request's view, in arrival order. */
        public McpRecordingCompletedListener recording() {
            return recording;
        }
    }

    /** The plain counterpart of {@link OrderedObserver}, recording one session per request. */
    public static final class PlainObserver implements McpRequestLifecycleObserver {
        private final List<RecordingPlainSession> sessions = Collections.synchronizedList(new ArrayList<>());

        @Override
        public McpRequestObservation open(Instant startedAt) {
            RecordingPlainSession session = new RecordingPlainSession();
            sessions.add(session);
            return session;
        }

        public List<RecordingPlainSession> sessions() {
            return List.copyOf(sessions);
        }
    }

    /** One plain session's exact ordered callback sequence: {@code open}, terminal, completed. */
    public static final class RecordingPlainSession implements McpRequestObservation {
        private final List<String> events = new ArrayList<>();

        RecordingPlainSession() {
            events.add("open");
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            events.add("onTerminal");
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            events.add("onCompleted");
        }

        public List<String> events() {
            return List.copyOf(events);
        }
    }
}
