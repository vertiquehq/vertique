// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.mcp.server.McpTypedPolicyServiceIT.ContentService;
import dev.vertique.mcp.server.McpTypedPolicyServiceIT.CountingContentService;
import dev.vertique.mcp.server.McpTypedPolicyServiceIT.DocumentService;
import dev.vertique.mcp.server.McpTypedPolicyServiceIT.OwnershipCheckingDocumentHandler;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
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
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.rest.security.dispatch.SecurityContextServiceDispatchEncoder;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.authz.DefaultActionRegistry;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import dev.vertique.services.ServiceClientFactory;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceSupervisor;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.interceptor.ServiceAuthorizationInterceptor;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import jakarta.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.JavaFileObject;
import org.mockito.Mockito;

/**
 * Framework wiring for {@link McpTypedPolicyServiceIT}.
 *
 * <p>Composes one real MCP mount over tool invokers that publish a typed access policy, behind real
 * port-0 identity establishment, the real {@link McpRequestDispatcher} and a real {@link
 * McpPolicyEnforcer}, with real service dispatch behind the tools: the services are registered by hand
 * with the real {@link ServiceAuthorizationInterceptor} on event bus consumers and reached through a
 * {@link ServiceClientFactory} proxy that captures the caller bound on the MCP request. Two event
 * recorders keep the MCP boundary and the service boundary apart.
 *
 * <p>Typed invokers override {@code accessPolicy()} and publish the closed {@code DENY_ALL}
 * placeholder descriptor access, exactly as generated invokers do.
 */
final class McpTypedPolicyServiceITFixture {

    // --- Wire constants ---

    static final String REQUEST_PATH = "/mcp/";
    static final String SCHEME = "bearer";

    private static final String LOOPBACK = "127.0.0.1";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final long WAIT_SECONDS = 10;
    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";
    private static final String DOCUMENT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"documentId\":"
            + "{\"type\":\"string\"}},\"required\":[\"documentId\"],\"additionalProperties\":false}";

    // --- Tool names ---

    static final String PUBLIC_TOOL = "typed.public";
    static final String OPS_TOOL = "typed.ops";
    static final String AUDITOR_TOOL = "typed.auditor";
    static final String SCOPED_TOOL = "typed.scoped";
    static final String AUTHENTICATED_TOOL = "typed.authenticated";
    static final String ACTION_TOOL = "typed.action";
    static final String EDIT_TOOL = "typed.edit";
    static final String RENAME_TOOL = "typed.rename";
    static final String LEGACY_EDIT_TOOL = "legacy.edit";
    static final String UNKNOWN_TOOL = "typed.doesNotExist";

    // --- Callers: the credential carries the subject, the roles and the scopes ---

    /** Holds the {@code ops} role only. */
    static final String ALICE = bearer("alice", "ops", "");

    /** Holds the {@code ops} and {@code editor} roles. */
    static final String BOB = bearer("bob", "ops,editor", "");

    /** Holds the {@code ops} and {@code editor} roles; owns document {@code doc-carol}. */
    static final String CAROL = bearer("carol", "ops,editor", "");

    /** Holds the {@code auditor} role only. */
    static final String IVY = bearer("ivy", "auditor", "");

    /** Holds the {@code ops} and {@code executor} roles; the scripted authorizer permits {@code executor}. */
    static final String ERIN = bearer("erin", "ops,executor", "");

    /** Holds the {@code reports:read} scope and no role. */
    static final String SAM = bearer("sam", "", "reports:read");

    /** The same subject as {@link #ALICE} after the {@code ops} role was withdrawn. */
    static final String ALICE_WITHOUT_OPS = bearer("alice", "viewer", "");

    private McpTypedPolicyServiceITFixture() {}

    static String bearer(String subject, String roles, String scopes) {
        return "Bearer " + subject + "|" + roles + "|" + scopes;
    }

    // --- Tool invokers ---

    /**
     * A hand-written invoker whose descriptor and optional typed policy hook are fixed at construction.
     *
     * <p>Counting the calls to {@link #accessPolicy()} proves how often the hook is read.
     */
    static final class PolicyToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final Optional<Class<? extends AccessPolicy>> hook;

        @Nullable
        private final Throwable hookFailure;

        @Nullable
        private volatile McpToolDescriptor laterDescriptor;

        private final Function<Map<String, Object>, Future<McpToolResult<?>>> behavior;
        private final AtomicInteger hookCalls = new AtomicInteger();
        private final AtomicInteger invocations = new AtomicInteger();

