// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.mcp.tool.McpToolInvoker;
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
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;

/**
 * Framework wiring for {@link McpGeneratedHelloToolIT} (T012 TP-002).
 *
 * <p>Compiles one real {@code com.example.hello.HelloTools} application source with the real {@link
 * McpToolProcessor} through {@link ProcessorTestHarness}, loads the resulting generated {@code
 * HelloTools_greet_McpToolInvoker} bytecode, and mounts it behind one real port-0 stateless
 * Streamable HTTP server composed exactly like {@link McpToolCallIT}'s hand-authored fixtures — the
 * only difference is that the single contributed {@link McpToolInvoker} here is genuine annotation-
 * processor output, not a hand-authored double.
 *
 * <p><strong>Reflection boundary.</strong> Instantiating the generated {@code HelloTools} bean and
 * its generated invoker requires exactly one reflective {@link Constructor#newInstance} pair each —
 * unavoidable because their fully-qualified names do not exist until this compile-testing run
 * produces them, the same boundary {@link ProcessorTestHarness#loadGeneratedClass} itself exists to
 * cross. Every operation after that point — registry composition, {@link
 * McpPolicyEnforcer#decide}, {@link McpToolInvoker#prepare}, and {@link
 * dev.vertique.mcp.tool.McpPreparedToolCall#invoke} — is a plain interface call on the loaded {@link
 * McpToolInvoker} reference, exactly as production {@link McpRequestDispatcher} does; that class
 * imports no {@code java.lang.reflect} type.
 */
final class McpGeneratedHelloToolITFixture {

    static final String TOOL_PACKAGE = "com.example.hello";
    static final String TOOL_NAME = "hello.greet";
    static final String TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".HelloTools";
    static final String INVOKER_FQN = TOOL_PACKAGE + ".HelloTools_greet_McpToolInvoker";
    static final String GREETING = "Hello, MCP!";

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";

    private final ProcessorTestHarness.Result result;
    private final HttpServer server;
    private final int port;

    /**
     * Compiles the real {@code HelloTools} source with the real {@link McpToolProcessor} and starts
     * one composed server.
     *
     * @param vertx the owning Vert.x instance
     * @param includeGeneratedTool {@code true} to contribute the loaded generated invoker to the
     *     registry (the Given composition); {@code false} to compose an otherwise-identical server
     *     whose registry omits it (the sensitivity mutation: "remove only the generated module from
     *     the composition")
     * @throws Exception if compilation, class loading, or server startup fails
     */
    private McpGeneratedHelloToolITFixture(Vertx vertx, boolean includeGeneratedTool) throws Exception {
        JavaFileObject source = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.hello;

                import dev.vertique.mcp.annotation.McpTool;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class HelloTools {

                    @Inject
                    public HelloTools() {}

                    @McpTool(name = "hello.greet", description = "Greets the caller.")
                    public Future<String> greet() {
                        return Future.succeededFuture("Hello, MCP!");
                    }
                }
                """);
        this.result = ProcessorTestHarness.run(new McpToolProcessor(), source);
        result.assertSuccess();

        Set<McpToolInvoker> invokers = includeGeneratedTool ? Set.of(loadInvoker()) : Set.of();

        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
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
        HttpConfig httpConfig = HttpConfig.builder().build();

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
                httpConfig);
        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        this.port = server.actualPort();
    }

    /**
     * Compiles and starts the fixture.
     *
     * @param vertx the owning Vert.x instance
     * @param includeGeneratedTool {@code true} for the Given composition, {@code false} for the
     *     sensitivity mutation
     * @return the started fixture
     * @throws Exception if compilation, class loading, or server startup fails
     */
    static McpGeneratedHelloToolITFixture start(Vertx vertx, boolean includeGeneratedTool) throws Exception {
        return new McpGeneratedHelloToolITFixture(vertx, includeGeneratedTool);
    }

    HttpServer server() {
        return server;
    }

    int port() {
        return port;
    }

    /** The raw {@code compile-testing} result, exposed so the test can independently assert on it. */
    ProcessorTestHarness.Result result() {
        return result;
    }

    /**
     * Loads the generated {@code HelloTools} bean and its generated invoker, and constructs the
     * invoker through its generated {@code @Inject} constructor.
     */
    private McpToolInvoker loadInvoker() throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(TOOLS_SOURCE_FQN);
        Class<?> invokerClass = result.loadGeneratedClass(INVOKER_FQN);
        Object toolsInstance = toolsClass.getDeclaredConstructor().newInstance();
        Constructor<?> invokerConstructor = invokerClass.getDeclaredConstructor(toolsClass);
        invokerConstructor.setAccessible(true);
        return (McpToolInvoker) invokerConstructor.newInstance(toolsInstance);
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
}
