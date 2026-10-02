// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.openapi.docs.ProtectedStartupTestComponents.ProtectedStartupGraph;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deploys compositions whose protected document of application {@code management} is
 * misconfigured, one defect per row, and observes that startup fails before the server listens with
 * a message naming the application, its declaring interface, and the violated rule, and never the
 * configured sentinel value. Each row also builds the composition's documentation mount on its own
 * router, after the composition's validators marked it, and observes that the build fails and leaves
 * that router without a single route, also when a valid protected document of another application
 * is installed before the failing one.
 *
 * <p>Every row builds a fresh component, deploys its Dagger-built {@code HttpVerticle} through
 * {@link StartupDeployments}, which provisions the verticle inside the deployment, and undeploys in
 * a {@code finally} block. Every expectation is a hand-written literal.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProtectedDocumentStartupFailureIT {

    /** The sentinel {@code serverUrl} every configuration sets for {@code management}. */
    private static final String SENTINEL_SERVER_URL = "/sentinel-zx";

    /** The part of the sentinel no failure message may contain. */
    private static final String SENTINEL = "sentinel-zx";

    /** The misconfigured application, quoted as every message names it. */
    private static final String QUOTED_MANAGEMENT = "'management'";

    /** The binary name of the declaration naming the unregistered scheme {@code nope}. */
    private static final String UNKNOWN_SCHEME_API =
            "dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.UnknownSchemeManagementApi";

    /** The binary name of the declaration naming the scheme {@code emptyAuth}. */
    private static final String HANDLERLESS_SCHEME_API =
            "dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.HandlerlessSchemeManagementApi";

    /** The binary name of the declaration naming {@code bearerAuth} and the role {@code admin}. */
    private static final String ROLE_API =
            "dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.RoleManagementApi";

    /** The binary name of the declaration naming {@code bearerAuth} and no role. */
    private static final String ROLELESS_API =
            "dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.RolelessManagementApi";

    /** The installer's origin of the protected document declared by the {@code emptyAuth} interface. */
    private static final String HANDLERLESS_ORIGIN =
            "Protected API document of application 'management' (access policy: @ApiDocs on "
                    + "dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.HandlerlessSchemeManagementApi): ";

    /** The attribute naming a protected document's security scheme. */
    private static final String SECURITY_SCHEME_ATTRIBUTE = "@ApiDocs.securityScheme";

    /** The attribute declaring a document's access. */
    private static final String ACCESS_ATTRIBUTE = "@ApiDocs.access";

    /** The unregistered scheme, quoted. */
    private static final String QUOTED_NOPE = "'nope'";

    /** The scheme whose handler registers no authentication handler, quoted. */
    private static final String QUOTED_EMPTY_AUTH = "'emptyAuth'";

    /** The rule of a scheme no registered handler has. */
    private static final String NO_HANDLER = "no registered SecuritySchemeHandler has that name";

    /** The rule of a protected document without authentication enforcement. */
    private static final String NO_ENFORCEMENT = "authentication enforcement is not installed";

    /** The rule of a scheme whose registered handler configured no authentication handler. */
    private static final String NO_AUTHENTICATION_HANDLER = "registered no authentication handler";

    /** The order in which {@code HttpVerticle} hands the mounts to its composition validators. */
    private static final Comparator<RouterMount> HTTP_VERTICLE_ORDER = Comparator.comparing(RouterMount::phase)
            .thenComparingInt(RouterMount::priority)
            .thenComparing(RouterMount::mountPath)
            .thenComparing(RouterMount::orderKey);

    @AfterEach
    void clearPublishedPort(Vertx vertx) {
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).remove(StartupDeployments.PORT_KEY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misconfigurations")
    @DisplayName(
            "a misconfigured protected document fails startup before listening, naming the application, its interface, and the rule, and its documentation router keeps no route")
    void misconfiguredProtectedDocumentFailsBeforeAnyRoute(
            String label,
            Function<JsonObject, ProtectedStartupGraph> graph,
            String declaringInterface,
            @Nullable String origin,
            List<String> ruleFragments,
            Vertx vertx)
            throws Exception {
        // Given: the row's composition, configured with management's sentinel serverUrl
        ProtectedStartupGraph component = graph.apply(sentinelConfiguration());

        // When: it is deployed
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: startup failed before listening, naming management, its interface, and the rule
            assertStartupFailure(label, outcome, declaringInterface, origin, ruleFragments);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }

        // When: the composition's documentation mount, marked by its validators, builds a fresh router
        DocsRouterMount docsMount = markedDocsMount(label, component);
        Router router = Router.router(vertx);
        RestConfigurationException refusal = assertThrows(
                RestConfigurationException.class, () -> docsMount.buildInto(router), label + ": the build fails");

        // Then: the build failed and the router holds no route
        List<String> routes = router.getRoutes().stream().map(Route::getPath).toList();
        assertEquals(
                List.of(),
                routes,
                () -> label + ": the documentation router holds no route after the refusal " + refusal.getMessage());
    }

    static Stream<Arguments> misconfigurations() {
        return Stream.of(
                Arguments.of(
                        "(1) securityScheme 'nope', registered by no handler",
                        graph(DaggerProtectedStartupTestComponents_UnknownSchemeComponent.factory()::create),
                        UNKNOWN_SCHEME_API,
                        null,
                        List.of(SECURITY_SCHEME_ATTRIBUTE, QUOTED_NOPE, NO_HANDLER)),
                Arguments.of(
                        "(2) securityScheme 'emptyAuth', whose handler configures no authentication handler",
                        graph(DaggerProtectedStartupTestComponents_HandlerlessSchemeComponent.factory()::create),
                        HANDLERLESS_SCHEME_API,
                        HANDLERLESS_ORIGIN,
                        List.of(QUOTED_EMPTY_AUTH, NO_AUTHENTICATION_HANDLER)),
                Arguments.of(
                        "(3) no authentication enforcement, rolesAllowed {admin}",
                        graph(DaggerProtectedStartupTestComponents_RoleWithoutEnforcementComponent.factory()::create),
                        ROLE_API,
                        null,
                        List.of(ACCESS_ATTRIBUTE, NO_ENFORCEMENT)),
                Arguments.of(
                        "(4) no authentication enforcement, no rolesAllowed",
                        graph(config ->
                                DaggerProtectedStartupTestComponents_RolelessWithoutEnforcementComponent.factory()
                                        .create(config)),
                        ROLELESS_API,
                        null,
                        List.of(ACCESS_ATTRIBUTE, NO_ENFORCEMENT)),
                Arguments.of(
                        "(5, alpha) case (2) beside the valid protected document of 'alpha'",
                        graph(DaggerProtectedStartupTestComponents_AlphaBesideHandlerlessComponent.factory()::create),
                        HANDLERLESS_SCHEME_API,
                        HANDLERLESS_ORIGIN,
                        List.of(QUOTED_EMPTY_AUTH, NO_AUTHENTICATION_HANDLER)),
                Arguments.of(
                        "(5, zeta) case (2) beside the valid protected document of 'zeta'",
                        graph(DaggerProtectedStartupTestComponents_ZetaBesideHandlerlessComponent.factory()::create),
                        HANDLERLESS_SCHEME_API,
                        HANDLERLESS_ORIGIN,
                        List.of(QUOTED_EMPTY_AUTH, NO_AUTHENTICATION_HANDLER)));
    }

    /** Widens a component factory to the graph type the rows share. */
    private static Function<JsonObject, ProtectedStartupGraph> graph(
            Function<JsonObject, ? extends ProtectedStartupGraph> factory) {
        return factory::apply;
    }

    /**
     * Returns the loopback configuration with {@code apidocs.documents.management} set to
     * {@code {"enabled": true, "serverUrl": "/sentinel-zx"}}.
     */
    private static JsonObject sentinelConfiguration() {
        JsonObject management = new JsonObject().put("enabled", true).put("serverUrl", SENTINEL_SERVER_URL);
        JsonObject documents = new JsonObject().put("management", management);
        return DocsConfigs.loopback().put("apidocs", new JsonObject().put("documents", documents));
    }

    /**
     * Provisions the component's mounts, runs every composition validator over them in
     * {@code HttpVerticle} order, as the composition does, and returns the one documentation mount,
     * which the validators must have marked.
     */
    private static DocsRouterMount markedDocsMount(String label, ProtectedStartupGraph component) {
        List<RouterMount> mounts =
                component.routerMounts().stream().sorted(HTTP_VERTICLE_ORDER).toList();
        List<String> violations = new ArrayList<>();
        for (MountCompositionValidator validator : component.mountCompositionValidators()) {
            violations.addAll(validator.validate(mounts));
        }
        assertEquals(List.of(), violations, label + ": the composition validators report no violation");
        List<DocsRouterMount> docsMounts = mounts.stream()
                .filter(DocsRouterMount.class::isInstance)
                .map(DocsRouterMount.class::cast)
                .toList();
        assertEquals(1, docsMounts.size(), label + ": the composition holds one documentation mount");
        DocsRouterMount docsMount = docsMounts.get(0);
        assertTrue(docsMount.isValidated(), label + ": the composition validators marked the documentation mount");
        return docsMount;
    }

    /**
     * Asserts a refused startup: the deployment failed and published no port; its message names
     * {@code 'management'}, the declaring interface's binary name, and every rule fragment, starts
     * with the installer's origin when one is given, and never contains the sentinel.
     *
     * @param label              the row
     * @param outcome            the deployment's outcome
     * @param declaringInterface the binary name of {@code management}'s declaring interface
     * @param origin             the installer origin the message must start with, or {@code null}
     * @param ruleFragments      the texts naming the violated rule
     */
    private static void assertStartupFailure(
            String label,
            Outcome outcome,
            String declaringInterface,
            @Nullable String origin,
            List<String> ruleFragments) {
        assertAll(
                label + ": startup is refused before listening",
                () -> assertNotNull(outcome.failure(), label + ": the deployment succeeded on port " + outcome.port()),
                () -> assertNull(outcome.port(), label + ": no port is published"));

        Throwable failure = outcome.failure();
        String message = failure.getMessage();
        assertNotNull(message, () -> label + ": the failure has a message: " + failure);
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertTrue(message.contains(QUOTED_MANAGEMENT), label + ": names " + QUOTED_MANAGEMENT));
        checks.add(() -> assertTrue(message.contains(declaringInterface), label + ": names " + declaringInterface));
        if (origin != null) {
            checks.add(() -> assertTrue(message.startsWith(origin), label + ": starts with the origin " + origin));
        }
        for (String fragment : ruleFragments) {
            checks.add(() -> assertTrue(message.contains(fragment), label + ": names " + fragment));
        }
        checks.add(() -> assertFalse(message.contains(SENTINEL), label + ": never names " + SENTINEL));
        assertAll(label + ": the failure " + failure, checks.stream());
    }
}