        private PolicyToolInvoker(
                McpToolDescriptor descriptor,
                @Nullable Optional<Class<? extends AccessPolicy>> hook,
                Function<Map<String, Object>, Future<McpToolResult<?>>> behavior) {
            this(descriptor, hook, null, behavior);
        }

        private PolicyToolInvoker(
                McpToolDescriptor descriptor,
                @Nullable Optional<Class<? extends AccessPolicy>> hook,
                @Nullable Throwable hookFailure,
                Function<Map<String, Object>, Future<McpToolResult<?>>> behavior) {
            this.descriptor = descriptor;
            this.hook = hook;
            this.hookFailure = hookFailure;
            this.behavior = behavior;
        }

        /** A typed tool: the restrictive legacy descriptor plus the policy hook. */
        static PolicyToolInvoker typed(
                String name,
                Class<? extends AccessPolicy> policy,
                Function<Map<String, Object>, Future<McpToolResult<?>>> behavior) {
            return new PolicyToolInvoker(
                    descriptorOf(name, CLOSED_OBJECT_SCHEMA, denyAllAccess()), Optional.of(policy), behavior);
        }

        /** A typed tool whose input schema requires a {@code documentId}. */
        static PolicyToolInvoker typedDocument(
                String name,
                Class<? extends AccessPolicy> policy,
                Function<Map<String, Object>, Future<McpToolResult<?>>> behavior) {
            return new PolicyToolInvoker(
                    descriptorOf(name, DOCUMENT_SCHEMA, denyAllAccess()), Optional.of(policy), behavior);
        }

        /** A tool that publishes its own legacy access and no typed policy. */
        static PolicyToolInvoker legacy(
                String name, McpToolAccess access, Function<Map<String, Object>, Future<McpToolResult<?>>> behavior) {
            return new PolicyToolInvoker(descriptorOf(name, CLOSED_OBJECT_SCHEMA, access), Optional.empty(), behavior);
        }

        /** A typed tool that also publishes the given legacy access instead of the restrictive placeholder. */
        static PolicyToolInvoker typedWithLegacyAccess(
                String name, Class<? extends AccessPolicy> policy, McpToolAccess access) {
            return new PolicyToolInvoker(
                    descriptorOf(name, CLOSED_OBJECT_SCHEMA, access), Optional.of(policy), neverInvoked(name));
        }

        /** A tool whose hook returns a null value. */
        static PolicyToolInvoker withNullHook(String name) {
            return new PolicyToolInvoker(
                    descriptorOf(name, CLOSED_OBJECT_SCHEMA, denyAllAccess()), null, neverInvoked(name));
        }

        /**
         * A tool whose hook throws {@code failure} when read.
         *
         * @param failure a {@link RuntimeException} or an {@link Error}
         */
        static PolicyToolInvoker withThrowingHook(String name, Throwable failure) {
            if (!(failure instanceof RuntimeException) && !(failure instanceof Error)) {
                throw new IllegalArgumentException("a hook can only throw an unchecked failure: " + failure);
            }
            return new PolicyToolInvoker(
                    descriptorOf(name, CLOSED_OBJECT_SCHEMA, denyAllAccess()),
                    Optional.empty(),
                    failure,
                    neverInvoked(name));
        }

