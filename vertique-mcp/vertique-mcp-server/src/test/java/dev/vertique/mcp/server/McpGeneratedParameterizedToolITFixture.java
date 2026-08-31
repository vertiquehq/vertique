// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.correlation.CorrelationContextFactory;
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
 * Framework wiring for {@link McpGeneratedParameterizedToolIT} (MCP-001 P04 remediation TP-001):
 * compiles one real, parameterized {@code com.example.greeting.GreetingTools} application source —
 * declaring a real {@code @Sanitize} chain and a real Jakarta Bean Validation {@code @NotBlank}
 * constraint directly on the tool parameter — with the real {@link McpToolProcessor}, loads the
 * resulting generated {@code GreetingTools_compose_McpToolInvoker} through its real four-argument
 * {@code @Inject} constructor (tool bean, {@link McpToolRuntimeFactory}, {@link
 * InputObjectProcessor}, {@code Optional<jakarta.validation.Validator>}), and mounts it behind one
 * real port-0 stateless Streamable HTTP server —
 * exactly {@link McpGeneratedHelloToolITFixture}'s shape, extended to a real parameterized tool so
 * the mandatory input-processing pipeline (contract §4.7) runs against genuine annotation-processor
 * output rather than a hand-written stand-in.
 *
 * <p><strong>Reflection boundary.</strong> Same boundary as {@link McpGeneratedHelloToolITFixture}:
 * loading the generated {@code GreetingTools}/{@code UpperCaseSanitizer} beans and the generated
 * invoker requires reflective {@link Constructor#newInstance} because their fully-qualified names do
 * not exist until this compile-testing run produces them. Every operation after that point —
 * registry composition, {@link McpPolicyEnforcer#decide}, {@link McpToolInvoker#prepare}, and {@link
 * dev.vertique.mcp.tool.McpPreparedToolCall#invoke} — is a plain interface call, exactly as
 * production {@link McpRequestDispatcher} does.
 */
final class McpGeneratedParameterizedToolITFixture {

    static final String TOOL_PACKAGE = "com.example.greeting";
    static final String TOOL_NAME = "greeting.compose";
    static final String TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".GreetingTools";
    static final String SANITIZER_SOURCE_FQN = TOOL_PACKAGE + ".UpperCaseSanitizer";
    static final String CONSTRAINT_SOURCE_FQN = TOOL_PACKAGE + ".NotBlankConstraint";
    static final String INVOKER_FQN = TOOL_PACKAGE + ".GreetingTools_compose_McpToolInvoker";
    static final String CASCADED_TOOL_NAME = "greeting.cascaded";
    static final String CASCADED_TOOLS_SOURCE_FQN = TOOL_PACKAGE + ".CascadedGreetingTools";
    static final String CASCADED_REQUEST_SOURCE_FQN = TOOL_PACKAGE + ".CascadedGreetingRequest";
    static final String CASCADED_INVOKER_FQN = TOOL_PACKAGE + ".CascadedGreetingTools_compose_McpToolInvoker";

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";

    private final ProcessorTestHarness.Result result;
    private final McpToolInvoker invoker;
    private final McpToolInvoker cascadedInvoker;
    private final HttpServer server;
    private final int port;
    private final McpInputLifecycleObservationITFixture.CapableSession session;

    /**
     * Compiles the real {@code GreetingTools}/{@code UpperCaseSanitizer} sources with the real {@link
     * McpToolProcessor}, loads the generated invoker through its real constructor, and starts one
     * composed server contributing two generated tools and one {@link
     * McpInputLifecycleObservationITFixture.CapableObserver} so the delivered {@code onToolInput}
     * normalized-argument tree can be observed directly.
     *
     * @param vertx the owning Vert.x instance
     * @throws Exception if compilation, class loading, or server startup fails
     */
    private McpGeneratedParameterizedToolITFixture(Vertx vertx) throws Exception {
        JavaFileObject toolSource = SourceFiles.inline(TOOLS_SOURCE_FQN, """
                package com.example.greeting;

                import dev.vertique.core.sanitization.Sanitize;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class GreetingTools {

                    @Inject
                    public GreetingTools() {}

                    @McpTool(name = "greeting.compose", description = "Composes a greeting for the given name.")
                    public Future<String> compose(
                            @McpToolParam(name = "name", description = "The name to greet.")
                            @NotBlankConstraint
                            @Sanitize(UpperCaseSanitizer.class)
                            String name) {
                        return Future.succeededFuture("Hello, " + name + "!");
                    }
                }
                """);
        JavaFileObject sanitizerSource = SourceFiles.inline(SANITIZER_SOURCE_FQN, """
                package com.example.greeting;

                import dev.vertique.core.sanitization.InputValueContext;
                import dev.vertique.core.sanitization.Sanitizer;
                import java.util.Locale;

                public class UpperCaseSanitizer implements Sanitizer {
                    @Override
                    public String sanitize(String value, InputValueContext context) {
                        return value == null ? null : value.toUpperCase(Locale.ROOT);
                    }
                }
                """);
        // A custom Bean Validation constraint — declared @Target({PARAMETER, FIELD}) only, deliberately
        // excluding TYPE_USE, to sidestep a pre-existing, unrelated McpToolModelValidator defect: its
        // input-representability check compares TypeMirror#toString() against a scalar allowlist, and
        // a TYPE_USE-targeted annotation (every jakarta.validation.constraints.* built-in, e.g.
        // @NotBlank, includes TYPE_USE) renders into that string, so a scalar parameter carrying one is
        // wrongly rejected as "no JSON schema representation" before this feature's own code ever runs.
        // Reported to the caller (see final report); this fixture routes around it rather than fixing
        // it. Using a custom constraint also proves the meta-annotation-based
        // McpToolInvokerEmitter#addValidationAnnotations detection generalizes beyond the built-ins.
        JavaFileObject constraintSource = SourceFiles.inline(CONSTRAINT_SOURCE_FQN, """
                package com.example.greeting;

                import jakarta.validation.Constraint;
                import jakarta.validation.ConstraintValidator;
                import jakarta.validation.ConstraintValidatorContext;
                import jakarta.validation.Payload;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.PARAMETER, ElementType.FIELD})
                @Retention(RetentionPolicy.RUNTIME)
                @Constraint(validatedBy = NotBlankConstraint.Validator.class)
                public @interface NotBlankConstraint {
                    String message() default "must not be blank";

                    Class<?>[] groups() default {};

                    Class<? extends Payload>[] payload() default {};

                    class Validator implements ConstraintValidator<NotBlankConstraint, String> {
                        @Override
                        public boolean isValid(String value, ConstraintValidatorContext context) {
                            return value != null && !value.isBlank();
                        }
                    }
                }
                """);
        JavaFileObject cascadedRequestSource = SourceFiles.inline(CASCADED_REQUEST_SOURCE_FQN, """
                package com.example.greeting;

                import jakarta.validation.constraints.NotBlank;

                public record CascadedGreetingRequest(@NotBlank String name) {}
                """);
        JavaFileObject cascadedToolSource = SourceFiles.inline(CASCADED_TOOLS_SOURCE_FQN, """
                package com.example.greeting;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import jakarta.validation.Valid;

                public class CascadedGreetingTools {

                    @Inject
                    public CascadedGreetingTools() {}

                    @McpTool(name = "greeting.cascaded", description = "Composes a greeting from a validated request.")
                    public Future<String> compose(
                            @McpToolParam(name = "request", description = "The request to validate.")
                            @Valid CascadedGreetingRequest request) {
                        return Future.succeededFuture("Hello, " + request.name() + "!");
                    }
                }
                """);
        this.result = ProcessorTestHarness.run(
                new McpToolProcessor(),
                toolSource,
                sanitizerSource,
                constraintSource,
                cascadedRequestSource,
                cascadedToolSource);
        result.assertSuccess();

        this.invoker = loadInvoker(TOOLS_SOURCE_FQN, INVOKER_FQN, SANITIZER_SOURCE_FQN);
        this.cascadedInvoker = loadInvoker(CASCADED_TOOLS_SOURCE_FQN, CASCADED_INVOKER_FQN, null);

        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
        McpToolRegistry registry = McpToolRegistry.build(Set.of(invoker, cascadedInvoker));
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

        McpInputLifecycleObservationITFixture.CapableObserver capable =
                new McpInputLifecycleObservationITFixture.CapableObserver();
        this.session = capable.session();

        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                new McpRequestDispatcher(
                        config,
                        securityRuntime,
                        Set.of(capable),
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

    /**
     * Compiles and starts the fixture.
     *
     * @param vertx the owning Vert.x instance
     * @return the started fixture
     * @throws Exception if compilation, class loading, or server startup fails
     */
    static McpGeneratedParameterizedToolITFixture start(Vertx vertx) throws Exception {
        return new McpGeneratedParameterizedToolITFixture(vertx);
    }

    HttpServer server() {
        return server;
    }

    int port() {
        return port;
    }

    /** The loaded real generated invoker — exposed so the test can assert on its descriptor directly. */
    McpToolInvoker invoker() {
        return invoker;
    }

    /** The value-observation session contributed to the dispatcher, recording {@code onToolInput}. */
    McpInputLifecycleObservationITFixture.CapableSession session() {
        return session;
    }

    /** The raw {@code compile-testing} result, exposed so the test can independently assert on it. */
    ProcessorTestHarness.Result result() {
        return result;
    }

    /**
     * Loads the generated {@code GreetingTools} and {@code UpperCaseSanitizer} beans and constructs
     * the generated invoker through its real four-argument {@code @Inject} constructor: the tool
     * bean, a real {@link McpToolRuntimeFactory}, a real {@link InputObjectProcessor} whose
     * sanitizer resolver returns the loaded {@code UpperCaseSanitizer} instance for its own generated
     * type, and an empty {@code Optional<jakarta.validation.Validator>} — proving the resolved chain
     * the emitter copied onto the carrier component actually reaches the real engine, not a
     * hand-written stand-in.
     */
    private McpToolInvoker loadInvoker(String toolsSourceFqn, String invokerFqn, String sanitizerSourceFqn)
            throws Exception {
        Class<?> toolsClass = result.loadGeneratedClass(toolsSourceFqn);
        Class<?> sanitizerClass = sanitizerSourceFqn == null ? null : result.loadGeneratedClass(sanitizerSourceFqn);
        Class<?> invokerClass = result.loadGeneratedClass(invokerFqn);
        Object toolsInstance = toolsClass.getDeclaredConstructor().newInstance();
        Sanitizer sanitizerInstance = sanitizerClass == null
                ? null
                : (Sanitizer) sanitizerClass.getDeclaredConstructor().newInstance();

        InputObjectProcessor processor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                },
                sanitizerType -> {
                    if (sanitizerSourceFqn != null && sanitizerType.getName().equals(sanitizerSourceFqn)) {
                        return sanitizerInstance;
                    }
                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                });

        Constructor<?> invokerConstructor = invokerClass.getDeclaredConstructor(
                toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class, Optional.class);
        invokerConstructor.setAccessible(true);
        return (McpToolInvoker) invokerConstructor.newInstance(
                toolsInstance, McpToolRuntimeFactoryTestSupport.factory(), processor, Optional.empty());
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
