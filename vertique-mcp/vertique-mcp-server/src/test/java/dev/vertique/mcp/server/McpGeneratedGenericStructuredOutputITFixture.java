// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpToolInvoker;
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
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.Router;
import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for {@link McpGeneratedGenericStructuredOutputIT} (review-finding #5, round-14
 * remediation): compiles one real {@code com.example.notes.NoteTools} application source — two
 * parameterized tools returning {@code Future<List<Note>>}, where {@code Note} carries a Jakarta
 * Bean Validation {@code @Min} constraint directly on a record component — with the real {@link
 * McpToolProcessor}, loads both resulting generated invokers, and mounts them behind one real
 * port-0 stateless Streamable HTTP server, exactly like {@link McpGeneratedHelloToolITFixture} but
 * with a generic (parameterized) structured-output type instead of a plain scalar one.
 */
final class McpGeneratedGenericStructuredOutputITFixture {

    static final String TOOL_PACKAGE = "com.example.notes";
    static final String TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".NoteTools";
    static final String NOTE_SOURCE_FQN = TOOL_PACKAGE + ".Note";
    static final String VALID_TOOL_NAME = "notes.valid";
    static final String INVALID_TOOL_NAME = "notes.invalid";
    static final String VALID_INVOKER_FQN = TOOL_PACKAGE + ".NoteTools_valid_McpToolInvoker";
    static final String INVALID_INVOKER_FQN = TOOL_PACKAGE + ".NoteTools_invalid_McpToolInvoker";
    static final List<String> NON_FINITE_TOOL_NAMES = List.of(
            "notes.double-nan",
            "notes.double-positive-infinity",
            "notes.double-negative-infinity",
            "notes.float-nan",
            "notes.float-positive-infinity",
            "notes.float-negative-infinity");
    private static final List<String> NON_FINITE_INVOKER_FQNS = List.of(
            TOOL_PACKAGE + ".NoteTools_doubleNan_McpToolInvoker",
            TOOL_PACKAGE + ".NoteTools_doublePositiveInfinity_McpToolInvoker",
            TOOL_PACKAGE + ".NoteTools_doubleNegativeInfinity_McpToolInvoker",
            TOOL_PACKAGE + ".NoteTools_floatNan_McpToolInvoker",
            TOOL_PACKAGE + ".NoteTools_floatPositiveInfinity_McpToolInvoker",
            TOOL_PACKAGE + ".NoteTools_floatNegativeInfinity_McpToolInvoker");

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROFILE_ID = "generated-output-snake";

    private final ProcessorTestHarness.Result result;
    private final McpToolInvoker validInvoker;
    private final McpToolInvoker invalidInvoker;
    private final McpOutputPipelineITFixture.CapableObserver outputObserver;
    private final HttpServer server;
    private final int port;

