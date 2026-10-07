// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.interceptor.OperationContext;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.unitb.ManagementApi;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves where a JAX-RS composition carries the application name: an application mount's
 * {@code MountMeta}, the descriptor its operation-handler contributors receive, and the {@code
 * OperationContext.operation()} every operation interceptor receives, which is that same descriptor
 * instance. Legacy and hand-built mounts carry {@code null} on each of these carriers, a synthetic
 * operation's descriptor returns the name of the application whose document it serves, and the
 * interceptor chain puts the registration-time descriptor back whenever an interceptor returns a
 * context carrying another one or none.
 *
 * <p>The harness serves {@link StatusResource} from real mounts on a root router behind an HTTP
 * server on {@code 127.0.0.1}, port 0. Each factory carries a {@link RecordingContributor} and two
 * operation interceptors with distinct priorities: {@link FirstInterceptor} runs first and {@link
 * SecondInterceptor} second.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ApplicationNameCarrierTest {

    private static final String APPLICATION = "mgmt";
    private static final String APPLICATION_MOUNT_PATH = "/api/mgmt/*";
    private static final String SEEN = "seen";
    private static final String SENTINEL_KEY = "application-name-carrier-test.sentinel";
    private static final String SENTINEL_VALUE = "planted by the first interceptor";
    private static final String SCHEME = "bearerAuth";

    private static final String BEFORE = "beforeOperation";
    private static final String AFTER = "afterOperation";
    private static final String RECOVER = "recoverOperation";

    /** The first interceptor's default: add the attribute {@code seen} to the context it received. */
    private static final UnaryOperator<OperationContext> WITH_SEEN = ctx -> ctx.withAttribute(SEEN, true);

    private Vertx vertx;
    private @Nullable HttpServer server;
    /** Awaitable close handle; {@link WebClient#close()} discards the underlying future. */
    private @Nullable HttpClient transport;

    private @Nullable WebClient client;
    private int port;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        Future<Void> clientClose = transport != null ? transport.close() : Future.succeededFuture();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        await(Future.join(clientClose, serverClose).mapEmpty());
        await(vertx.close());
    }

    // --- Application mount ---

    @Test
    @DisplayName("An application mount carries its name on its meta, its descriptor, and the one instance both"
            + " interceptors receive, and never in routing-context data")
    void applicationMountCarriesItsNameEverywhere() {
        // Given
        Recorders recorders = new Recorders(WITH_SEEN, UnaryOperator.identity());
        JaxRsRouterMount mount = applicationMount(recorders.factory);
        Router root = Router.router(vertx);
        Map<String, RestOperationDescriptor> registered = mountOn(root, mount, recorders.contributor);
        listen(root);

        // When
        int statusCode = get("/api/mgmt/status");

        // Then
        RestOperationDescriptor descriptor = required(registered, "status");
        OperationContext firstReceived = recorders.first.received("status");
        OperationContext secondReceived = recorders.second.received(BEFORE, "status");
        List<Object> dataValues = recorders.second.dataValues("status");
        assertAll(
                () -> assertEquals(200, statusCode, "GET /api/mgmt/status"),
                () -> assertEquals(APPLICATION, mount.meta().applicationName(), "mount meta"),
                () -> assertEquals(APPLICATION, descriptor.applicationName(), "contributor's descriptor"),
                () -> assertSame(
                        descriptor,
                        firstReceived.operation(),
                        "the first interceptor must receive the descriptor the contributor received"),
                () -> assertSame(
                        descriptor,
                        secondReceived.operation(),
                        "the second interceptor must receive the descriptor the contributor received"),
                () -> assertEquals(
                        Boolean.TRUE,
                        secondReceived.attributes().get(SEEN),
                        "the second interceptor must receive the first interceptor's withAttribute copy"),
                () -> assertTrue(
                        dataValues.contains(SENTINEL_VALUE),
                        "control: the routing-context data the second interceptor read must hold the value the"
                                + " first interceptor planted"),
                () -> assertFalse(
                        dataValues.contains(APPLICATION),
                        "no routing-context data value may be the application name: " + dataValues),
                () -> assertTrue(
                        dataValues.stream().noneMatch(value -> value == descriptor),
                        "no routing-context data value may be the operation descriptor"));
    }

    // --- Legacy and hand-built mounts ---

    @Test
    @DisplayName("Legacy and hand-built mounts carry null on every carrier, while the application mount beside"
            + " them carries its name")
    void legacyAndManualMountsCarryNull() {
        // Given (a): a zero-registration composition's legacy default mount
        Recorders legacyRecorders = new Recorders(WITH_SEEN, UnaryOperator.identity());
        JaxRsConfig legacyConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .basePath("/legacy/*")
                .build();
        Set<RouterMount> legacyComposition = RestModule.jaxRsRouterMount(
                legacyRecorders.factory,
                new RestApplications(List.of()),
                Set.of(),
                () -> Set.<Object>of(new StatusResource()),
                () -> Set.of(),
                legacyConfig);
        assertEquals(1, legacyComposition.size(), "a zero-registration composition builds one legacy mount");
        JaxRsRouterMount legacy =
                (JaxRsRouterMount) legacyComposition.iterator().next();

        // Given (b): a hand-built mount beside the application mount in one composition
        Recorders composition = new Recorders(WITH_SEEN, UnaryOperator.identity());
        JaxRsRouterMount manual =
                composition.factory.create("/api/manual/*", "openapi.json", Set.<Object>of(new StatusResource()));
        JaxRsRouterMount mgmt = applicationMount(composition.factory);

        List<Row> rows = List.of(
                new Row("zero-registration legacy mount", legacy, legacyRecorders, null),
                new Row("hand-built Factory.create mount", manual, composition, null),
                new Row("application mount beside it (control)", mgmt, composition, APPLICATION));

        Router root = Router.router(vertx);
        Map<Row, RestOperationDescriptor> registered = new HashMap<>();
        for (Row row : rows) {
            registered.put(row, required(mountOn(root, row.mount(), row.recorders().contributor), "status"));
        }
        listen(root);

        // When: GET .../status is sent to each mount in turn
        List<Observed> observed = new ArrayList<>();
        for (Row row : rows) {
            int statusCode = get(prefixOf(row.mount()) + "/status");
            observed.add(new Observed(
                    row,
                    statusCode,
                    registered.get(row),
                    row.recorders().first.received("status"),
                    row.recorders().second.received(BEFORE, "status")));
        }

        // Then
        assertAll(observed.stream().map(ApplicationNameCarrierTest::carriersOf));
    }

    // --- Synthetic operations ---

    @Test
    @DisplayName("A synthetic operation's descriptor returns the name of the application whose document it serves")
    void syntheticDescriptorReturnsTheDocumentedApplicationName() {
        // Given
        RecordingContributor contributor = new RecordingContributor();
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(new AuthenticatingSchemeHandler(SCHEME)))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .operationHandlerContributors(Set.of(contributor))
                .build();
        DefaultSyntheticOperationInstaller installer = new DefaultSyntheticOperationInstaller(factory);
        Router router = Router.router(vertx);

        // When
        installer.install(
                router,
                "/management/openapi.json",
                List.of(HttpMethod.GET),
                SyntheticOperation.withRoles(
                        "@ApiDocs on application 'management'",
                        "apidocs:management:json",
                        SCHEME,
                        "management",
                        List.of("admin")),
                ctx -> ctx.response().end());
        installer.install(
                router,
                "/reports/openapi.json",
                List.of(HttpMethod.GET),
                SyntheticOperation.authenticated(
                        "@ApiDocs on application 'reports'", "apidocs:reports:json", SCHEME, "reports"),
                ctx -> ctx.response().end());

        // Then
        Map<String, RestOperationDescriptor> stored = contributor.drain();
        RestOperationDescriptor management = required(stored, "apidocs:management:json");
        RestOperationDescriptor reports = required(stored, "apidocs:reports:json");
        assertAll(
                () -> assertEquals("management", management.applicationName(), "apidocs:management:json"),
                () -> assertEquals("reports", reports.applicationName(), "apidocs:reports:json"));
    }

    // --- Chain restoration ---

    private static Stream<Arguments> rebuildRows() {
        UnaryOperator<OperationContext> fiveArgumentRebuild = ctx -> new OperationContext(
                ctx.operationId(),
                ctx.routingContext(),
                ctx.methodAnnotations(),
                ctx.classAnnotations(),
                Map.of(SEEN, true));
        UnaryOperator<OperationContext> otherDescriptorRebuild = ctx -> new OperationContext(
                ctx.operationId(),
                ctx.routingContext(),
                ctx.methodAnnotations(),
                ctx.classAnnotations(),
                Map.of(SEEN, true),
                new OtherDescriptor(ctx.operationId()));
        UnaryOperator<OperationContext> equalToEverythingDescriptorRebuild = ctx -> new OperationContext(
                ctx.operationId(),
                ctx.routingContext(),
                ctx.methodAnnotations(),
                ctx.classAnnotations(),
                Map.of(SEEN, true),
                new EqualToEverythingDescriptor(ctx.operationId()));
        UnaryOperator<OperationContext> lastFiveArgumentRebuild = ctx -> new OperationContext(
                ctx.operationId(),
                ctx.routingContext(),
                ctx.methodAnnotations(),
                ctx.classAnnotations(),
                ctx.attributes());
        return Stream.of(
                Arguments.of(
                        "A: the first interceptor rebuilds with the previous-arity constructor",
                        fiveArgumentRebuild,
                        UnaryOperator.<OperationContext>identity()),
                Arguments.of(
                        "B: the first interceptor rebuilds carrying another descriptor",
                        otherDescriptorRebuild,
                        UnaryOperator.<OperationContext>identity()),
                Arguments.of(
                        "C: the last interceptor rebuilds with the previous-arity constructor",
                        WITH_SEEN,
                        lastFiveArgumentRebuild),
                Arguments.of(
                        "D: the first interceptor rebuilds carrying a descriptor that claims equality with every object",
                        equalToEverythingDescriptorRebuild,
                        UnaryOperator.<OperationContext>identity()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rebuildRows")
    @DisplayName("The interceptor chain puts the registration-time operation back after a rebuilt context")
    void interceptorChainRestoresTheRegistrationTimeOperation(
            String label, UnaryOperator<OperationContext> firstReturns, UnaryOperator<OperationContext> secondReturns) {
        // Given
        Recorders recorders = new Recorders(firstReturns, secondReturns);
        JaxRsRouterMount mount = applicationMount(recorders.factory);
        Router root = Router.router(vertx);
        Map<String, RestOperationDescriptor> registered = mountOn(root, mount, recorders.contributor);
        listen(root);

        // When
        int statusCode = get("/api/mgmt/status");
        int boomCode = get("/api/mgmt/boom");

        // Then
        RestOperationDescriptor status = required(registered, "status");
        RestOperationDescriptor boom = required(registered, "boom");
        OperationContext beforeStatus = recorders.second.received(BEFORE, "status");
        OperationContext afterStatus = recorders.second.received(AFTER, "status");
        OperationContext recoverBoom = recorders.second.received(RECOVER, "boom");
        assertAll(
                () -> assertEquals(200, statusCode, "GET /api/mgmt/status"),
                () -> assertEquals(200, boomCode, "GET /api/mgmt/boom (recovered by the second interceptor)"),
                () -> assertEquals(APPLICATION, status.applicationName(), "contributor's status descriptor"),
                () -> assertEquals(APPLICATION, boom.applicationName(), "contributor's boom descriptor"),
                () -> assertRestored(status, beforeStatus, BEFORE + " for status"),
                () -> assertRestored(status, afterStatus, AFTER + " for status"),
                () -> assertRestored(boom, recoverBoom, RECOVER + " for boom"));
    }

    // --- Assertion helpers ---

    private static void assertRestored(RestOperationDescriptor registered, OperationContext received, String hook) {
        assertSame(
                registered,
                received.operation(),
                "the second interceptor's " + hook + " must receive the registration-time descriptor");
        assertEquals(
                APPLICATION,
                received.operation().applicationName(),
                "the second interceptor's " + hook + " descriptor name");
        assertEquals(
                Boolean.TRUE,
                received.attributes().get(SEEN),
                "the second interceptor's " + hook + " context must still carry the attribute " + SEEN);
    }

    private static Executable carriersOf(Observed observed) {
        String label = observed.row().label();
        String expected = observed.row().expectedName();
        return () -> assertAll(
                label,
                () -> assertEquals(200, observed.statusCode(), label + ": response status"),
                () -> assertEquals(expected, observed.row().mount().meta().applicationName(), label + ": mount meta"),
                () -> assertEquals(
                        expected, observed.registered().applicationName(), label + ": contributor's descriptor"),
                () -> {
                    assertNotNull(
                            observed.firstReceived().operation(), label + ": the first interceptor's ctx.operation()");
                    assertEquals(
                            expected,
                            observed.firstReceived().operation().applicationName(),
                            label + ": the first interceptor's ctx.operation().applicationName()");
                },
                () -> {
                    assertNotNull(
                            observed.secondReceived().operation(),
                            label + ": the second interceptor's ctx.operation()");
                    assertEquals(
                            expected,
                            observed.secondReceived().operation().applicationName(),
                            label + ": the second interceptor's ctx.operation().applicationName()");
                });
    }

    private static <V> V required(Map<String, V> recorded, String operationId) {
        V value = recorded.get(operationId);
        assertNotNull(value, "nothing was recorded for operation '" + operationId + "': " + recorded.keySet());
        return value;
    }

    // --- Harness ---

    /**
     * Builds the harness's application mount {@code mgmt} serving {@link StatusResource}, and marks it
     * validated as the composition validator would.
     */
    private static JaxRsRouterMount applicationMount(JaxRsRouterMount.Factory factory) {
        JaxRsRouterMount mount = factory.createApplicationMount(
                APPLICATION_MOUNT_PATH,
                "openapi.json",
                Set.<Object>of(new StatusResource()),
                APPLICATION,
                ManagementApi.class);
        mount.markValidated();
        return mount;
    }

    /**
     * Builds the mount's router, mounts it on {@code root} at the mount's path, and returns the
     * descriptors the contributor received while that router was built.
     */
    private Map<String, RestOperationDescriptor> mountOn(
            Router root, JaxRsRouterMount mount, RecordingContributor contributor) {
        Router router = await(mount.createRouter(vertx));
        root.route(mount.mountPath()).subRouter(router);
        return contributor.drain();
    }

    private static String prefixOf(JaxRsRouterMount mount) {
        String path = mount.mountPath();
        return path.endsWith("/*") ? path.substring(0, path.length() - 2) : path;
    }

    private void listen(Router root) {
        server = await(vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1"));
        port = server.actualPort();
        transport = vertx.createHttpClient();
        client = WebClient.wrap(transport);
    }

    private int get(String path) {
        return await(client.get(port, "127.0.0.1", path).send()).statusCode();
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("asynchronous step failed: " + e.getMessage(), e);
        }
    }

    /** One factory with its three recorders. */
    private static final class Recorders {
        final RecordingContributor contributor = new RecordingContributor();
        final FirstInterceptor first;
        final SecondInterceptor second;
        final JaxRsRouterMount.Factory factory;

        Recorders(UnaryOperator<OperationContext> firstReturns, UnaryOperator<OperationContext> secondReturns) {
            this.first = new FirstInterceptor(firstReturns);
            this.second = new SecondInterceptor(secondReturns);
            this.factory = TestFactories.builder()
                    .operationHandlerContributors(Set.of(contributor))
                    .operationInterceptors(Set.of(first, second))
                    .build();
        }
    }

    /** One mount under observation and the name every one of its carriers must report. */
    private record Row(
            String label,
            JaxRsRouterMount mount,
            Recorders recorders,
            @Nullable String expectedName) {}

    /** What one mount's carriers reported for {@code GET .../status}. */
    private record Observed(
            Row row,
            int statusCode,
            RestOperationDescriptor registered,
            OperationContext firstReceived,
            OperationContext secondReceived) {}

    /**
     * Stores the descriptor every {@link OperationRegistrationContext} carries, by operation id, until
     * drained.
     */
    private static final class RecordingContributor implements OperationHandlerContributor {
        private final Map<String, RestOperationDescriptor> descriptors = new ConcurrentHashMap<>();

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            descriptors.put(context.operationId(), context.operation());
        }

        Map<String, RestOperationDescriptor> drain() {
            Map<String, RestOperationDescriptor> drained = Map.copyOf(descriptors);
            descriptors.clear();
            return drained;
        }
    }

    /**
     * Runs first: records the context its {@code beforeOperation} receives, plants a sentinel value in
     * the routing-context data, and returns the context its row's function builds.
     */
    private static final class FirstInterceptor implements OperationInterceptor {
        private final UnaryOperator<OperationContext> returns;
        private final Map<String, OperationContext> received = new ConcurrentHashMap<>();

        FirstInterceptor(UnaryOperator<OperationContext> returns) {
            this.returns = returns;
        }

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public Future<OperationContext> beforeOperation(OperationContext ctx) {
            received.put(ctx.operationId(), ctx);
            ctx.routingContext().put(SENTINEL_KEY, SENTINEL_VALUE);
            return Future.succeededFuture(returns.apply(ctx));
        }

        OperationContext received(String operationId) {
            OperationContext ctx = received.remove(operationId);
            assertNotNull(ctx, "the first interceptor's beforeOperation was not called for " + operationId);
            return ctx;
        }
    }

    /**
     * Runs second: records the context each of {@code beforeOperation}, {@code afterOperation}, and
     * {@code recoverOperation} receives, keyed by hook and operation id, and the routing-context data
     * values its {@code beforeOperation} reads; recovers {@code boom} with a fixed result.
     */
    private static final class SecondInterceptor implements OperationInterceptor {
        private final UnaryOperator<OperationContext> returns;
        private final Map<String, OperationContext> received = new ConcurrentHashMap<>();
        private final Map<String, List<Object>> dataValues = new ConcurrentHashMap<>();

        SecondInterceptor(UnaryOperator<OperationContext> returns) {
            this.returns = returns;
        }

        @Override
        public int priority() {
            return 200;
        }

        @Override
        public Future<OperationContext> beforeOperation(OperationContext ctx) {
            received.put(BEFORE + ":" + ctx.operationId(), ctx);
            dataValues.put(
                    ctx.operationId(),
                    new ArrayList<>(ctx.routingContext().data().values()));
            return Future.succeededFuture(returns.apply(ctx));
        }

        @Override
        public Future<Object> afterOperation(OperationContext ctx, Object result) {
            received.put(AFTER + ":" + ctx.operationId(), ctx);
            return Future.succeededFuture(result);
        }

        @Override
        public Future<Object> recoverOperation(OperationContext ctx, Throwable cause) {
            received.put(RECOVER + ":" + ctx.operationId(), ctx);
            return "boom".equals(ctx.operationId()) ? Future.succeededFuture("recovered") : Future.failedFuture(cause);
        }

        OperationContext received(String hook, String operationId) {
            OperationContext ctx = received.remove(hook + ":" + operationId);
            assertNotNull(ctx, "the second interceptor's " + hook + " was not called for " + operationId);
            return ctx;
        }

        List<Object> dataValues(String operationId) {
            List<Object> values = dataValues.remove(operationId);
            assertNotNull(values, "the second interceptor read no routing-context data for " + operationId);
            return values;
        }
    }

    /** Another operation's descriptor, named {@code other}, that an interceptor may put on a context. */
    private static class OtherDescriptor implements RestOperationDescriptor {
        private final String operationId;

        OtherDescriptor(String operationId) {
            this.operationId = operationId;
        }

        @Override
        public String operationId() {
            return operationId;
        }

        @Override
        public String httpMethod() {
            return "GET";
        }

        @Override
        public String routeTemplate() {
            return "/" + operationId;
        }

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }

        @Override
        public String applicationName() {
            return "other";
        }
    }

    /**
     * A descriptor named {@code other} whose {@code equals} accepts every object, so only reference identity tells it
     * from the registered one.
     */
    private static final class EqualToEverythingDescriptor extends OtherDescriptor {
        EqualToEverythingDescriptor(String operationId) {
            super(operationId);
        }

        @Override
        public boolean equals(Object other) {
            return true;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    /** A security scheme handler that registers a pass-through authentication handler. */
    private static final class AuthenticatingSchemeHandler implements SecuritySchemeHandler {
        private final String schemeName;

        AuthenticatingSchemeHandler(String schemeName) {
            this.schemeName = schemeName;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(RoutingContext::next);
        }
    }

    /**
     * The harness resource: {@code GET /status} (operation id {@code status}) answers, and {@code GET
     * /boom} (operation id {@code boom}) throws.
     */
    @Path("")
    static final class StatusResource {

        @GET
        @Path("/status")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "status")
        public String status() {
            return "ok";
        }

        @GET
        @Path("/boom")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "boom")
        public String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
