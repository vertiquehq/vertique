// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ALICE;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ALICE_WITHOUT_OPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Deployment;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Outcome;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ToolSource;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the binary compatibility of the {@link McpToolInvoker} contract across the optional typed
 * policy hook, with real compiled classes rather than source-level claims.
 *
 * <p>The pinned baseline interface source is compiled with the raw {@code javax.tools} compiler and
 * two isolated class loader arrangements are built from it:
 *
 * <ul>
 *   <li><strong>An old invoker on the current runtime.</strong> An invoker compiled against the
 *       baseline interface is loaded without that interface, so it links against the current one. It
 *       must load without a linkage failure and keep its listing, invocation and refusal behavior
 *       behind the real {@link McpRequestDispatcher}, {@link McpToolRegistry} and {@link
 *       McpPolicyEnforcer}.
 *   <li><strong>A typed invoker on an old consumer.</strong> An invoker compiled against the current
 *       interface is loaded beside the baseline interface and a consumer that only knows the
 *       descriptor. That consumer must hide and refuse the typed tool for every caller, a permitted
 *       one included, and the tool must never take effect. A permit-all control tool shows the
 *       consumer still serves what it understands. The same typed invoker is then served normally
 *       by the current runtime.
 * </ul>
 *
 * <p>Every assertion is on a runtime outcome: listed names, call outcomes and effect counters. None
 * reads generated metadata text. The baseline source and its digest are test resources, and the
 * digest is asserted against a literal so the fixture cannot drift unnoticed.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpPolicyBinaryCompatibilityTest {

    private static final String BASELINE_SHA256 = "55bde767eb369589d7f672f1942111d3f590b58ae63cd9ec7525aafa4b85badb";
    private static final String BASELINE_SOURCE_RESOURCE = "/policy-compatibility/baseline-McpToolInvoker.java.txt";
    private static final String BASELINE_DIGEST_RESOURCE = "/policy-compatibility/baseline.sha256";
    private static final String INTERFACE_NAME = "dev.vertique.mcp.tool.McpToolInvoker";
    private static final String INTERFACE_CLASS_FILE = "dev/vertique/mcp/tool/McpToolInvoker.class";

    private static final String LEGACY_TOOL = "legacy.report";
    private static final String CONTROL_TOOL = "legacy.open";
    private static final String TYPED_TOOL = "typed.report";

    private static final String LEGACY_INVOKER = "compat.legacy.LegacyRoleInvoker";
    private static final String CONTROL_INVOKER = "compat.legacy.OpenInvoker";
    private static final String TYPED_INVOKER = "compat.typed.TypedOpsInvoker";
    private static final String TYPED_POLICY = "compat.typed.OpsOnlyPolicy";
    private static final String OLD_CONSUMER = "compat.consumer.DescriptorOnlyConsumer";

    private static final String REFUSED = "refused";
    private static final String INVOKED = "invoked";

    private final Deque<AutoCloseable> owned = new ArrayDeque<>();
    private Vertx vertx;

    /** Opens the owned {@link Vertx}; it is closed in {@link #tearDown()}. */
    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    /**
     * Closes every deployment, class loader and temporary directory this test opened, newest first and
     * on every path, then closes the owned {@link Vertx} once nothing is in flight.
     *
     * @throws Exception if a resource could not be released
     */
    @AfterEach
    void tearDown() throws Exception {
        Exception failure = null;
        while (!owned.isEmpty()) {
            try {
                owned.pop().close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(result -> {
            if (result.failed()) {
                closed.completeExceptionally(result.cause());
            } else {
                closed.complete(null);
            }
        });
        try {
            closed.get(10, TimeUnit.SECONDS);
        } catch (Exception closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    @DisplayName("shouldPreserveOldInvokersAndDenyTypedInvokersOnOldConsumers")
    void shouldPreserveOldInvokersAndDenyTypedInvokersOnOldConsumers() throws Exception {
        byte[] baselineBytes = readResource(BASELINE_SOURCE_RESOURCE);
        String baselineSource = new String(baselineBytes, StandardCharsets.UTF_8);
        Path workspace = temporaryWorkspace();

        assertAll(
                "binary compatibility of the typed policy hook",
                () -> baselineSourceIsThePinnedOne(baselineBytes),
                () -> oldInvokerLinksAndBehavesOnTheCurrentRuntime(workspace, baselineSource),
                () -> oldConsumerHidesAndRefusesTheTypedInvoker(workspace, baselineSource),
                () -> currentRuntimeServesTheTypedInvoker(workspace),
                this::hookIsADefaultMethodWithTheFrozenSignature);
    }

    // --- Pinned baseline ---

    private static void baselineSourceIsThePinnedOne(byte[] baselineBytes) throws Exception {
        // Given the baseline interface source and its recorded digest file
        String recorded = new String(readResource(BASELINE_DIGEST_RESOURCE), StandardCharsets.UTF_8)
                .strip()
                .split("\\s+")[0];

        // When the source is hashed
        String actual =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baselineBytes));

        // Then the digest equals the pinned literal and the recorded digest
        assertThat(actual).as("digest of the baseline source").isEqualTo(BASELINE_SHA256);
        assertThat(recorded).as("digest recorded beside the baseline source").isEqualTo(BASELINE_SHA256);
    }

    // --- Direction A: an old binary on the current runtime ---

    private void oldInvokerLinksAndBehavesOnTheCurrentRuntime(Path workspace, String baselineSource) throws Exception {
        // Given an invoker compiled against the baseline interface only
        Path legacyOut = compile(
                workspace.resolve("legacy"),
                Map.of(
                        LEGACY_INVOKER,
                        invokerSource("compat.legacy", "LegacyRoleInvoker", LEGACY_TOOL, rolesAccess("ops"), null),
                        INTERFACE_NAME,
                        baselineSource));
        // and the baseline interface is not part of what the runtime loads, so the invoker links against
        // the current one
        Files.delete(legacyOut.resolve(INTERFACE_CLASS_FILE));
        DirectoryClassLoader loader = own(new DirectoryClassLoader(List.of(legacyOut), testClassLoader()));
        AtomicInteger effects = new AtomicInteger();

        // When the old binary is loaded and used by the current runtime
        Class<?> legacyClass = load(loader, LEGACY_INVOKER);
        assertThat(legacyClass.getInterfaces())
                .as("the old binary implements the current interface")
                .containsExactly(McpToolInvoker.class);
        McpToolInvoker invoker = (McpToolInvoker) instantiate(legacyClass, effects);
        assertThatCode(invoker::descriptor)
                .as("the old binary links without a linkage error")
                .doesNotThrowAnyException();
        Deployment app = startDeployment(Set.of(invoker));

        // Then it is listed for the permitted caller only and invoked once for that caller
        assertThat(app.list(ALICE).toolNames())
                .as("listed for the caller holding the role")
                .contains(LEGACY_TOOL);
        assertThat(app.list(ALICE_WITHOUT_OPS).toolNames())
                .as("hidden from a caller without the role")
                .doesNotContain(LEGACY_TOOL);
        assertThat(app.call(ALICE, LEGACY_TOOL).outcome())
                .as("call by the caller holding the role")
                .isEqualTo(Outcome.SUCCEEDED);
        assertThat(effects).as("effects after the permitted call").hasValue(1);

        // and refused to a denied and an anonymous caller without any further effect
        assertThat(app.call(ALICE_WITHOUT_OPS, LEGACY_TOOL).outcome())
                .as("call by a caller without the role")
                .isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.call(null, LEGACY_TOOL).outcome())
                .as("call by an anonymous caller")
                .isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(effects).as("effects after the refused calls").hasValue(1);
    }

    // --- Direction B: a typed invoker on an old consumer ---

    private void oldConsumerHidesAndRefusesTheTypedInvoker(Path workspace, String baselineSource) throws Exception {
        // Given a typed invoker compiled against the current interface
        Path typedOut = compile(workspace.resolve("typed-for-old"), typedSources());
        // and a baseline interface beside a descriptor-only consumer and a permit-all control tool
        Path consumerOut = compile(
                workspace.resolve("old-consumer"),
                Map.of(
                        CONTROL_INVOKER,
                        invokerSource("compat.legacy", "OpenInvoker", CONTROL_TOOL, permitAllAccess(), null),
                        OLD_CONSUMER,
                        descriptorOnlyConsumerSource(),
                        INTERFACE_NAME,
                        baselineSource));
        DirectoryClassLoader isolated =
                own(new DirectoryClassLoader(List.of(typedOut, consumerOut), testClassLoader()));
        Class<?> baselineInterface = load(isolated, INTERFACE_NAME);
        Class<?> typedClass = load(isolated, TYPED_INVOKER);
        assertThat(baselineInterface)
                .as("the old consumer sees its own baseline interface")
                .isNotSameAs(McpToolInvoker.class);
        assertThat(baselineInterface.getClassLoader()).isSameAs(isolated);
        assertThat(typedClass.getInterfaces()).containsExactly(baselineInterface);
        assertThat(Stream.of(baselineInterface.getDeclaredMethods()).map(Method::getName))
                .as("the baseline interface has no policy hook")
                .doesNotContain("accessPolicy");
        assertThat(Stream.of(typedClass.getDeclaredMethods()).map(Method::getName))
                .as("the typed invoker publishes the hook")
                .contains("accessPolicy");
        AtomicInteger typedEffects = new AtomicInteger();
        AtomicInteger controlEffects = new AtomicInteger();
        OldConsumer consumer = oldConsumer(isolated);
        consumer.register(instantiate(typedClass, typedEffects));
        consumer.register(instantiate(load(isolated, CONTROL_INVOKER), controlEffects));
        Map<String, SecurityContext> callers = new LinkedHashMap<>();
        callers.put("a caller the typed policy permits", caller("alice", "ops"));
        callers.put("a caller the typed policy denies", caller("bob", "viewer"));
        callers.put("an anonymous caller", SecurityContexts.unauthenticated(SecurityIdentity.anonymous()));

        // When each caller lists and calls both tools through the descriptor-only consumer
        for (Map.Entry<String, SecurityContext> entry : callers.entrySet()) {
            String who = entry.getKey();
            SecurityContext caller = entry.getValue();

            // Then the typed tool is hidden and refused, a permitted caller included
            assertThat(consumer.list(caller)).as("listing for " + who).containsExactly(CONTROL_TOOL);
            assertThat(consumer.call(caller, TYPED_TOOL))
                    .as("typed call by " + who)
                    .isEqualTo(REFUSED);
            assertThat(typedEffects)
                    .as("typed effects after the call by " + who)
                    .hasValue(0);

            // and the control tool is still served, so the consumer is not simply refusing everything
            assertThat(consumer.call(caller, CONTROL_TOOL))
                    .as("control call by " + who)
                    .isEqualTo(INVOKED);
        }
        assertThat(controlEffects).as("control effects after every caller").hasValue(3);
        assertThat(typedEffects).as("typed effects after every caller").hasValue(0);
    }

    // --- The same typed invoker on the current runtime ---

    private void currentRuntimeServesTheTypedInvoker(Path workspace) throws Exception {
        // Given the typed invoker loaded against the current interface
        Path typedOut = compile(workspace.resolve("typed-for-current"), typedSources());
        DirectoryClassLoader loader = own(new DirectoryClassLoader(List.of(typedOut), testClassLoader()));
        AtomicInteger effects = new AtomicInteger();
        Class<?> typedClass = load(loader, TYPED_INVOKER);
        assertThat(typedClass.getInterfaces()).containsExactly(McpToolInvoker.class);
        Deployment app = startDeployment(Set.of((McpToolInvoker) instantiate(typedClass, effects)));

        // When callers list and call through the current runtime
        // Then the tool is visible and callable for the permitted caller only
        assertThat(app.list(ALICE).toolNames())
                .as("listed for the caller the policy permits")
                .contains(TYPED_TOOL);
        assertThat(app.list(ALICE_WITHOUT_OPS).toolNames())
                .as("hidden from a caller the policy denies")
                .doesNotContain(TYPED_TOOL);
        assertThat(app.call(ALICE, TYPED_TOOL).outcome())
                .as("call by the caller the policy permits")
                .isEqualTo(Outcome.SUCCEEDED);
        assertThat(effects).as("effects after the permitted call").hasValue(1);
        assertThat(app.call(ALICE_WITHOUT_OPS, TYPED_TOOL).outcome())
                .as("call by a caller the policy denies")
                .isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.call(null, TYPED_TOOL).outcome())
                .as("call by an anonymous caller")
                .isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(effects).as("effects after the refused calls").hasValue(1);
    }

    // --- The hook signature ---

    private void hookIsADefaultMethodWithTheFrozenSignature() {
        // Given the current invoker interface
        // When its declared methods are inspected reflectively
        List<Method> hooks = Stream.of(McpToolInvoker.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("accessPolicy"))
                .toList();

        // Then it declares one public default accessPolicy() with the exact generic return type
        assertThat(hooks).as("declared accessPolicy methods").hasSize(1);
        Method hook = hooks.get(0);
        assertThat(hook.isDefault()).as("accessPolicy is a default method").isTrue();
        assertThat(hook.getParameterCount()).as("accessPolicy parameters").isZero();
        assertThat(hook.getGenericReturnType().getTypeName())
                .as("accessPolicy return type")
                .isEqualTo("java.util.Optional<java.lang.Class<? extends dev.vertique.security.authz.AccessPolicy>>");
    }

    // --- Compiled fixtures ---

    private static Map<String, String> typedSources() {
        return Map.of(
                TYPED_POLICY,
                """
                        package compat.typed;

                        @jakarta.annotation.security.RolesAllowed("ops")
                        public interface OpsOnlyPolicy extends dev.vertique.security.authz.AccessPolicy {}
                        """,
                TYPED_INVOKER,
                invokerSource(
                        "compat.typed",
                        "TypedOpsInvoker",
                        TYPED_TOOL,
                        "new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null)",
                        """
                                public Optional<Class<? extends dev.vertique.security.authz.AccessPolicy>> accessPolicy() {
                                    return Optional.of(OpsOnlyPolicy.class);
                                }
                                """));
    }

    private static String rolesAccess(String role) {
        return "new McpToolAccess(McpAccessMode.RESTRICTED, List.of(\"" + role + "\"), null)";
    }

    private static String permitAllAccess() {
        return "new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null)";
    }

    /**
     * Source of a hand-written invoker that counts each invocation in the supplied counter. The hook
     * method is declared without an override annotation so the source compiles against an interface
     * that has no such hook.
     */
    private static String invokerSource(
            String packageName, String className, String toolName, String accessExpression, String hookMethod) {
        return """
                package %s;

                import dev.vertique.mcp.tool.McpAccessMode;
                import dev.vertique.mcp.tool.McpCancellationSignal;
                import dev.vertique.mcp.tool.McpPreparedToolCall;
                import dev.vertique.mcp.tool.McpToolAccess;
                import dev.vertique.mcp.tool.McpToolAnnotations;
                import dev.vertique.mcp.tool.McpToolDescriptor;
                import dev.vertique.mcp.tool.McpToolInvoker;
                import dev.vertique.mcp.tool.McpToolResult;
                import io.vertx.core.Future;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.concurrent.atomic.AtomicInteger;

                public final class %s implements McpToolInvoker {
                    private final AtomicInteger effects;

                    public %s(AtomicInteger effects) {
                        this.effects = effects;
                    }

                    @Override
                    public McpToolDescriptor descriptor() {
                        return new McpToolDescriptor(
                                "%s",
                                null,
                                "Compatibility fixture tool.",
                                new McpToolAnnotations(true, false, true, false),
                                "{\\"type\\":\\"object\\",\\"additionalProperties\\":false}",
                                null,
                                %s);
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
                                effects.incrementAndGet();
                                return Future.succeededFuture(McpToolResult.text("ok"));
                            }
                        };
                    }

                    %s
                }
                """.formatted(
                packageName, className, className, toolName, accessExpression, hookMethod == null ? "" : hookMethod);
    }

    /**
     * Source of a consumer that knows only the baseline interface: it keeps its invokers by name and
     * decides visibility and invocation from each descriptor alone, through a decider handed in by the
     * current runtime. It stays free of package-private runtime types so a different class loader can
     * define it.
     */
    private static String descriptorOnlyConsumerSource() {
        return """
                package compat.consumer;

                import dev.vertique.mcp.tool.McpCancellationSignal;
                import dev.vertique.mcp.tool.McpToolDescriptor;
                import dev.vertique.mcp.tool.McpToolInvoker;
                import dev.vertique.security.SecurityContext;
                import dev.vertique.security.authz.AuthorizationDecision;
                import io.vertx.core.Future;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.function.BiFunction;

                public final class DescriptorOnlyConsumer {
                    private final Map<String, McpToolInvoker> invokersByName = new LinkedHashMap<>();
                    private final BiFunction<McpToolDescriptor, SecurityContext, Future<AuthorizationDecision>> decide;

                    public DescriptorOnlyConsumer(
                            BiFunction<McpToolDescriptor, SecurityContext, Future<AuthorizationDecision>> decide) {
                        this.decide = decide;
                    }

                    public void register(Object invoker) {
                        McpToolInvoker known = (McpToolInvoker) invoker;
                        invokersByName.put(known.descriptor().name(), known);
                    }

                    public Future<List<String>> list(SecurityContext caller) {
                        List<String> names = new ArrayList<>();
                        List<Future<Boolean>> visibility = new ArrayList<>();
                        for (McpToolInvoker invoker : invokersByName.values()) {
                            names.add(invoker.descriptor().name());
                            visibility.add(decide.apply(invoker.descriptor(), caller).map(AuthorizationDecision::permitted));
                        }
                        return Future.all(visibility).map(all -> {
                            List<String> shown = new ArrayList<>();
                            for (int i = 0; i < names.size(); i++) {
                                if (visibility.get(i).result()) {
                                    shown.add(names.get(i));
                                }
                            }
                            return shown;
                        });
                    }

                    public Future<String> call(SecurityContext caller, String name) {
                        McpToolInvoker invoker = invokersByName.get(name);
                        if (invoker == null) {
                            return Future.succeededFuture("refused");
                        }
                        return decide.apply(invoker.descriptor(), caller).compose(decision -> {
                            if (!decision.permitted()) {
                                return Future.succeededFuture("refused");
                            }
                            McpCancellationSignal signal = new McpCancellationSignal() {
                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public Future<Void> cancelled() {
                                    return Future.<Void>future(promise -> {});
                                }
                            };
                            return invoker.prepare(Map.of(), signal).invoke().map(result -> "invoked");
                        });
                    }
                }
                """;
    }

    // --- The old consumer, driven from this class loader ---

    /** The reflective handle on the descriptor-only consumer defined by the isolated class loader. */
    private static final class OldConsumer {
        private final Object consumer;
        private final Method register;
        private final Method list;
        private final Method call;

        private OldConsumer(Object consumer) throws NoSuchMethodException {
            this.consumer = consumer;
            Class<?> type = consumer.getClass();
            this.register = type.getMethod("register", Object.class);
            this.list = type.getMethod("list", SecurityContext.class);
            this.call = type.getMethod("call", SecurityContext.class, String.class);
        }

        void register(Object invoker) throws Exception {
            invoke(register, invoker);
        }

        @SuppressWarnings("unchecked")
        List<String> list(SecurityContext caller) throws Exception {
            return McpTypedPolicyServiceITFixture.await((Future<List<String>>) invoke(list, caller));
        }

        @SuppressWarnings("unchecked")
        String call(SecurityContext caller, String name) throws Exception {
            return McpTypedPolicyServiceITFixture.await((Future<String>) invoke(call, caller, name));
        }

        private Object invoke(Method method, Object... arguments) throws Exception {
            try {
                return method.invoke(consumer, arguments);
            } catch (InvocationTargetException failed) {
                throw failed.getCause() instanceof Exception cause ? cause : failed;
            }
        }
    }

    private OldConsumer oldConsumer(DirectoryClassLoader isolated) throws Exception {
        McpPolicyEnforcer enforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                new DefaultContextHolder(),
                new HolderBackedSecurityRuntime((securityContext, secure) -> null),
                Optional.empty()));
        BiFunction<McpToolDescriptor, SecurityContext, Future<AuthorizationDecision>> decider = enforcer::decide;
        Class<?> type = load(isolated, OLD_CONSUMER);
        Object instance = type.getConstructor(BiFunction.class).newInstance(decider);
        return new OldConsumer(instance);
    }

    private static SecurityContext caller(String subject, String role) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, subject, Map.of()));
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(
                Set.of(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "compatibility-test", Map.of())), Map.of());
        return SecurityContexts.assemble(identity, authentication, claims, Optional.empty());
    }

    // --- Current runtime ---

    /** Starts the real loopback deployment of the typed policy fixture around exactly {@code invokers}. */
    private Deployment startDeployment(Set<McpToolInvoker> invokers) throws Exception {
        ToolSource tools = services -> invokers;
        return own(Deployment.start(vertx, tools, Map.of()));
    }

    // --- Compilation and loading ---

    private static ClassLoader testClassLoader() {
        return McpPolicyBinaryCompatibilityTest.class.getClassLoader();
    }

    /**
     * Compiles the given sources, keyed by fully qualified class name, into {@code outputDirectory}
     * against the test class path. Annotation processing is off so the processors on the class path
     * never run on the fixtures.
     */
    private static Path compile(Path outputDirectory, Map<String, String> sourcesByClassName) throws IOException {
        Files.createDirectories(outputDirectory);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>();
        sourcesByClassName.forEach((className, source) -> units.add(new InMemorySource(className, source)));
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory.toFile()));
            List<String> options =
                    List.of("-proc:none", "--release", "21", "-classpath", System.getProperty("java.class.path"));
            Boolean compiled = compiler.getTask(null, files, diagnostics, options, null, units)
                    .call();
            assertThat(compiled)
                    .as("compilation of " + sourcesByClassName.keySet() + ": " + diagnostics.getDiagnostics())
                    .isTrue();
        }
        return outputDirectory;
    }

    private static Class<?> load(ClassLoader loader, String className) {
        try {
            return loader.loadClass(className);
        } catch (ClassNotFoundException | LinkageError failed) {
            throw new AssertionError("class " + className + " could not be loaded", failed);
        }
    }

    private static Object instantiate(Class<?> type, AtomicInteger effects) throws Exception {
        return type.getConstructor(AtomicInteger.class).newInstance(effects);
    }

    private static byte[] readResource(String resource) throws IOException {
        try (var input = McpPolicyBinaryCompatibilityTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("test resource " + resource + " is missing");
            }
            return input.readAllBytes();
        }
    }

    private Path temporaryWorkspace() throws IOException {
        Path directory = Files.createTempDirectory("mcp-policy-compat");
        own(() -> deleteRecursively(directory));
        return directory;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.push(resource);
        return resource;
    }

    /** A Java source held in memory. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String source;

        private InMemorySource(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /**
     * A class loader over compiled output directories that defines the classes found there itself and
     * delegates every other class to its parent. A class absent from the directories, such as a
     * deleted baseline interface, therefore resolves to the parent's version.
     */
    private static final class DirectoryClassLoader extends URLClassLoader {
        private final List<Path> roots;

        private DirectoryClassLoader(List<Path> roots, ClassLoader parent) {
            super(urlsOf(roots), parent);
            this.roots = List.copyOf(roots);
        }

        private static URL[] urlsOf(List<Path> roots) {
            try {
                List<URL> urls = new ArrayList<>();
                for (Path root : roots) {
                    urls.add(root.toUri().toURL());
                }
                return urls.toArray(URL[]::new);
            } catch (MalformedURLException failed) {
                throw new IllegalArgumentException(failed);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = definesLocally(name) ? findClass(name) : super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private boolean definesLocally(String name) {
            String classFile = name.replace('.', '/') + ".class";
            return roots.stream().anyMatch(root -> Files.isRegularFile(root.resolve(classFile)));
        }
    }
}
