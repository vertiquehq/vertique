// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.input.processing.InputObjectProcessor;
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
import io.vertx.ext.web.Router;
import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for {@link McpGeneratedGenericStructuredOutputIT} (review-finding #5, round-14
 * remediation): compiles one real {@code com.example.notes.NoteTools} application source — two
 * zero-argument tools returning {@code Future<List<Note>>}, where {@code Note} carries a Jakarta
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

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";

    private final ProcessorTestHarness.Result result;
    private final McpToolInvoker validInvoker;
    private final McpToolInvoker invalidInvoker;
    private final HttpServer server;
    private final int port;

    private McpGeneratedGenericStructuredOutputITFixture(Vertx vertx) throws Exception {
        JavaFileObject noteSource = SourceFiles.inline(NOTE_SOURCE_FQN, """
                package com.example.notes;

                import jakarta.validation.constraints.Min;

                public record Note(String text, @Min(1) int priority) {}
                """);
        JavaFileObject toolSource = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.notes;

                import dev.vertique.mcp.annotation.McpTool;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.List;

                public class NoteTools {

                    @Inject
                    public NoteTools() {}

                    @McpTool(name = "notes.valid", description = "Returns a note satisfying its own output schema.")
                    public Future<List<Note>> valid() {
                        return Future.succeededFuture(List.of(new Note("hello", 5)));
                    }

                    @McpTool(name = "notes.invalid", description = "Returns a note violating its own output schema.")
                    public Future<List<Note>> invalid() {
                        return Future.succeededFuture(List.of(new Note("hello", -1)));
                    }
                }
                """);
        this.result = ProcessorTestHarness.run(new McpToolProcessor(), noteSource, toolSource);
        result.assertSuccess();

        Object toolsInstance = result.loadGeneratedClass(TOOLS_SOURCE_FQN)
                .getDeclaredConstructor()
                .newInstance();
        this.validInvoker = loadInvoker(VALID_INVOKER_FQN, toolsInstance);
        this.invalidInvoker = loadInvoker(INVALID_INVOKER_FQN, toolsInstance);

        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
        McpToolRegistry registry = McpToolRegistry.build(Set.of(validInvoker, invalidInvoker));
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

    private McpToolInvoker loadInvoker(String invokerFqn, Object toolsInstance) throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(TOOLS_SOURCE_FQN);
        Class<?> invokerClass = result.loadGeneratedClass(invokerFqn);
        Constructor<?> invokerConstructor = invokerClass.getDeclaredConstructor(
                toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class);
        invokerConstructor.setAccessible(true);
        return (McpToolInvoker) invokerConstructor.newInstance(
                toolsInstance,
                McpToolRuntimeFactoryTestSupport.factory(),
                // Both tools are zero-argument, so neither resolver function is ever actually invoked.
                InputObjectProcessor.createDefault(
                        canonicalizerType -> {
                            throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                        },
                        sanitizerType -> {
                            throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                        }));
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
