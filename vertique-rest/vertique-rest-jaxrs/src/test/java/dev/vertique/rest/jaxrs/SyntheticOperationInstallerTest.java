// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.SyntheticOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Unit proofs for the package-private {@link SyntheticOperationInstaller}: every condition a
 * resource route would fail on rejects a synthetic operation before any route exists, a duplicate
 * operation id is rejected per router only, a failure while the route is built removes the route and
 * releases its operation id, scheme handlers are configured exactly once per router, and the
 * registered contributors receive the same effective policy, requirement sets, and
 * descriptor annotations an equally annotated resource method's contributors receive.
 */
class SyntheticOperationInstallerTest {

    private static final String ORIGIN = "@ApiDocs on application 'management'";
    private static final String SCHEME = "bearerAuth";
    private static final String APPLICATION = "management";
    private static final Handler<RoutingContext> NOOP_TERMINAL =
            ctx -> ctx.response().end();

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    // --- Misconfiguration fails before any route exists ---

    private static Stream<Arguments> misconfigurationCases() {
        return Stream.of(
                Arguments.of(
                        "an unknown scheme",
                        (Supplier<JaxRsRouterMount.Factory>) () -> TestFactories.builder()
                                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                                .build(),
                        (Supplier<SyntheticOperation>) () -> SyntheticOperation.withRoles(
                                ORIGIN, "apidocs:management:json", "nope", APPLICATION, List.of("admin")),
                        List.of(ORIGIN, "nope")),
                Arguments.of(
                        "a scheme whose handler collects no authentication handler",
                        (Supplier<JaxRsRouterMount.Factory>) () -> TestFactories.builder()
                                .securitySchemeHandlers(Set.of(new StubSchemeHandler("silent", false)))
                                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                                .build(),
                        (Supplier<SyntheticOperation>) () -> SyntheticOperation.withRoles(
                                ORIGIN, "apidocs:management:json", "silent", APPLICATION, List.of("admin")),
                        List.of(ORIGIN, "silent")),
                Arguments.of(
                        "withRoles with no AuthEnforcementCapability",
                        (Supplier<JaxRsRouterMount.Factory>) () -> TestFactories.builder()
                                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                                .authEnforcementCapability(Optional.empty())
                                .build(),
                        (Supplier<SyntheticOperation>) () -> SyntheticOperation.withRoles(
                                ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin")),
                        List.of(ORIGIN)),
                Arguments.of(
                        "authenticated with no AuthEnforcementCapability",
                        (Supplier<JaxRsRouterMount.Factory>) () -> TestFactories.builder()
                                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                                .authEnforcementCapability(Optional.empty())
                                .build(),
                        (Supplier<SyntheticOperation>) () -> SyntheticOperation.authenticated(
                                ORIGIN, "apidocs:authenticated:json", SCHEME, "authenticated"),
                        List.of(ORIGIN)),
                Arguments.of(
                        "a SecurityPolicyValidator reporting one violation",
                        (Supplier<JaxRsRouterMount.Factory>) () -> TestFactories.builder()
                                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                                .securityPolicyValidator((op, policy) -> List.of(new SecurityPolicyViolation(
                                        op.operationId(),
                                        SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS,
                                        "synthetic violation")))
                                .build(),
                        (Supplier<SyntheticOperation>) () -> SyntheticOperation.withRoles(
                                ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin")),
                        List.of(ORIGIN)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misconfigurationCases")
    @DisplayName("A misconfigured synthetic operation fails before any route exists, naming its origin")
    void misconfigurationFailsBeforeAnyRoute(
            String label,
            Supplier<JaxRsRouterMount.Factory> factorySupplier,
            Supplier<SyntheticOperation> operationSupplier,
            List<String> expectedFragments) {
        assertFailsBeforeAnyRoute(factorySupplier.get(), operationSupplier.get(), expectedFragments);
    }

    private void assertFailsBeforeAnyRoute(
            JaxRsRouterMount.Factory factory, SyntheticOperation operation, List<String> expectedFragments) {
        Router spyRouter = Mockito.spy(Router.router(vertx));
        SyntheticOperationInstaller installer = new SyntheticOperationInstaller(factory);

        RestConfigurationException thrown = assertThrows(
                RestConfigurationException.class,
                () -> installer.install(
                        spyRouter,
                        "/management/openapi.json",
                        List.of(HttpMethod.GET, HttpMethod.HEAD),
                        operation,
                        NOOP_TERMINAL));

        assertTrue(
                thrown.getMessage().startsWith(ORIGIN),
                "message should start with the origin but was: " + thrown.getMessage());
        for (String fragment : expectedFragments) {
            assertTrue(
                    thrown.getMessage().contains(fragment),
                    "message should contain '" + fragment + "' but was: " + thrown.getMessage());
        }

        verify(spyRouter, never()).route(anyString());
        verify(spyRouter, never()).route(any(HttpMethod.class), anyString());
        verify(spyRouter, never()).routeWithRegex(any(HttpMethod.class), anyString());
        verify(spyRouter, never()).routeWithRegex(anyString());
    }

    // --- Duplicate operation id ---

    @Test
    @DisplayName("A duplicate operation id fails on the same router only")
    void duplicateOperationIdFailsPerRouter() {
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .build();
        SyntheticOperationInstaller installer = new SyntheticOperationInstaller(factory);

        Router routerA = Mockito.spy(Router.router(vertx));
        Router routerB = Router.router(vertx);

        SyntheticOperation operation =
                SyntheticOperation.withRoles(ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin"));
        installer.install(
                routerA,
                "/management/openapi.json",
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                operation,
                NOOP_TERMINAL);

        SyntheticOperation duplicate =
                SyntheticOperation.withRoles(ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin"));
        assertThrows(
                RestConfigurationException.class,
                () -> installer.install(
                        routerA,
                        "/management/openapi-2.json",
                        List.of(HttpMethod.GET, HttpMethod.HEAD),
                        duplicate,
                        NOOP_TERMINAL),
                "the same operation id installed twice on the same router must fail");

        verify(routerA, times(1)).route(anyString());

        assertDoesNotThrow(
                () -> installer.install(
                        routerB,
                        "/management/openapi.json",
                        List.of(HttpMethod.GET, HttpMethod.HEAD),
                        operation,
                        NOOP_TERMINAL),
                "the same operation id must install cleanly on a different router");
    }

    // --- A failure while building the route ---

    @Test
    @DisplayName("A failure while building the route removes the route and releases the operation id")
    void aFailureWhileBuildingTheRouteLeavesTheRouterUnchanged() {
        IllegalStateException boom = new IllegalStateException("boom");
        SwitchableThrowingContributor contributor = new SwitchableThrowingContributor(boom);
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .operationHandlerContributors(Set.of(contributor))
                .build();
        SyntheticOperationInstaller installer = new SyntheticOperationInstaller(factory);

        Router router = Router.router(vertx);
        String path = "/management/openapi.json";
        List<HttpMethod> methods = List.of(HttpMethod.GET, HttpMethod.HEAD);
        SyntheticOperation operation =
                SyntheticOperation.withRoles(ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin"));

        RestConfigurationException thrown = assertThrows(
                RestConfigurationException.class,
                () -> installer.install(router, path, methods, operation, NOOP_TERMINAL));
        assertTrue(
                thrown.getMessage().startsWith(ORIGIN),
                "message should start with the origin but was: " + thrown.getMessage());
        assertSame(boom, thrown.getCause(), "the contributor's failure must be the cause");
        assertEquals(0, routesAt(router, path), "the partial route must be removed from the router");

        contributor.stopThrowing();
        assertDoesNotThrow(
                () -> installer.install(router, path, methods, operation, NOOP_TERMINAL),
                "the released operation id must install on the same router once the contributors pass");
        assertEquals(1, routesAt(router, path), "the corrected installation must add exactly one route");
    }

    private static long routesAt(Router router, String path) {
        return router.getRoutes().stream()
                .filter(route -> path.equals(route.getPath()))
                .count();
    }

    // --- Scheme handlers configured once per router ---

    @Test
    @DisplayName("Scheme handlers are configured once per router")
    void schemeHandlersAreConfiguredOncePerRouter() {
        StubSchemeHandler bearerAuth = new StubSchemeHandler(SCHEME, true);
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(bearerAuth))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .build();
        SyntheticOperationInstaller installer = new SyntheticOperationInstaller(factory);

        Router routerA = Router.router(vertx);
        Router routerB = Router.router(vertx);

        installer.install(
                routerA,
                "/management/openapi.json",
                List.of(HttpMethod.GET),
                SyntheticOperation.withRoles(ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin")),
                NOOP_TERMINAL);
        installer.install(
                routerA,
                "/other/openapi.json",
                List.of(HttpMethod.GET),
                SyntheticOperation.withRoles(ORIGIN, "apidocs:other:json", SCHEME, APPLICATION, List.of("admin")),
                NOOP_TERMINAL);

        assertEquals(
                1,
                bearerAuth.configureCalls(),
                "two installs on the same router must configure the scheme handler exactly once");

        installer.install(
                routerB,
                "/third/openapi.json",
                List.of(HttpMethod.GET),
                SyntheticOperation.withRoles(ORIGIN, "apidocs:third:json", SCHEME, APPLICATION, List.of("admin")),
                NOOP_TERMINAL);

        assertEquals(
                2,
                bearerAuth.configureCalls(),
                "installing on a second router must configure the scheme handler exactly once more");
    }

    // --- Contributor policy and order parity ---

    @Test
    @DisplayName("Contributors receive what an equally annotated resource method receives")
    void contributorsReceiveTheResourceEffectivePolicy() {
        List<String> invocationOrder = new ArrayList<>();
        CapturingContributor contributor100 = new CapturingContributor(100, "100", invocationOrder);
        CapturingContributor contributor45 = new CapturingContributor(45, "45", invocationOrder);
        // Unsorted set, the 100 priority contributor inserted first, so a caller that iterates it
        // directly (rather than through the OrderedExtension-sorted list) cannot accidentally match
        // priority order.
        Set<OperationHandlerContributor> contributors = new LinkedHashSet<>();
        contributors.add(contributor100);
        contributors.add(contributor45);

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .operationHandlerContributors(contributors)
                .build();
        SyntheticOperationInstaller installer = new SyntheticOperationInstaller(factory);

        SyntheticOperation managementOp =
                SyntheticOperation.withRoles(ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin"));
        SyntheticOperation authenticatedOp =
                SyntheticOperation.authenticated(ORIGIN, "apidocs:authenticated:json", SCHEME, "authenticated");

        Router router = Router.router(vertx);
        installer.install(
                router,
                "/management/openapi.json",
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                managementOp,
                NOOP_TERMINAL);
        installer.install(
                router,
                "/authenticated/openapi.json",
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                authenticatedOp,
                NOOP_TERMINAL);

        JaxRsRouterMount mount = factory.create("/api/mgmt/*", "openapi.json", Set.of(new TwinResource()));
        Future<Router> mountRouterFuture = mount.createRouter(vertx);
        assertTrue(mountRouterFuture.succeeded(), "the twin mount must build without error");

        assertContributorParity(contributor100, contributor45, invocationOrder, "apidocs:management:json", "doc");
        assertContributorParity(
                contributor100, contributor45, invocationOrder, "apidocs:authenticated:json", "authOnly");
    }

    private void assertContributorParity(
            CapturingContributor contributor100,
            CapturingContributor contributor45,
            List<String> invocationOrder,
            String syntheticOperationId,
            String twinOperationId) {
        OperationRegistrationContext synthetic = contributor100.captured(syntheticOperationId);
        OperationRegistrationContext twin = contributor100.captured(twinOperationId);
        assertTrue(synthetic != null, "the synthetic operation '" + syntheticOperationId + "' must be captured");
        assertTrue(twin != null, "the twin operation '" + twinOperationId + "' must be captured");

        assertEquals(
                twin.securityPolicy(),
                synthetic.securityPolicy(),
                "pair (" + syntheticOperationId + ", " + twinOperationId + "): securityPolicy() must match");
        assertEquals(
                Optional.empty(),
                synthetic.requiredAction(),
                "pair (" + syntheticOperationId + ", " + twinOperationId
                        + "): synthetic requiredAction() must be empty");
        assertEquals(
                Optional.empty(),
                twin.requiredAction(),
                "pair (" + syntheticOperationId + ", " + twinOperationId + "): twin requiredAction() must be empty");
        assertEquals(
                twin.operation().securityRequirementSets(),
                synthetic.operation().securityRequirementSets(),
                "pair (" + syntheticOperationId + ", " + twinOperationId + "): securityRequirementSets() must match");
        assertEquals(
                syntheticOperationId,
                synthetic.operation().operationId(),
                "the synthetic descriptor must report the synthetic operation id");

        assertEquals(1, contributor45.callCount(syntheticOperationId), "contributor 45 must run exactly once");
        assertEquals(1, contributor100.callCount(syntheticOperationId), "contributor 100 must run exactly once");
        int index45 = invocationOrder.indexOf("45:" + syntheticOperationId);
        int index100 = invocationOrder.indexOf("100:" + syntheticOperationId);
        assertTrue(index45 >= 0 && index100 >= 0, "both contributors must have run for " + syntheticOperationId);
        assertTrue(index45 < index100, "contributor 45 must run before contributor 100 for " + syntheticOperationId);
    }

    // --- Descriptor annotation parity ---

    @Test
    @DisplayName("The synthetic descriptor shows the security annotations of its resource twin")
    void syntheticDescriptorShowsTheTwinSecurityAnnotations() {
        CapturingContributor contributor = new CapturingContributor(50, "capture", new ArrayList<>());
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(new StubSchemeHandler(SCHEME, true)))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .operationHandlerContributors(Set.of(contributor))
                .build();
        SyntheticOperationInstaller installer = new SyntheticOperationInstaller(factory);

        SyntheticOperation managementOp =
                SyntheticOperation.withRoles(ORIGIN, "apidocs:management:json", SCHEME, APPLICATION, List.of("admin"));
        SyntheticOperation authenticatedOp =
                SyntheticOperation.authenticated(ORIGIN, "apidocs:authenticated:json", SCHEME, "authenticated");

        Router router = Router.router(vertx);
        installer.install(
                router,
                "/management/openapi.json",
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                managementOp,
                NOOP_TERMINAL);
        installer.install(
                router,
                "/authenticated/openapi.json",
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                authenticatedOp,
                NOOP_TERMINAL);

        JaxRsRouterMount mount = factory.create("/api/mgmt/*", "openapi.json", Set.of(new TwinResource()));
        assertTrue(mount.createRouter(vertx).succeeded(), "the twin mount must build without error");

        assertDescriptorMatchesTwin(contributor, "apidocs:management:json", "doc", "/management/openapi.json", true);
        assertDescriptorMatchesTwin(
                contributor, "apidocs:authenticated:json", "authOnly", "/authenticated/openapi.json", false);
    }

    private void assertDescriptorMatchesTwin(
            CapturingContributor contributor,
            String syntheticOperationId,
            String twinOperationId,
            String installedPath,
            boolean expectRolesAllowed) {
        var synthetic = contributor.captured(syntheticOperationId).operation();
        var twin = contributor.captured(twinOperationId).operation();

        assertEquals(
                2,
                synthetic.methodAnnotations().size(),
                "pair (" + syntheticOperationId + "): methodAnnotations() must hold exactly two annotations");

        assertAnnotationEquals(
                twin.findAnnotation(SecurityRequirement.class),
                synthetic.findAnnotation(SecurityRequirement.class),
                syntheticOperationId,
                "SecurityRequirement");
        if (expectRolesAllowed) {
            assertAnnotationEquals(
                    twin.findAnnotation(RolesAllowed.class),
                    synthetic.findAnnotation(RolesAllowed.class),
                    syntheticOperationId,
                    "RolesAllowed");
            assertTrue(
                    synthetic.findAnnotation(Authorized.class).isEmpty(),
                    "pair (" + syntheticOperationId + "): findAnnotation(Authorized.class) must be empty");
        } else {
            assertAnnotationEquals(
                    twin.findAnnotation(Authorized.class),
                    synthetic.findAnnotation(Authorized.class),
                    syntheticOperationId,
                    "Authorized");
            assertTrue(
                    synthetic.findAnnotation(RolesAllowed.class).isEmpty(),
                    "pair (" + syntheticOperationId + "): findAnnotation(RolesAllowed.class) must be empty");
        }

        assertTrue(
                synthetic.classAnnotations().isEmpty(),
                "pair (" + syntheticOperationId + "): classAnnotations() must be empty");
        assertTrue(synthetic.consumes().isEmpty(), "pair (" + syntheticOperationId + "): consumes() must be empty");
        assertTrue(synthetic.produces().isEmpty(), "pair (" + syntheticOperationId + "): produces() must be empty");
        assertEquals(
                installedPath,
                synthetic.routeTemplate(),
                "pair (" + syntheticOperationId + "): routeTemplate() must equal the literal install path");

        assertEquals(
                new SecurityPolicyBuilder().buildSecurityPolicy(List.of(), synthetic.methodAnnotations()),
                contributor.captured(syntheticOperationId).securityPolicy(),
                "pair (" + syntheticOperationId + "): the recorded securityPolicy() must be built from the "
                        + "reported annotations");
    }

    private <A extends Annotation> void assertAnnotationEquals(
            Optional<A> expected, Optional<A> actual, String operationId, String memberLabel) {
        assertTrue(
                expected.isPresent(),
                "pair (" + operationId + ", " + memberLabel + "): the twin must carry the annotation");
        assertTrue(
                actual.isPresent(),
                "pair (" + operationId + ", " + memberLabel + "): the synthetic descriptor must report " + memberLabel);
        assertEquals(
                expected.get(),
                actual.get(),
                "pair (" + operationId + ", " + memberLabel + "): annotation type and member values must match");
    }

    // --- Test doubles ---

    /**
     * A {@link SecuritySchemeHandler} whose {@code configure} call is counted, and which registers
     * an authentication handler only when {@code registersHandler} is {@code true}.
     */
    private static final class StubSchemeHandler implements SecuritySchemeHandler {
        private final String schemeName;
        private final boolean registersHandler;
        private int configureCalls;

        StubSchemeHandler(String schemeName, boolean registersHandler) {
            this.schemeName = schemeName;
            this.registersHandler = registersHandler;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            configureCalls++;
            if (registersHandler) {
                registry.authenticationHandler(RoutingContext::next);
            }
        }

        int configureCalls() {
            return configureCalls;
        }
    }

    /**
     * Throws its given failure from {@code contribute} until {@link #stopThrowing()} is called, and
     * contributes nothing afterwards, so one installer can see a failing and then a passing
     * contributor set.
     */
    private static final class SwitchableThrowingContributor implements OperationHandlerContributor {
        private final RuntimeException failure;
        private boolean throwing = true;

        SwitchableThrowingContributor(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            if (throwing) {
                throw failure;
            }
        }

        void stopThrowing() {
            throwing = false;
        }
    }

    /**
     * Captures every {@link OperationRegistrationContext} it contributes to, keyed by operation id,
     * and appends {@code label:operationId} to a shared invocation-order log so callers can compare
     * two contributors' relative order per operation.
     */
    private static final class CapturingContributor implements OperationHandlerContributor {
        private final int priority;
        private final String label;
        private final List<String> invocationOrder;
        private final Map<String, OperationRegistrationContext> capturedByOperationId = new LinkedHashMap<>();
        private final Map<String, Integer> callCounts = new LinkedHashMap<>();

        CapturingContributor(int priority, String label, List<String> invocationOrder) {
            this.priority = priority;
            this.label = label;
            this.invocationOrder = invocationOrder;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            capturedByOperationId.put(context.operationId(), context);
            callCounts.merge(context.operationId(), 1, Integer::sum);
            invocationOrder.add(label + ":" + context.operationId());
        }

        OperationRegistrationContext captured(String operationId) {
            return capturedByOperationId.get(operationId);
        }

        int callCount(String operationId) {
            return callCounts.getOrDefault(operationId, 0);
        }
    }

    /**
     * The resource twin of the IT fixture's protected documents: {@code GET /doc} mirrors {@code
     * apidocs:management:json} ({@code @RolesAllowed("admin")}), {@code GET /auth-only} mirrors
     * {@code apidocs:authenticated:json} ({@code @Authorized}).
     */
    @Path("")
    static final class TwinResource {

        @GET
        @Path("/doc")
        @SecurityRequirement(name = SCHEME)
        @RolesAllowed("admin")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "doc")
        public String doc() {
            return "doc";
        }

        @GET
        @Path("/auth-only")
        @SecurityRequirement(name = SCHEME)
        @Authorized
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "authOnly")
        public String authOnly() {
            return "auth-only";
        }
    }
}