    private McpGeneratedGenericStructuredOutputITFixture(Vertx vertx) throws Exception {
        JavaFileObject noteSource = SourceFiles.inline(NOTE_SOURCE_FQN, """
                package com.example.notes;

                import jakarta.validation.constraints.Min;

                import java.util.concurrent.atomic.AtomicInteger;

                public record Note(String displayName, @Min(1) int priority, Object value) {
                    private static final AtomicInteger DISPLAY_NAME_ACCESSES = new AtomicInteger();

                    @Override
                    public String displayName() {
                        DISPLAY_NAME_ACCESSES.incrementAndGet();
                        return displayName;
                    }

                    public static void resetAccessCount() {
                        DISPLAY_NAME_ACCESSES.set(0);
                    }

                    public static int accessCount() {
                        return DISPLAY_NAME_ACCESSES.get();
                    }
                }
                """);
        JavaFileObject toolSource = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.notes;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.List;

                public class NoteTools {

                    @Inject
                    public NoteTools() {}

                    public record NoteInput(String displayName) {}

                    @McpTool(name = "notes.valid", description = "Returns a note satisfying its own output schema.")
                    public Future<List<Note>> valid(
                            @McpToolParam(name = "input", description = "The note input.") NoteInput input) {
                        return Future.succeededFuture(List.of(new Note(input.displayName(), 5, "finite")));
                    }

                    @McpTool(name = "notes.invalid", description = "Returns a note violating its own output schema.")
                    public Future<List<Note>> invalid(
                            @McpToolParam(name = "input", description = "The note input.") NoteInput input) {
                        return Future.succeededFuture(List.of(new Note(input.displayName(), -1, "finite")));
                    }

                    @McpTool(name = "notes.double-nan", description = "Returns a non-finite nested value.")
                    public Future<List<Note>> doubleNan() {
                        return Future.succeededFuture(List.of(new Note("hello", 5, Double.NaN)));
                    }

                    @McpTool(name = "notes.double-positive-infinity", description = "Returns a non-finite nested value.")
                    public Future<List<Note>> doublePositiveInfinity() {
                        return Future.succeededFuture(List.of(new Note("hello", 5, Double.POSITIVE_INFINITY)));
                    }

                    @McpTool(name = "notes.double-negative-infinity", description = "Returns a non-finite nested value.")
                    public Future<List<Note>> doubleNegativeInfinity() {
                        return Future.succeededFuture(List.of(new Note("hello", 5, Double.NEGATIVE_INFINITY)));
                    }

                    @McpTool(name = "notes.float-nan", description = "Returns a non-finite nested value.")
                    public Future<List<Note>> floatNan() {
                        return Future.succeededFuture(List.of(new Note("hello", 5, Float.NaN)));
                    }

                    @McpTool(name = "notes.float-positive-infinity", description = "Returns a non-finite nested value.")
                    public Future<List<Note>> floatPositiveInfinity() {
                        return Future.succeededFuture(List.of(new Note("hello", 5, Float.POSITIVE_INFINITY)));
                    }

                    @McpTool(name = "notes.float-negative-infinity", description = "Returns a non-finite nested value.")
                    public Future<List<Note>> floatNegativeInfinity() {
                        return Future.succeededFuture(List.of(new Note("hello", 5, Float.NEGATIVE_INFINITY)));
                    }
                }
                """);
        this.result = ProcessorTestHarness.run(new McpToolProcessor(), noteSource, toolSource);
        result.assertSuccess();

        Object toolsInstance = result.loadGeneratedClass(TOOLS_SOURCE_FQN)
                .getDeclaredConstructor()
                .newInstance();
        McpToolRuntimeFactory runtimeFactory =
                McpToolRuntimeFactoryTestSupport.factory(Set.of(snakeProfile()), PROFILE_ID);
        InputObjectProcessor inputProcessor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                },
                sanitizerType -> {
                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                });
        this.validInvoker = loadInvoker(VALID_INVOKER_FQN, toolsInstance, runtimeFactory, inputProcessor);
        this.invalidInvoker = loadInvoker(INVALID_INVOKER_FQN, toolsInstance, runtimeFactory, inputProcessor);
        Set<McpToolInvoker> invokers = new LinkedHashSet<>();
        invokers.add(validInvoker);
        invokers.add(invalidInvoker);
        for (String invokerFqn : NON_FINITE_INVOKER_FQNS) {
            invokers.add(loadInvoker(invokerFqn, toolsInstance, runtimeFactory, inputProcessor));
        }

        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .jsonProfile(PROFILE_ID)
                .build();
        McpToolRegistry registry = McpToolRegistry.build(invokers);
        this.outputObserver = new McpOutputPipelineITFixture.CapableObserver();
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
                        Set.of(outputObserver),
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
        this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        this.port = server.actualPort();
    }

    static McpGeneratedGenericStructuredOutputITFixture start(Vertx vertx) throws Exception {
        return new McpGeneratedGenericStructuredOutputITFixture(vertx);
    }

    HttpServer server() {
        return server;
    }

    int port() {
        return port;
    }

    /** The real generated invoker for {@code notes.valid} — exposed so the test can read its descriptor. */
    McpToolInvoker validInvoker() {
        return validInvoker;
    }

    /** The real generated invoker for {@code notes.invalid} — exposed so the test can read its descriptor. */
    McpToolInvoker invalidInvoker() {
        return invalidInvoker;
    }

    int outputObservationCount() {
        return outputObserver.session().toolOutputCount();
    }

    Object observedOutput() {
        return outputObserver.session().observedOutput().normalizedOutput();
    }

    void resetNoteAccessCount() throws Exception {
        result.loadGeneratedClass(NOTE_SOURCE_FQN).getMethod("resetAccessCount").invoke(null);
    }

    int noteAccessCount() throws Exception {
        return (int) result.loadGeneratedClass(NOTE_SOURCE_FQN)
                .getMethod("accessCount")
                .invoke(null);
    }

    private McpToolInvoker loadInvoker(
            String invokerFqn,
            Object toolsInstance,
            McpToolRuntimeFactory runtimeFactory,
            InputObjectProcessor inputProcessor)
            throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(TOOLS_SOURCE_FQN);
        Class<?> invokerClass = result.loadGeneratedClass(invokerFqn);
        Constructor<?> invokerConstructor = invokerClass.getDeclaredConstructor(
                toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class);
        invokerConstructor.setAccessible(true);
        return (McpToolInvoker) invokerConstructor.newInstance(toolsInstance, runtimeFactory, inputProcessor);
    }

    private static JsonMapperProfile snakeProfile() {
        var mapper = DatabindCodec.mapper().copy();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return JsonMapperProfiles.of(JsonProfileId.of(PROFILE_ID), mapper);
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

    /** Resolves the canonical anonymous identity: no authentication scheme is configured. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
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
}