        /** The typed policy hook; counts every read. */
        @Override
        public Optional<Class<? extends AccessPolicy>> accessPolicy() {
            hookCalls.incrementAndGet();
            if (hookFailure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (hookFailure instanceof Error error) {
                throw error;
            }
            return hook;
        }

        @Override
        public McpToolDescriptor descriptor() {
            McpToolDescriptor later = laterDescriptor;
            return later != null ? later : descriptor;
        }

        /**
         * From now on, publishes the same tool with {@code access}, as an invoker whose descriptor is not
         * stable would; the registry keeps the descriptor it read when it registered the invoker.
         */
        void publishLaterAccess(McpToolAccess access) {
            laterDescriptor = descriptorOf(descriptor.name(), descriptor.inputSchema(), access);
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return arguments;
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocations.incrementAndGet();
                    return behavior.apply(arguments);
                }
            };
        }

        int hookCalls() {
            return hookCalls.get();
        }

        int invocations() {
            return invocations.get();
        }

        void resetInvocations() {
            invocations.set(0);
        }
    }

    static Function<Map<String, Object>, Future<McpToolResult<?>>> answering(String text) {
        return arguments -> Future.succeededFuture(McpToolResult.text(text));
    }

    static Function<Map<String, Object>, Future<McpToolResult<?>>> neverInvoked(String name) {
        return arguments -> {
            throw new AssertionError("tool " + name + " must not be invoked in this scenario");
        };
    }

    private static McpToolDescriptor descriptorOf(String name, String inputSchema, McpToolAccess access) {
        return new McpToolDescriptor(
                name, null, "Typed policy fixture tool " + name + ".", ANNOTATIONS, inputSchema, null, access);
    }

    static McpToolAccess denyAllAccess() {
        return new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null);
    }

    static McpToolAccess permitAllAccess() {
        return new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
    }

    static McpToolAccess rolesAccess(String role) {
        return new McpToolAccess(McpAccessMode.RESTRICTED, List.of(role), null);
    }

    /** The action registry the typed action tool resolves against. */
    static ActionRegistry actionRegistryOf(String... actions) {
        List<ActionDefinition> definitions = Arrays.stream(actions)
                .map(action -> new ActionDefinition(ActionRef.parse(action)))
                .toList();
        return new DefaultActionRegistry(Set.<ActionContributor>of(() -> definitions));
    }

    // --- Security stack shared by the startup and request scenarios ---

    private static SecurityEventObserver recordingObserver(List<AuthorizationDecisionEvent> target) {
        return new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                target.add(event);
                return Future.succeededFuture();
            }
        };
    }

    /** The MCP side of the composition: runtime, holder, event recorder, authorizer and mount factory. */
    private static final class McpSecurity {
        final List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        final ScriptedAuthorizer authorizer = new ScriptedAuthorizer();
        final ContextHolder holder = new DefaultContextHolder();
        final SecurityRuntime runtime = new HolderBackedSecurityRuntime((securityContext, secure) -> null);
        private final Optional<Authorizer> installedAuthorizer;

        McpSecurity(boolean authorizerInstalled) {
            this.installedAuthorizer = authorizerInstalled ? Optional.of(authorizer) : Optional.empty();
        }

        McpRouterMount mount(McpServerConfig config, McpServerConfigValidator validator, McpToolRegistry registry) {
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of(recordingObserver(events))),
                    holder,
                    runtime,
                    installedAuthorizer));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            IdentityResolutionMiddleware identity = new IdentityResolutionMiddleware(
                    Set.of(new SubjectIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    runtime,
                    holder);
            McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                    config,
                    runtime,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    httpConfig,
                    registry,
                    policyEnforcer,
                    holder,
                    new CorrelationContextFactory(Optional.empty()));
            return new McpRouterMount(
                    config,
                    validator,
                    dispatcher,
                    Set.of(new MultiCallerBearerHandler()),
                    identity,
                    httpConfig,
                    registry,
                    installedAuthorizer);
        }
    }

    private static McpServerConfig config(boolean enabled, @Nullable String scheme) {
        return McpServerConfig.builder()
                .enabled(enabled)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .authenticationScheme(scheme)
                .build();
    }

    // --- Startup composition ---

    /** How far one composition got. */
    enum Stage {
        /** The tool registry refused the contributed invokers. */
        REGISTRY_REJECTED,
        /** The registry built, but the mount refused to start. */
        MOUNT_REJECTED,
        /** The mount started. */
        MOUNTED
    }

    /**
     * The outcome of one composition attempt.
     *
     * @param stage how far the composition got
     * @param failure the configuration error that stopped it, or {@code null}
     */
    record ComposeResult(Stage stage, @Nullable ConfigurationException failure) {}

    /**
     * The one startup composition under test: whether the server is enabled, the configured scheme,
     * whether an {@link Authorizer} is installed, and how the validator is built.
     *
     * @param enabled whether the MCP server is enabled
     * @param scheme the configured authentication scheme, or {@code null} for none
     * @param authorizerInstalled whether an {@link Authorizer} is installed
     * @param validator creates the validator under test
     */
    record Composition(
            boolean enabled,
            @Nullable String scheme,
            boolean authorizerInstalled,
            java.util.function.Supplier<McpServerConfigValidator> validator) {

        /** An enabled server with the bearer scheme, no authorizer and the no-argument validator. */
        static Composition enabledWithScheme() {
            return new Composition(true, SCHEME, false, McpServerConfigValidator::new);
        }

        Composition withoutScheme() {
            return new Composition(enabled, null, authorizerInstalled, validator);
        }

        Composition disabled() {
            return new Composition(false, scheme, authorizerInstalled, validator);
        }

        Composition withAuthorizer() {
            return new Composition(enabled, scheme, true, validator);
        }

        /** Constructs the validator with the given action registry (possibly empty). */
        Composition withValidatorRegistry(Optional<ActionRegistry> actionRegistry) {
            return new Composition(
                    enabled, scheme, authorizerInstalled, () -> new McpServerConfigValidator(actionRegistry));
        }
    }

    /** Builds the registry and starts the mount for {@code invokers}; no network is bound. */
    static ComposeResult compose(Vertx vertx, Set<McpToolInvoker> invokers, Composition composition) throws Exception {
        McpToolRegistry registry;
        try {
            registry = McpToolRegistry.build(invokers);
        } catch (ConfigurationException rejected) {
            return new ComposeResult(Stage.REGISTRY_REJECTED, rejected);
        }
        McpServerConfigValidator validator = composition.validator().get();
        McpSecurity security = new McpSecurity(composition.authorizerInstalled());
        McpRouterMount mount;
        try {
            mount = security.mount(config(composition.enabled(), composition.scheme()), validator, registry);
        } catch (ConfigurationException rejected) {
            return new ComposeResult(Stage.MOUNT_REJECTED, rejected);
        }
        await(mount.createRouter(vertx));
        return new ComposeResult(Stage.MOUNTED, null);
    }

    // --- Services behind the tools ---

    /**
     * The real service dispatch behind the tools: policy-protected services on event bus consumers
     * guarded by the real {@link ServiceAuthorizationInterceptor}, reached through generated-style
     * client proxies.
     */
    static final class Services implements AutoCloseable {
        final CountingContentService content = new CountingContentService();
        final OwnershipCheckingDocumentHandler documents = new OwnershipCheckingDocumentHandler();
        final List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        private final List<MessageConsumer<?>> consumers = new ArrayList<>();
        private final Resilience resilience;
        private final ContentService contentProxy;
        private final DocumentService documentProxy;

        Services(Vertx vertx, Authorizer authorizer, ActionRegistry actions, ContextHolder holder) throws Exception {
            this.resilience = Resilience.create(vertx);
            try {
                registerCodec(vertx, "dispatch.envelope");
                registerCodec(vertx, "dispatch.result");
                ServiceContractRegistry registry = ServiceContractRegistry.build(
                        Set.<Object>of(content, documents), Set.of(), new JsonObject(), Map.of());
                startConsumers(vertx, registry, authorizer, actions, holder);
                ServiceClientFactory clients = clients(vertx, registry, holder);
                this.contentProxy = clients.create(ContentService.class);
                this.documentProxy = clients.create(DocumentService.class);
            } catch (Throwable startFailure) {
                try {
                    close();
                } catch (Exception suppressed) {
                    startFailure.addSuppressed(suppressed);
                }
                throw startFailure;
            }
        }

        ContentService contentProxy() {
            return contentProxy;
        }

        DocumentService documentProxy() {
            return documentProxy;
        }

        int effects() {
            return content.effects() + documents.effects();
        }

        void reset() {
            content.reset();
            documents.reset();
            events.clear();
        }

        private static void registerCodec(Vertx vertx, String name) {
            try {
                vertx.eventBus().registerCodec(new LocalMessageCodec<>(name));
            } catch (IllegalStateException alreadyRegistered) {
                // The codec is shared by every registration on this Vert.x instance.
            }
        }

        private void startConsumers(
                Vertx vertx,
                ServiceContractRegistry registry,
                Authorizer authorizer,
                ActionRegistry actions,
                ContextHolder holder)
                throws Exception {
            List<ServiceMethodMeta> metas = registry.entries().stream()
                    .flatMap(entry -> entry.operations().values().stream())
                    .toList();
            ServiceAuthorizationInterceptor gate = new ServiceAuthorizationInterceptor(
                    Optional.of(authorizer),
                    Optional.of(actions),
                    new SecurityEventEmitter(Set.of(recordingObserver(events))),
                    holder,
                    Set.copyOf(metas));
            for (ServiceMethodMeta meta : metas) {
                ServiceMethodInvoker invoker =
                        new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(gate), null);
                MessageConsumer<DispatchEnvelope<?>> consumer = vertx.eventBus().consumer(meta.address(), invoker);
                consumers.add(consumer);
                await(consumer.completion());
            }
        }

        private ServiceClientFactory clients(Vertx vertx, ServiceContractRegistry registry, ContextHolder holder) {
            ServiceSupervisor supervisor = Mockito.mock(ServiceSupervisor.class);
            Mockito.when(supervisor.isAvailable(Mockito.any())).thenReturn(true);
            ServiceRequestSender sender = new ServiceRequestSender(
                    new EventBusClient(vertx, new EventBusExceptionMapper()),
                    supervisor,
                    new ServiceResilienceConfigAdapter(
                            resilience, new ServicesConfig(null, List.of()), Map.of(), Optional.empty()));
            DispatchEnvelopeBuilder envelopes = new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                    new ServiceDispatchContextRegistry(Set.of(new SecurityContextServiceDispatchEncoder()), Set.of()),
                    holder));
            return new ServiceClientFactory(sender, registry, envelopes);
        }

        @Override
        public void close() throws Exception {
            List<Future<?>> closing = new ArrayList<>();
            for (MessageConsumer<?> consumer : consumers) {
                closing.add(consumer.unregister());
            }
            closing.add(resilience.close());
            Exception failure = null;
            for (Future<?> pending : closing) {
                try {
                    await(pending);
                } catch (Exception e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    // --- Tool sets ---

    /** Supplies the invokers a deployment contributes, given the services behind them. */
    @FunctionalInterface
    interface ToolSource {
        Set<McpToolInvoker> create(Services services) throws Exception;
    }

    /** The hand-written typed tools, one legacy control tool, and the tools that call services. */
    static ToolSource manualTools(Map<String, PolicyToolInvoker> byName) {
        return services -> {
            List<PolicyToolInvoker> tools = List.of(
                    PolicyToolInvoker.typed(
                            PUBLIC_TOOL, McpTypedPolicyServiceIT.PublicPolicy.class, answering(PUBLIC_TOOL + "-ok")),
                    PolicyToolInvoker.typed(
                            OPS_TOOL, McpTypedPolicyServiceIT.OpsPolicy.class, answering(OPS_TOOL + "-ok")),
                    PolicyToolInvoker.typed(
                            AUDITOR_TOOL, McpTypedPolicyServiceIT.AuditorPolicy.class, answering(AUDITOR_TOOL + "-ok")),
                    PolicyToolInvoker.typed(
                            SCOPED_TOOL,
                            McpTypedPolicyServiceIT.ReportScopePolicy.class,
                            answering(SCOPED_TOOL + "-ok")),
                    PolicyToolInvoker.typed(
                            AUTHENTICATED_TOOL,
                            McpTypedPolicyServiceIT.AuthenticatedPolicy.class,
                            answering(AUTHENTICATED_TOOL + "-ok")),
                    PolicyToolInvoker.typed(
                            ACTION_TOOL,
                            McpTypedPolicyServiceIT.ReportActionPolicy.class,
                            answering(ACTION_TOOL + "-ok")),
                    PolicyToolInvoker.typed(
                            EDIT_TOOL, McpTypedPolicyServiceIT.OpsPolicy.class, arguments -> services.contentProxy()
                                    .edit("typed")
                                    .map(McpTypedPolicyServiceITFixture::text)),
                    PolicyToolInvoker.typedDocument(
                            RENAME_TOOL, McpTypedPolicyServiceIT.OpsPolicy.class, arguments -> services.documentProxy()
                                    .rename(String.valueOf(arguments.get("documentId")))
                                    .map(McpTypedPolicyServiceITFixture::text)),
                    PolicyToolInvoker.legacy(LEGACY_EDIT_TOOL, rolesAccess("ops"), arguments -> services.contentProxy()
                            .edit("legacy")
                            .map(McpTypedPolicyServiceITFixture::text)));
            tools.forEach(tool -> byName.put(tool.descriptor().name(), tool));
            return Set.copyOf(tools);
        };
    }

    private static McpToolResult<?> text(String value) {
        return McpToolResult.text(value);
    }

    /** Tool names published by the real annotation processor for {@link #generatedSources()}. */
    static final String GENERATED_OPS_TOOL = "generated.ops";

    static final String GENERATED_AUDITOR_TOOL = "generated.auditor";
    static final String GENERATED_EDIT_TOOL = "generated.edit";

    private static final String GENERATED_PACKAGE = "com.example.typed";

    private static JavaFileObject[] generatedSources() {
        return new JavaFileObject[] {
            SourceFiles.inline(GENERATED_PACKAGE + ".OpsPolicy", """
                package com.example.typed;

                @jakarta.annotation.security.RolesAllowed("ops")
                public interface OpsPolicy extends dev.vertique.security.authz.AccessPolicy {}
                """),
            SourceFiles.inline(GENERATED_PACKAGE + ".AuditorPolicy", """
                package com.example.typed;

                @jakarta.annotation.security.RolesAllowed("auditor")
                public interface AuditorPolicy extends dev.vertique.security.authz.AccessPolicy {}
                """),
            SourceFiles.inline(GENERATED_PACKAGE + ".TypedTools", """
                package com.example.typed;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.security.authz.RequiresPolicy;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.function.Function;

                public class TypedTools {
                    private final Function<String, Future<String>> edit;

                    @Inject
                    public TypedTools(Function<String, Future<String>> edit) {
                        this.edit = edit;
                    }

                    @McpTool(name = "generated.ops", description = "Operations only.")
                    @RequiresPolicy(OpsPolicy.class)
                    public Future<String> ops() {
                        return Future.succeededFuture("generated.ops-ok");
                    }

                    @McpTool(name = "generated.auditor", description = "Auditors only.")
                    @RequiresPolicy(AuditorPolicy.class)
                    public Future<String> auditor() {
                        return Future.succeededFuture("generated.auditor-ok");
                    }

                    @McpTool(name = "generated.edit", description = "Operations, then the protected service.")
                    @RequiresPolicy(OpsPolicy.class)
                    public Future<String> edit() {
                        return edit.apply("generated");
                    }
                }
                """)
        };
    }

    /**
     * Compiles typed tools with the real {@link McpToolProcessor} and loads their generated invokers,
     * handing the tool bean the protected content service.
     */
    static ToolSource generatedTools() {
        return services -> {
            ProcessorTestHarness.Result result = ProcessorTestHarness.run(new McpToolProcessor(), generatedSources());
            if (result.compilation().errors().size() > 0) {
                throw new AssertionError("the real processor must accept typed policy declarations: "
                        + result.compilation().errors());
            }
            Class<?> toolsClass = result.loadGeneratedClass(GENERATED_PACKAGE + ".TypedTools");
            Function<String, Future<String>> edit = services.contentProxy()::edit;
            Object toolsInstance =
                    toolsClass.getDeclaredConstructor(Function.class).newInstance(edit);
            Set<McpToolInvoker> invokers = new java.util.HashSet<>();
            for (String method : List.of("ops", "auditor", "edit")) {
                Class<?> invokerClass =
                        result.loadGeneratedClass(GENERATED_PACKAGE + ".TypedTools_" + method + "_McpToolInvoker");
                Constructor<?> constructor = invokerClass.getDeclaredConstructor(
                        toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class, Optional.class);
                constructor.setAccessible(true);
                invokers.add((McpToolInvoker) constructor.newInstance(
                        toolsInstance,
                        McpToolRuntimeFactoryTestSupport.factory(),
                        InputObjectProcessor.createDefault(
                                canonicalizerType -> {
                                    throw new IllegalArgumentException(
                                            "unresolvable canonicalizer " + canonicalizerType);
                                },
                                sanitizerType -> {
                                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                                }),
                        Optional.empty()));
            }
            return invokers;
        };
    }

    // --- Running deployment ---

    /** What one {@code tools/call} did, as seen by the client. */
    enum Outcome {
        /** The tool ran and returned a result. */
        SUCCEEDED,
        /** The tool was admitted but its invocation failed. */
        FAILED_AFTER_ADMISSION,
        /** The generic invalid-params refusal that an unknown and a denied tool share. */
        REFUSED_AS_INVALID_PARAMS,
        /** Anything else. */
        UNEXPECTED
    }

    /**
     * One response, as seen by the client.
     *
     * @param status the HTTP status
     * @param body the exact response bytes
     * @param headers the response headers without the wall-clock {@code date}, names lower-cased
     */
    record Reply(int status, Buffer body, Map<String, List<String>> headers) {

        static Reply of(HttpResponse<Buffer> response) {
            Map<String, List<String>> headers = response.headers().entries().stream()
                    .filter(entry -> !"date".equalsIgnoreCase(entry.getKey()))
                    .collect(Collectors.groupingBy(
                            entry -> entry.getKey().toLowerCase(Locale.ROOT),
                            TreeMap::new,
                            Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
            return new Reply(response.statusCode(), response.body(), headers);
        }

        /** The tool names of a {@code tools/list} answer, in published order. */
        List<String> toolNames() {
            JsonObject result = new JsonObject(body.toString()).getJsonObject("result");
            if (status != 200 || result == null) {
                throw new AssertionError("tools/list did not answer with a result: " + status + " " + body);
            }
            List<String> names = new ArrayList<>();
            for (Object tool : result.getJsonArray("tools")) {
                names.add(((JsonObject) tool).getString("name"));
            }
            return names;
        }

        Outcome outcome() {
            String text = body.toString();
            if (status == 400) {
                JsonObject error = new JsonObject(text).getJsonObject("error");
                return error != null && error.getInteger("code") == -32602
                        ? Outcome.REFUSED_AS_INVALID_PARAMS
                        : Outcome.UNEXPECTED;
            }
            if (!text.startsWith(SSE_PREFIX)) {
                return Outcome.UNEXPECTED;
            }
            JsonObject data = new JsonObject(text.substring(SSE_PREFIX.length()).stripTrailing());
            JsonObject result = data.getJsonObject("result");
            if (result == null) {
                // An invocation that fails after admission settles as an SSE-framed internal error.
                return status == 500 && data.getJsonObject("error") != null
                        ? Outcome.FAILED_AFTER_ADMISSION
                        : Outcome.UNEXPECTED;
            }
            if (status != 200) {
                return Outcome.UNEXPECTED;
            }
            return Boolean.TRUE.equals(result.getBoolean("isError"))
                    ? Outcome.FAILED_AFTER_ADMISSION
                    : Outcome.SUCCEEDED;
        }

        /** The text of the first content block of a successful call. */
        String resultText() {
            JsonObject data = new JsonObject(
                    body.toString().substring(SSE_PREFIX.length()).stripTrailing());
            return data.getJsonObject("result")
                    .getJsonArray("content")
                    .getJsonObject(0)
                    .getString("text");
        }
    }

    /**
     * One composed server on a port-0 loopback socket, with its client, its services and its
     * observation points. Closing it releases the client, the server and the services; the owning
     * {@link Vertx} is closed by the caller afterwards.
     */
    static final class Deployment implements AutoCloseable {
        private final McpSecurity security;
        private final Services services;
        private final HttpServer server;
        private final HttpClient rawClient;
        private final WebClient client;
        private final Map<String, PolicyToolInvoker> manualTools;

        private Deployment(
                McpSecurity security,
                Services services,
                HttpServer server,
                HttpClient rawClient,
                Map<String, PolicyToolInvoker> manualTools) {
            this.security = security;
            this.services = services;
            this.server = server;
            this.rawClient = rawClient;
            this.client = WebClient.wrap(rawClient);
            this.manualTools = manualTools;
        }

        /** Starts the hand-written typed tools with every collaborator installed. */
        static Deployment startManual(Vertx vertx) throws Exception {
            Map<String, PolicyToolInvoker> byName = new LinkedHashMap<>();
            return start(vertx, manualTools(byName), byName);
        }

        /** Starts the tools generated by the real annotation processor with every collaborator installed. */
        static Deployment startGenerated(Vertx vertx) throws Exception {
            return start(vertx, generatedTools(), Map.of());
        }

        /**
         * Starts a deployment around exactly the tools {@code tools} creates, with every collaborator
         * installed.
         */
        static Deployment start(Vertx vertx, ToolSource tools, Map<String, PolicyToolInvoker> byName) throws Exception {
            McpSecurity security = new McpSecurity(true);
            ActionRegistry actions = actionRegistryOf(McpTypedPolicyServiceIT.REPORT_ACTION);
            Services services = new Services(vertx, security.authorizer, actions, security.holder);
            HttpClient rawClient = vertx.createHttpClient();
            HttpServer server = null;
            try {
                McpToolRegistry registry = McpToolRegistry.build(tools.create(services));
                McpServerConfig config = config(true, SCHEME);
                McpRouterMount mount =
                        security.mount(config, new McpServerConfigValidator(Optional.of(actions)), registry);
                Router router = Router.router(vertx);
                router.route().handler(new RequestContextLifecycle());
                router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
                server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
                return new Deployment(security, services, server, rawClient, byName);
            } catch (Throwable startFailure) {
                try {
                    release(server, rawClient, services);
                } catch (Exception suppressed) {
                    startFailure.addSuppressed(suppressed);
                }
                throw startFailure;
            }
        }

        // --- Observation points ---

        Services services() {
            return services;
        }

        ScriptedAuthorizer authorizer() {
            return security.authorizer;
        }

        PolicyToolInvoker tool(String name) {
            PolicyToolInvoker tool = manualTools.get(name);
            if (tool == null) {
                throw new IllegalArgumentException("no hand-written tool " + name);
            }
            return tool;
        }

        /** The MCP-boundary decision events for {@code toolName}. */
        List<AuthorizationDecisionEvent> mcpEventsFor(String toolName) {
            return security.events.stream()
                    .filter(event -> toolName.equals(event.request().resource().id()))
                    .toList();
        }

        List<AuthorizationDecisionEvent> mcpEvents() {
            return List.copyOf(security.events);
        }

        List<AuthorizationDecisionEvent> serviceEvents() {
            return List.copyOf(services.events);
        }

        /** Clears every counter and recorded event so the next phase starts from zero. */
        void resetObservations() {
            security.events.clear();
            security.authorizer.reset();
            services.reset();
            manualTools.values().forEach(PolicyToolInvoker::resetInvocations);
        }

        // --- Requests ---

        Reply list(@Nullable String authorization) throws Exception {
            JsonObject params = new JsonObject().put("_meta", meta());
            return send(authorization, "tools/list", "tools/list", params);
        }

        Reply call(@Nullable String authorization, String toolName) throws Exception {
            return call(authorization, toolName, null);
        }

        Reply call(@Nullable String authorization, String toolName, @Nullable JsonObject arguments) throws Exception {
            JsonObject params = new JsonObject().put("_meta", meta()).put("name", toolName);
            if (arguments != null) {
                params.put("arguments", arguments);
            }
            return send(authorization, "tools/call", toolName, params);
        }

        private Reply send(@Nullable String authorization, String method, String name, JsonObject params)
                throws Exception {
            HttpRequest<Buffer> request = client.post(server.actualPort(), LOOPBACK, REQUEST_PATH)
                    .putHeader("content-type", "application/json")
                    .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .putHeader("Mcp-Method", method)
                    .putHeader("Mcp-Name", name);
            if (authorization != null) {
                request = request.putHeader("Authorization", authorization);
            }
            Buffer body = new JsonObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", method)
                    .put("params", params)
                    .toBuffer();
            return Reply.of(await(request.sendBuffer(body)));
        }

        private static JsonObject meta() {
            return new JsonObject()
                    .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                    .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        }

        // --- Cleanup ---

        @Override
        public void close() throws Exception {
            release(server, rawClient, services);
        }

        /** Closes the client and the server, then the services, reporting the first failure. */
        private static void release(@Nullable HttpServer server, HttpClient rawClient, Services services)
                throws Exception {
            Exception failure = null;
            List<Future<?>> closing = new ArrayList<>();
            closing.add(rawClient.close());
            if (server != null) {
                closing.add(server.close());
            }
            for (Future<?> pending : closing) {
                try {
                    await(pending);
                } catch (Exception e) {
                    failure = remember(failure, e);
                }
            }
            try {
                services.close();
            } catch (Exception e) {
                failure = remember(failure, e);
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static Exception remember(@Nullable Exception first, Exception next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // --- Authentication and authorization doubles ---

    /** An {@link Authorizer} that counts calls, permits the {@code executor} role, and can be told to fail. */
    static final class ScriptedAuthorizer implements Authorizer {

        /** How the authorizer answers. */
        enum Mode {
            /** Permit the callers holding the executor role. */
            DELEGATE,
            /** Throw instead of answering. */
            THROW,
            /** Return a failed future. */
            FAILED_FUTURE
        }

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DELEGATE);
        private final List<String> actions = new CopyOnWriteArrayList<>();

        void mode(Mode next) {
            mode.set(next);
        }

        int calls() {
            return calls.get();
        }

        /** The action strings every call asked about. */
        List<String> askedActions() {
            return List.copyOf(actions);
        }

        void reset() {
            calls.set(0);
            actions.clear();
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            return answer(request.securityContext(), request.action());
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            return answer(ctx, action.value());
        }

        private Future<AuthorizationDecision> answer(SecurityContext ctx, String action) {
            calls.incrementAndGet();
            actions.add(action);
            return switch (mode.get()) {
                case THROW -> throw new IllegalStateException("evaluator failure");
                case FAILED_FUTURE -> Future.failedFuture("evaluator failed");
                case DELEGATE ->
                    Future.succeededFuture(
                            ctx.authorization().valuesOf(AuthorityKind.ROLE).contains("executor")
                                    ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                                    : AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
            };
        }
    }

    /**
     * Resolves the canonical anonymous identity from empty evidence and the {@code sub}-named user
     * otherwise.
     */
    private record SubjectIdentityResolver() implements SecurityIdentityResolver {

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

    /**
     * An optional-authentication bearer scheme over credentials of the form {@code Bearer
     * <subject>|<role,role>|<scope scope>}; an absent credential continues anonymously and a malformed
     * one fails 401.
     */
    private static final class MultiCallerBearerHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return SCHEME;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    context.next();
                    return;
                }
                String[] parts = credential.startsWith("Bearer ")
                        ? credential.substring("Bearer ".length()).split("\\|", -1)
                        : new String[0];
                if (parts.length != 3 || parts[0].isBlank()) {
                    context.fail(401);
                    return;
                }
                RestAuthenticationEvidence.append(
                        context,
                        new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of(parts[0]),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", parts[0])));
                JsonArray roles = new JsonArray();
                Arrays.stream(parts[1].split(","))
                        .filter(role -> !role.isBlank())
                        .forEach(roles::add);
                ((UserContextInternal) context.userContext())
                        .setUser(User.create(new JsonObject()
                                .put("sub", parts[0])
                                .put("roles", roles)
                                .put("scope", parts[2])));
                context.next();
            });
        }
    }
}
