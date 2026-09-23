// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.server.runtime.McpToolRuntime;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
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
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Framework wiring and argument shapes for {@link McpUnmergedAllOfAcceptanceIT}: the gap-3c89fb8e
 * false-reject fixtures. {@code HJ03} is a discriminator-carrying polymorphic property whose
 * subtypes' generated {@code allOf} parts victools cannot consolidate (the discriminator {@code kind}
 * is declared on both the base and each subtype, once as {@code const} and once as a plain string);
 * {@code UW2UnwrappedAlias} is a {@code @JsonUnwrapped} child whose definition provider leaves an
 * {@code allOf} part carrying the child's own properties. Both are copied verbatim (identifiers kept)
 * from {@code evidence/proof-harness/deser-validation-bv/src/probe/Shapes.java} under
 * {@code docs/specs/rest-021-deserializer-driven-schema-description/} (HJ03, J03Base, J03AnySub,
 * J03PlainSub, UW2AliasChild, UW2UnwrappedAlias).
 *
 * <p>Every tool here is composed through the real {@link McpToolRuntimeFactory}, so its published
 * input schema is what {@code forInputProfile} produces for the argument type and what {@code
 * McpSchemaHardener} then hardens — never a hand-authored document.
 */
final class McpUnmergedAllOfAcceptanceITFixture {

    static final String LOOPBACK = "127.0.0.1";

    static final String HJ03_TOOL = "shapes.hj03PolymorphicAnySetterSubtype";
    static final String UW2_TOOL = "shapes.uw2UnwrappedAlias";

    private final HttpServer server;
    private final int port;
    private final Map<String, CountingToolInvoker<?>> toolsByName;
    private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();

    private McpUnmergedAllOfAcceptanceITFixture(Vertx vertx) throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .build();
        McpToolRuntimeFactory factory = McpToolRuntimeFactoryTestSupport.factory();

        Map<String, CountingToolInvoker<?>> tools = new LinkedHashMap<>();
        register(tools, factory, HJ03_TOOL, Hj03Payload.class);
        register(tools, factory, UW2_TOOL, Uw2Payload.class);
        this.toolsByName = Map.copyOf(tools);

        McpToolRegistry registry = McpToolRegistry.build(Set.copyOf(tools.values()));
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
                        Set.of(recordingObserver()),
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

    /**
     * Composes and starts one real MCP mount carrying the HJ03 and UW2 shape tools.
     *
     * @param vertx the Vert.x instance owning the server
     * @return the started fixture
     * @throws Exception if composition or listening fails
     */
    static McpUnmergedAllOfAcceptanceITFixture start(Vertx vertx) throws Exception {
        return new McpUnmergedAllOfAcceptanceITFixture(vertx);
    }

    HttpServer server() {
        return server;
    }

    int port() {
        return port;
    }

    /**
     * Returns the counting invoker published under {@code toolName}.
     *
     * @param toolName the published tool name
     * @return the invoker, never {@code null}
     */
    CountingToolInvoker<?> tool(String toolName) {
        CountingToolInvoker<?> tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("no fixture tool named '" + toolName + "'");
        }
        return tool;
    }

    /** Every terminal event this fixture's dispatcher has published so far, in publish order. */
    List<McpRequestTerminalEvent> terminals() {
        return terminals;
    }

    private <I> void register(
            Map<String, CountingToolInvoker<?>> tools,
            McpToolRuntimeFactory factory,
            String name,
            Class<I> carrierType) {
        McpToolRuntime<I> runtime = factory.create(
                name,
                null,
                "gap-3c89fb8e shape fixture tool " + name + ".",
                new McpToolAnnotations(true, false, true, false),
                carrierType,
                null,
                List.of(),
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        tools.put(name, new CountingToolInvoker<>(runtime));
    }

    private McpRequestLifecycleObserver recordingObserver() {
        return startedAt -> new McpRequestObservation() {
            @Override
            public void onTerminal(McpRequestTerminalObservation observation) {
                terminals.add(observation.event());
            }
        };
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

    /**
     * Stands in for a generated invoker: it materializes the arguments through the tool's own effective
     * profile mapper (the generated fixed input boundary's stage 3) and counts every genuine
     * invocation, so a test can prove a rejection happened before the handler ran.
     *
     * @param <I> the input-carrier record type
     */
    static final class CountingToolInvoker<I> implements McpToolInvoker {

        private final McpToolRuntime<I> runtime;
        private final AtomicInteger prepareCallCount = new AtomicInteger();
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicReference<I> lastPayload = new AtomicReference<>();

        CountingToolInvoker(McpToolRuntime<I> runtime) {
            this.runtime = runtime;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return runtime.descriptor();
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            prepareCallCount.incrementAndGet();
            I materialized;
            try {
                materialized = runtime.materializeArguments(arguments);
            } catch (RuntimeException materializationFailure) {
                throw new McpInputRejectionException(
                        "Invalid tool arguments: materialization failed", materializationFailure);
            }
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.copyOf(arguments);
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocationCount.incrementAndGet();
                    lastPayload.set(materialized);
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
        }

        /** The published, hardened input schema of this tool. */
        String inputSchema() {
            return runtime.descriptor().inputSchema();
        }

        int prepareCallCount() {
            return prepareCallCount.get();
        }

        int invocationCount() {
            return invocationCount.get();
        }

        /** The carrier the last genuine invocation received, or {@code null} if none ran. */
        @Nullable
        I lastPayload() {
            return lastPayload.get();
        }
    }

    // --- Security wiring (anonymous-only, no configured scheme) ---

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

    private record AnonymousOnlyIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    // --- Carriers: the generated shape, one parameter published as "payload" ---

    record Hj03Payload(@JsonProperty("payload") HJ03 argument0) {}

    record Uw2Payload(@JsonProperty("payload") UW2UnwrappedAlias argument0) {}

    // ---- HJ03: any-setter on a polymorphic subtype (copied verbatim from Shapes.java) ----

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = J03AnySub.class, name = "any"),
        @JsonSubTypes.Type(value = J03PlainSub.class, name = "plain")
    })
    abstract static class J03Base {
        public String kind;
    }

    static final class J03AnySub extends J03Base {
        public String name;

        @JsonAnySetter
        private Map<String, String> extras = new LinkedHashMap<>();
    }

    static final class J03PlainSub extends J03Base {
        public String other;
    }

    static final class HJ03 {
        public String label;
        public J03Base value;
    }

    // ---- UW2: @JsonUnwrapped child whose alias is folded, plus an any-setter on the parent
    // (copied verbatim from Shapes.java's UW2AliasChild / UW2UnwrappedAlias) ----

    static final class UW2AliasChild {
        @JsonAlias("nm")
        @Size(max = 3)
        public String name;
    }

    static final class UW2UnwrappedAlias {
        public String label;

        @JsonUnwrapped
        public UW2AliasChild inner;

        @JsonAnySetter
        private Map<String, Object> extras = new LinkedHashMap<>();
    }
}
