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
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedStartupApis;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import dev.vertique.rest.openapi.docs.serving.DocsRouterMount;
import dev.vertique.rest.openapi.docs.serving.ServingAccess;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    /**
     * The start of the installer's origin of the protected document of {@code management}. Only the
     * start is pinned: the rest of the origin names the declaration and may be reworded.
     */
    private static final String INSTALLER_ORIGIN = "Protected API document of application 'management' (";

    /** The attribute naming a protected document's security scheme. */
    private static final String SECURITY_SCHEME_ATTRIBUTE = "@ApiDocs.securityScheme";

    /** The unregistered scheme, quoted. */
    private static final String QUOTED_NOPE = "'nope'";

    /** The scheme whose handler registers no authentication handler, quoted. */
    private static final String QUOTED_EMPTY_AUTH = "'emptyAuth'";

    /** The rule of a scheme no registered handler has. */
    private static final String NO_HANDLER = "no registered SecuritySchemeHandler has that name";

    /**
     * The words of the rule of a restrictive document policy without authentication enforcement. Only
     * these words are pinned: the rest of the sentence names the policy and may be reworded.
     */
    private static final String NO_ENFORCEMENT = "authentication enforcement";

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
                RestConfigurationException.class,
                () -> ServingAccess.buildInto(docsMount, router),
                label + ": the build fails");

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
                        INSTALLER_ORIGIN,
                        List.of(QUOTED_EMPTY_AUTH, NO_AUTHENTICATION_HANDLER)),
                Arguments.of(
                        "(3) no authentication enforcement, a roles policy",
                        graph(DaggerProtectedStartupTestComponents_RoleWithoutEnforcementComponent.factory()::create),
                        ROLE_API,
                        null,
                        List.of(NO_ENFORCEMENT)),
                Arguments.of(
                        "(4) no authentication enforcement, an authenticated-only policy",
                        graph(config ->
                                DaggerProtectedStartupTestComponents_RolelessWithoutEnforcementComponent.factory()
                                        .create(config)),
                        ROLELESS_API,
                        null,
                        List.of(NO_ENFORCEMENT)),
                Arguments.of(
                        "(5, alpha) case (2) beside the valid protected document of 'alpha'",
                        graph(DaggerProtectedStartupTestComponents_AlphaBesideHandlerlessComponent.factory()::create),
                        HANDLERLESS_SCHEME_API,
                        INSTALLER_ORIGIN,
                        List.of(QUOTED_EMPTY_AUTH, NO_AUTHENTICATION_HANDLER)),
                Arguments.of(
                        "(5, zeta) case (2) beside the valid protected document of 'zeta'",
                        graph(DaggerProtectedStartupTestComponents_ZetaBesideHandlerlessComponent.factory()::create),
                        HANDLERLESS_SCHEME_API,
                        INSTALLER_ORIGIN,
                        List.of(QUOTED_EMPTY_AUTH, NO_AUTHENTICATION_HANDLER)));
    }

    /**
     * Every combination of document policy and security scheme, deployed one at a time on the real
     * authentication modules: a public policy needs an empty scheme, every restrictive policy,
     * deny included, needs a registered scheme, any unknown scheme refuses startup, and a composition
     * without authentication enforcement refuses a restrictive document. Each row is a fresh
     * deployment, so one failing row cannot mask another.
     */
    @Test
    @DisplayName("Document policy and scheme combinations start or refuse startup; enforcement is required")
    void shouldRejectInvalidSchemesAndMissingActualEnforcement(Vertx vertx) throws Exception {
        List<Executable> checks = new ArrayList<>();

        // Given: declarations the final contract refuses at startup, each naming a scheme problem
        // When: each is deployed on the real JWT authentication modules
        List<Refusal> refusals = List.of(
                new Refusal("public policy with a registered scheme", TypedStartupApis.PublicWithScheme.class),
                new Refusal("public policy with an unknown scheme", TypedStartupApis.PublicWithUnknownScheme.class),
                new Refusal("deny policy with no scheme", TypedStartupApis.DenyWithoutScheme.class),
                new Refusal("authenticated policy with no scheme", TypedStartupApis.AuthenticatedWithoutScheme.class),
                new Refusal(
                        "authenticated policy with a blank scheme",
                        TypedStartupApis.AuthenticatedWithBlankScheme.class),
                new Refusal(
                        "authenticated policy with an unknown scheme",
                        TypedStartupApis.AuthenticatedWithUnknownScheme.class),
                new Refusal("deny policy with an unknown scheme", TypedStartupApis.DenyWithUnknownScheme.class));
        for (Refusal refusal : refusals) {
            Throwable failure = TypedDocumentDeployment.startupFailure(typedConfiguration(), refusal.declaration());

            // Then: startup is refused, naming the application, its interface and the scheme attribute
            checks.add(() -> assertNotNull(failure, refusal.label() + ": startup must be refused"));
            if (failure != null) {
                String message = String.valueOf(failure.getMessage());
                checks.add(() -> assertTrue(
                        message.contains(QUOTED_MANAGEMENT), refusal.label() + ": names " + QUOTED_MANAGEMENT));
                checks.add(() -> assertTrue(
                        message.contains(refusal.declaration().getName()),
                        refusal.label() + ": names " + refusal.declaration().getName()));
                checks.add(() -> assertTrue(
                        message.contains(SECURITY_SCHEME_ATTRIBUTE),
                        refusal.label() + ": names " + SECURITY_SCHEME_ATTRIBUTE + ": " + message));
            }
        }

        // Given: the declaration naming an unknown scheme
        // Then: the refusal names the scheme and the missing handler
        Throwable unknown = TypedDocumentDeployment.startupFailure(
                typedConfiguration(), TypedStartupApis.AuthenticatedWithUnknownScheme.class);
        checks.add(() -> assertNotNull(unknown, "unknown scheme: startup must be refused"));
        if (unknown != null) {
            checks.add(() -> assertTrue(
                    String.valueOf(unknown.getMessage()).contains(QUOTED_NOPE)
                            && String.valueOf(unknown.getMessage()).contains(NO_HANDLER),
                    "unknown scheme: names " + QUOTED_NOPE + " and the missing handler: " + unknown.getMessage()));
        }

        // Given: a composition without authentication enforcement: the bearer scheme handler is bound
        // and neither the authentication nor the security module is
        // When: a restrictive document is deployed on it
        ProtectedStartupGraph unenforced =
                DaggerProtectedStartupTestComponents_RoleWithoutEnforcementComponent.factory()
                        .create(sentinelConfiguration());
        Outcome refused = StartupDeployments.deploy(vertx, unenforced::httpVerticle);
        try {
            // Then: startup is refused before listening for the missing authentication enforcement
            checks.add(() -> assertNotNull(refused.failure(), "no enforcement: startup must be refused"));
            checks.add(() -> assertNull(refused.port(), "no enforcement: no port is published"));
            if (refused.failure() != null) {
                String message = String.valueOf(refused.failure().getMessage());
                checks.add(() -> assertTrue(message.contains(QUOTED_MANAGEMENT), "no enforcement: names management"));
                checks.add(() -> assertTrue(message.contains(ROLE_API), "no enforcement: names " + ROLE_API));
                checks.add(() -> assertTrue(
                        message.contains(NO_ENFORCEMENT), "no enforcement: names the missing enforcement: " + message));
            }
        } finally {
            StartupDeployments.undeploy(vertx, refused);
        }

        // Given: a public policy with an empty scheme
        // When: it is deployed and read anonymously, with GET and HEAD
        try (TypedDocumentDeployment deployment =
                TypedDocumentDeployment.startSingle(typedConfiguration(), TypedStartupApis.PublicWithoutScheme.class)) {
            TypedDocumentDeployment.Reply get = deployment.request(HttpMethod.GET, DOCUMENT, null, Map.of());
            TypedDocumentDeployment.Reply head = deployment.request(HttpMethod.HEAD, DOCUMENT, null, Map.of());

            // Then: it is the public classification: served without credentials, no Vary, not private
            checks.add(() -> assertEquals(200, get.status(), "public: GET status"));
            checks.add(() -> assertEquals(200, head.status(), "public: HEAD status"));
            checks.add(() ->
                    assertEquals(List.of("no-cache"), get.headers().getAll("Cache-Control"), "public: Cache-Control"));
            checks.add(() -> assertEquals(List.of(), get.headers().getAll("Vary"), "public: no Vary"));
        }

        // Given: a deny policy with a registered scheme
        // When: it is deployed and read anonymously, by an authenticated caller, and conditionally
        try (TypedDocumentDeployment deployment =
                TypedDocumentDeployment.startSingle(typedConfiguration(), TypedStartupApis.DenyWithScheme.class)) {
            String token = deployment.token("alice", List.of("admin"), null);
            List<TypedDocumentDeployment.Reply> replies = new ArrayList<>();
            List<Integer> expected = new ArrayList<>();
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("If-None-Match", "*"))) {
                    replies.add(deployment.request(method, DOCUMENT, null, headers));
                    expected.add(401);
                    replies.add(deployment.request(method, DOCUMENT, token, headers));
                    expected.add(403);
                }
            }

            // Then: it starts, authenticates and then denies, and never serves bytes or a 304
            for (int index = 0; index < replies.size(); index++) {
                TypedDocumentDeployment.Reply reply = replies.get(index);
                int status = expected.get(index);
                String row = "deny request " + index;
                checks.add(() -> assertEquals(status, reply.status(), row + ": status"));
                checks.add(() -> TypedDocumentExpectations.assertNeverServed(row, reply));
            }
        }

        // Given: an authenticated-only policy with the real bearer scheme
        // When: it is deployed and read with no credential, a forged one, and a valid one
        try (TypedDocumentDeployment deployment = TypedDocumentDeployment.startSingle(
                typedConfiguration(), TypedStartupApis.AuthenticatedWithScheme.class)) {
            String token = deployment.token("alice", List.of("user"), null);
            TypedDocumentDeployment.Reply missing = deployment.request(HttpMethod.GET, DOCUMENT, null, Map.of());
            TypedDocumentDeployment.Reply forged =
                    deployment.request(HttpMethod.GET, DOCUMENT, "forged.invalid.token", Map.of());
            TypedDocumentDeployment.Reply forgedConditional =
                    deployment.request(HttpMethod.GET, DOCUMENT, "forged.invalid.token", Map.of("If-None-Match", "*"));
            TypedDocumentDeployment.Reply valid = deployment.request(HttpMethod.GET, DOCUMENT, token, Map.of());
            String tag = valid.header("ETag");
            TypedDocumentDeployment.Reply revalidated =
                    deployment.request(HttpMethod.GET, DOCUMENT, token, Map.of("If-None-Match", String.valueOf(tag)));

            // Then: missing and invalid credentials are 401 with no bytes and no 304; the valid caller
            // is served privately and revalidated
            checks.add(() -> assertEquals(401, missing.status(), "authenticated: missing credential"));
            checks.add(() -> assertEquals(401, forged.status(), "authenticated: forged credential"));
            checks.add(() -> assertEquals(401, forgedConditional.status(), "authenticated: forged conditional"));
            for (TypedDocumentDeployment.Reply reply : List.of(missing, forged, forgedConditional)) {
                checks.add(() -> TypedDocumentExpectations.assertNeverServed("authenticated denial", reply));
            }
            checks.add(() -> assertEquals(200, valid.status(), "authenticated: valid credential"));
            checks.add(() -> assertNotNull(tag, "authenticated: the valid read carries an entity tag"));
            checks.add(() -> assertEquals(
                    List.of("private, no-store"),
                    valid.headers().getAll("Cache-Control"),
                    "authenticated: Cache-Control"));
            checks.add(() ->
                    assertEquals(List.of("Authorization"), valid.headers().getAll("Vary"), "authenticated: Vary"));
            checks.add(() -> assertEquals(304, revalidated.status(), "authenticated: revalidation"));
        }
        assertAll(checks);
    }

    /** One refused combination. */
    private record Refusal(String label, Class<?> declaration) {}

    /** The path of the JSON document of {@code management}. */
    private static final String DOCUMENT = "/apidocs/management/openapi.json";

    /** The configuration of a typed single-document deployment: loopback, the JWT scheme, info. */
    private static JsonObject typedConfiguration() {
        return DocsConfigs.withDocumentInfo(
                TypedDocumentDeployment.configuration(), "management", "Typed startup", "1");
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
        assertTrue(
                ServingAccess.isValidated(docsMount),
                label + ": the composition validators marked the documentation mount");
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
