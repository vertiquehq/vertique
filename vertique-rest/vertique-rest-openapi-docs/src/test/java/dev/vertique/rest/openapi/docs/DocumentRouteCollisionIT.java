// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.openapi.docs.CollisionTestComponents.CaseMountComponent;
import dev.vertique.rest.openapi.docs.CollisionTestComponents.DocumentedProvisions;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CaseMount;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CaseResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CaseResources;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CatchAllResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.DocsNameResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetApidocsRestResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetDigitsIdResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetDottedDocumentNameResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetDottedNamesResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetIdResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetOpenapiExtensionResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetPublicJsonDocumentResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetRestResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetThreeSegmentsResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetTwoSegmentsResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetUpperApidocsResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.GetXResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.HeadThreeSegmentsResource;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.PostThreeSegmentsResource;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deploys compositions whose JAX-RS routes can or cannot answer a document URL and observes startup
 * and real routing: a {@code GET} or {@code HEAD} route that can answer a document URL fails startup
 * naming the route, the URL, and the URL's application, while other routes deploy beside the
 * document; and the route matcher's verdict agrees with what Vert.x routing actually answers.
 *
 * <p>Every row deploys one composition through {@link StartupDeployments}, which reads the bound
 * port from the {@code vertique} local map and clears it before the next deployment, and undeploys
 * it before the next row. A request counts as answered by a case resource when that resource's
 * answer count grows ({@link CaseResource#hits()}), which also works for {@code HEAD}.
 *
 * <p>The class timeout is 60 seconds rather than the usual 20 because one row runs sequential
 * deployment steps whose individual bounds add up past 20 seconds: a deployment and an undeployment,
 * each awaited up to {@link StartupDeployments#BOUND}, plus up to three requests, each awaited up to
 * 10 seconds.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DocumentRouteCollisionIT {

    /** The host every server binds and every request dials. */
    private static final String LOOPBACK = "127.0.0.1";

    /** The longest one request is awaited, in seconds. */
    private static final long REQUEST_BOUND_SECONDS = 10;

    /** A marker no failure message may contain. */
    private static final String ZQ7 = "zq7";

    /** The binary name of the root application's declaring interface. */
    private static final String ROOT_API_BINARY = "dev.vertique.rest.openapi.docs.fixture.startup.RootApi";

    /** The binary name of the documented neighbour application's declaring interface. */
    private static final String PUBLIC_ROOT_API_BINARY =
            "dev.vertique.rest.openapi.docs.fixture.startup.collision.PublicRootApi";

    /** The remedy of choosing another documentation prefix. */
    private static final String CHOOSE_ANOTHER_PREFIX = "choose an apidocs.path";

    /** The remedy of narrowing or removing the colliding route. */
    private static final String NARROW_OR_REMOVE_ROUTE = "narrow or remove the route";

    /** The configured {@code info.title} of the root application's document {@code api}. */
    private static final String ROOT_TITLE = "Root";

    /** The configured {@code info.version} of the root application's document {@code api}. */
    private static final String ROOT_VERSION = "1";

    /** The configured {@code info.title} of the neighbour's document {@code public}. */
    private static final String PUBLIC_TITLE = "Catalog";

    /** The configured {@code info.version} of the neighbour's document {@code public}. */
    private static final String PUBLIC_VERSION = "1.0";

    private static Vertx vertx;

    private static WebClient client;

    @BeforeAll
    static void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    // ---------------------------------------------------------------------------------------------
    // Colliding GET or HEAD routes fail startup
    // ---------------------------------------------------------------------------------------------

    /**
     * The startup table: each row's label names its case, its composition, the documentation
     * prefix it configures ({@code null} for the default {@code /apidocs}), its resources, and
     * whether it is refused naming the listed document URLs or deploys.
     */
    static Stream<CollisionRow> collisionRows() {
        return Stream.of(
                new CollisionRow(
                        "(a) GET /{id} and GET /{a}/{b} deploy beside the document",
                        Composition.ROOT_APPLICATION,
                        null,
                        List.of(new GetIdResource(), new GetTwoSegmentsResource()),
                        new Deploys(
                                "/apidocs/api/openapi.json",
                                ROOT_TITLE,
                                List.of(new Control(HttpMethod.GET, "/x", 0), new Control(HttpMethod.GET, "/x/y", 1)))),
                new CollisionRow(
                        "(b) GET /{a}/{b}/{c} is refused",
                        Composition.ROOT_APPLICATION,
                        null,
                        List.of(new GetThreeSegmentsResource()),
                        new Refused(
                                "GET /{a}/{b}/{c}",
                                "getThreeSegments",
                                "/*",
                                "api",
                                ROOT_API_BINARY,
                                List.of("/apidocs/api/openapi.json", "/apidocs/api/openapi.yaml"),
                                List.of())),
                new CollisionRow(
                        "(c) HEAD-only /{a}/{b}/{c} is refused",
                        Composition.ROOT_APPLICATION,
                        null,
                        List.of(new HeadThreeSegmentsResource()),
                        new Refused(
                                "HEAD /{a}/{b}/{c}",
                                "headThreeSegments",
                                "/*",
                                "api",
                                ROOT_API_BINARY,
                                List.of("/apidocs/api/openapi.json", "/apidocs/api/openapi.yaml"),
                                List.of())),
                new CollisionRow(
                        "(d) POST-only /{a}/{b}/{c} deploys beside the document",
                        Composition.ROOT_APPLICATION,
                        null,
                        List.of(new PostThreeSegmentsResource()),
                        new Deploys(
                                "/apidocs/api/openapi.json",
                                ROOT_TITLE,
                                List.of(new Control(HttpMethod.POST, "/p/q/r", 0)))),
                new CollisionRow(
                        "(e) GET /apidocs/{rest: .+} is refused",
                        Composition.ROOT_APPLICATION,
                        null,
                        List.of(new GetApidocsRestResource()),
                        new Refused(
                                "GET /apidocs/{rest: .+}",
                                "getApidocsRest",
                                "/*",
                                "api",
                                ROOT_API_BINARY,
                                List.of("/apidocs/api/openapi.json", "/apidocs/api/openapi.yaml"),
                                List.of())),
                new CollisionRow(
                        "(f) GET /{a}/{b}/{c} deploys with apidocs.path /docs/v1/api",
                        Composition.ROOT_APPLICATION,
                        "/docs/v1/api",
                        List.of(new GetThreeSegmentsResource()),
                        new Deploys(
                                "/docs/v1/api/api/openapi.json",
                                ROOT_TITLE,
                                List.of(new Control(HttpMethod.GET, "/p/q/r", 0)))),
                new CollisionRow(
                        "(g) GET /apidocs/{rest: .+} deploys with apidocs.path /docs",
                        Composition.ROOT_APPLICATION,
                        "/docs",
                        List.of(new GetApidocsRestResource()),
                        new Deploys(
                                "/docs/api/openapi.json",
                                ROOT_TITLE,
                                List.of(new Control(HttpMethod.GET, "/apidocs/x", 0)))),
                new CollisionRow(
                        "(j) catch-all GET /{path: .*} is refused under the default prefix",
                        Composition.ROOT_APPLICATION,
                        null,
                        List.of(new CatchAllResource()),
                        new Refused(
                                "GET /{path: .*}",
                                "catchAll",
                                "/*",
                                "api",
                                ROOT_API_BINARY,
                                List.of("/apidocs/api/openapi.json", "/apidocs/api/openapi.yaml"),
                                List.of())),
                new CollisionRow(
                        "(k) catch-all GET /{path: .*} is refused with apidocs.path /docs/v1",
                        Composition.ROOT_APPLICATION,
                        "/docs/v1",
                        List.of(new CatchAllResource()),
                        new Refused(
                                "GET /{path: .*}",
                                "catchAll",
                                "/*",
                                "api",
                                ROOT_API_BINARY,
                                List.of("/docs/v1/api/openapi.json", "/docs/v1/api/openapi.yaml"),
                                List.of())),
                new CollisionRow(
                        "(h) an undocumented mount's GET /docs/{name}/openapi.json is refused",
                        Composition.UNDOCUMENTED_NEIGHBOUR,
                        "/api/docs",
                        List.of(new CatalogResource(), new DocsNameResource()),
                        new Refused(
                                "GET /docs/{name}/openapi.json",
                                "getDocsName",
                                "/api/*",
                                "public",
                                PUBLIC_ROOT_API_BINARY,
                                List.of("/api/docs/public/openapi.json"),
                                List.of("/api/docs/public/openapi.yaml"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collisionRows")
    @DisplayName(
            "A GET or HEAD route that can answer a document URL fails startup naming it; other routes deploy beside the document")
    void collidingGetOrHeadRoutesFailStartup(CollisionRow row) throws Exception {
        // Given: the row's composition, configuration, and case resources
        DocumentedProvisions component = row.component();

        // When: the composition is deployed
        StartupDeployments.Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: it is refused naming the colliding route and URLs, or it deploys and both the
            // document and the case routes answer
            switch (row.expected()) {
                case Refused refused -> assertRefused(row, refused, outcome, component);
                case Deploys deploys -> assertDeploys(row, deploys, outcome);
            }
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    private static void assertRefused(
            CollisionRow row, Refused refused, StartupDeployments.Outcome outcome, DocumentedProvisions component)
            throws Exception {
        String unexpected = outcome.deployed() ? describeDeployed(row, refused, outcome) : "";
        Set<String> storedNames = component.documentStore().names();
        assertAll(
                "the composition is refused before it publishes anything",
                () -> assertNotNull(outcome.failure(), () -> "the composition deployed; " + unexpected),
                () -> assertNull(outcome.port(), "no port is published"),
                () -> assertEquals(Set.of(), storedNames, "no document is stored"));

        Throwable failure = outcome.failure();
        RestConfigurationException refusal =
                assertInstanceOf(RestConfigurationException.class, failure, () -> "the failure: " + failure);
        String message = refusal.getMessage();
        assertNotNull(message, "the refusal has a message");

        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertTrue(message.contains(refused.route()), "names the route " + refused.route()));
        checks.add(() -> assertTrue(
                message.contains("'" + refused.operationId() + "'"),
                "names the operation id " + refused.operationId()));
        checks.add(
                () -> assertTrue(message.contains("'" + refused.mount() + "'"), "names the mount " + refused.mount()));
        checks.add(() -> assertTrue(
                message.contains("'" + refused.application() + "'"), "names the application " + refused.application()));
        checks.add(() -> assertTrue(
                message.contains(refused.declaringInterface()),
                "names the declaring interface " + refused.declaringInterface()));
        for (String url : refused.namedUrls()) {
            checks.add(() -> assertTrue(message.contains("'" + url + "'"), "names the document URL " + url));
        }
        for (String url : refused.unnamedUrls()) {
            checks.add(() -> assertFalse(message.contains(url), "does not name the document URL " + url));
        }
        if (refused.namedUrls().size() > 1) {
            String first = "'" + refused.namedUrls().get(0) + "'";
            String second = "'" + refused.namedUrls().get(1) + "'";
            checks.add(() -> assertTrue(
                    message.indexOf(first) >= 0 && message.indexOf(first) < message.indexOf(second),
                    "names " + first + " before " + second));
        }
        checks.add(() ->
                assertTrue(message.contains(CHOOSE_ANOTHER_PREFIX), "states the remedy: " + CHOOSE_ANOTHER_PREFIX));
        checks.add(() ->
                assertTrue(message.contains(NARROW_OR_REMOVE_ROUTE), "states the remedy: " + NARROW_OR_REMOVE_ROUTE));
        checks.add(() -> assertFalse(message.contains(ZQ7), "does not contain " + ZQ7));
        assertAll("the refusal's message: " + message, checks.stream());
    }

    private static String describeDeployed(CollisionRow row, Refused refused, StartupDeployments.Outcome outcome)
            throws Exception {
        String url = refused.namedUrls().get(0);
        if (outcome.port() == null) {
            return "no port was published";
        }
        HttpResponse<Buffer> response = send(HttpMethod.GET, outcome.port(), url);
        StringBuilder described =
                new StringBuilder("GET ").append(url).append(" answered ").append(response.statusCode());
        for (Object resource : row.resources()) {
            if (resource instanceof CaseResource caseResource) {
                described
                        .append("; ")
                        .append(caseResource.getClass().getSimpleName())
                        .append(" answered ")
                        .append(caseResource.hits())
                        .append(" request(s)");
            }
        }
        return described.toString();
    }

    private static void assertDeploys(CollisionRow row, Deploys deploys, StartupDeployments.Outcome outcome)
            throws Exception {
        assertTrue(outcome.deployed(), () -> "the composition deploys; it failed with: " + outcome.failure());
        assertNotNull(outcome.port(), "a port is published");
        int port = outcome.port();

        HttpResponse<Buffer> document = send(HttpMethod.GET, port, deploys.documentUrl());
        List<Executable> documentChecks = new ArrayList<>();
        documentChecks.add(
                () -> assertEquals(200, document.statusCode(), "GET " + deploys.documentUrl() + " answers 200"));
        documentChecks.add(() -> assertEquals(
                deploys.title(),
                infoTitle(document),
                "the document's info.title is the configured " + deploys.title()));
        for (Object resource : row.resources()) {
            if (resource instanceof CaseResource caseResource) {
                documentChecks.add(() -> assertEquals(
                        0,
                        caseResource.hits(),
                        caseResource.getClass().getSimpleName() + " did not answer the document request"));
            }
        }
        assertAll("the document is served by the documentation mount", documentChecks.stream());

        for (Control control : deploys.controls()) {
            CaseResource resource = (CaseResource) row.resources().get(control.resourceIndex());
            int before = resource.hits();
            HttpResponse<Buffer> response = send(control.method(), port, control.uri());
            assertAll(
                    control.method() + " " + control.uri() + " is answered by "
                            + resource.getClass().getSimpleName(),
                    () -> assertEquals(200, response.statusCode(), "status"),
                    () -> assertEquals(resource.marker(), response.bodyAsString(), "body"),
                    () -> assertEquals(before + 1, resource.hits(), "the resource's answer count"));
        }
    }

    private static @Nullable String infoTitle(HttpResponse<Buffer> response) {
        try {
            JsonObject info = new JsonObject(response.bodyAsString()).getJsonObject("info");
            return info == null ? null : info.getString("title");
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    @Test
    @DisplayName("Several colliding routes of one mount are listed one line each, by document URL, then template")
    void collidingRoutesAreListedByUrlThenTemplate() throws Exception {
        // Given: the documented root application api at / holding GET /apidocs/{rest: .+},
        // GET /{rest: .*}, and GET /{a}/{b}/{c}; Vert.x registers the regex route /{rest: .*} before
        // /{a}/{b}/{c} as the more specific one, the reverse of their template order
        JsonObject config = DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), "api", ROOT_TITLE, ROOT_VERSION);
        DocumentedProvisions component = DaggerCollisionTestComponents_RootApplicationComponent.factory()
                .create(
                        config,
                        new CaseResources(List.of(
                                new GetApidocsRestResource(), new GetRestResource(), new GetThreeSegmentsResource())));

        // When: the composition is deployed
        StartupDeployments.Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: one refusal lists every route per document URL, the URLs in order, and within a
            // URL the routes by template (every route here is a GET)
            assertNotNull(outcome.failure(), "the composition is refused");
            RestConfigurationException refusal = assertInstanceOf(
                    RestConfigurationException.class, outcome.failure(), () -> "the failure: " + outcome.failure());
            String message = refusal.getMessage();
            assertNotNull(message, "the refusal has a message");
            List<List<String>> expectedLines = List.of(
                    List.of("GET /apidocs/{rest: .+}", "'/apidocs/api/openapi.json'"),
                    List.of("GET /{a}/{b}/{c}", "'/apidocs/api/openapi.json'"),
                    List.of("GET /{rest: .*}", "'/apidocs/api/openapi.json'"),
                    List.of("GET /apidocs/{rest: .+}", "'/apidocs/api/openapi.yaml'"),
                    List.of("GET /{a}/{b}/{c}", "'/apidocs/api/openapi.yaml'"),
                    List.of("GET /{rest: .*}", "'/apidocs/api/openapi.yaml'"));
            List<String> lines = List.of(message.split("\n"));
            assertEquals(expectedLines.size(), lines.size(), () -> "one line per route and URL: " + message);
            List<Executable> checks = new ArrayList<>();
            for (int i = 0; i < expectedLines.size(); i++) {
                String line = lines.get(i);
                for (String fragment : expectedLines.get(i)) {
                    int lineNumber = i + 1;
                    checks.add(() -> assertTrue(line.contains(fragment), "line " + lineNumber + " names " + fragment));
                }
            }
            checks.add(() -> assertFalse(message.contains(ZQ7), "does not contain " + ZQ7));
            assertAll("the refusal's message: " + message, checks.stream());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** The compositions of the startup table. */
    enum Composition {

        /** The documented root application {@code api} at {@code /}, the sole registration. */
        ROOT_APPLICATION,

        /** The documented {@code public} at {@code /public} beside the undocumented {@code api} at {@code /api}. */
        UNDOCUMENTED_NEIGHBOUR
    }

    /** What a startup row expects. */
    sealed interface Expected permits Refused, Deploys {}

    /**
     * Startup is refused.
     *
     * @param route              the colliding route as {@code <METHOD> <template>}
     * @param operationId        the route's operation id
     * @param mount              the route's mount path
     * @param application        the application the named URLs belong to
     * @param declaringInterface the binary name of that application's declaring interface
     * @param namedUrls          the document URLs the message names, in the order it names them
     * @param unnamedUrls        document URLs the message must not name
     */
    record Refused(
            String route,
            String operationId,
            String mount,
            String application,
            String declaringInterface,
            List<String> namedUrls,
            List<String> unnamedUrls)
            implements Expected {}

    /**
     * The composition deploys.
     *
     * @param documentUrl the JSON document URL the documentation mount answers
     * @param title       the document's expected {@code info.title}
     * @param controls    requests each answered by one of the row's case resources
     */
    record Deploys(String documentUrl, String title, List<Control> controls) implements Expected {}

    /**
     * A request a case resource answers.
     *
     * @param method        the request method
     * @param uri           the request URI
     * @param resourceIndex the index of the answering resource in the row's resources
     */
    record Control(HttpMethod method, String uri, int resourceIndex) {}

    /**
     * One row of the startup table.
     *
     * @param label       the row's display label
     * @param composition the composition deployed
     * @param apidocsPath the configured {@code apidocs.path}, or {@code null} for the default
     * @param resources   the composition's resource instances
     * @param expected    the expected outcome
     */
    record CollisionRow(
            String label,
            Composition composition,
            @Nullable String apidocsPath,
            List<Object> resources,
            Expected expected) {

        /** Builds this row's configuration: loopback, the document's {@code info}, and the prefix if any. */
        JsonObject configuration() {
            JsonObject config = DocsConfigs.loopback();
            switch (composition) {
                case ROOT_APPLICATION -> DocsConfigs.withDocumentInfo(config, "api", ROOT_TITLE, ROOT_VERSION);
                case UNDOCUMENTED_NEIGHBOUR ->
                    DocsConfigs.withDocumentInfo(config, "public", PUBLIC_TITLE, PUBLIC_VERSION);
            }
            if (apidocsPath != null) {
                DocsConfigs.withApidocsPath(config, apidocsPath);
            }
            return config;
        }

        /** Builds this row's component. */
        DocumentedProvisions component() {
            CaseResources caseResources = new CaseResources(resources);
            return switch (composition) {
                case ROOT_APPLICATION ->
                    DaggerCollisionTestComponents_RootApplicationComponent.factory()
                            .create(configuration(), caseResources);
                case UNDOCUMENTED_NEIGHBOUR ->
                    DaggerCollisionTestComponents_UndocumentedNeighbourComponent.factory()
                            .create(configuration(), caseResources);
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The route matcher agrees with Vert.x routing
    // ---------------------------------------------------------------------------------------------

    /**
     * The matcher table: case, mount path, documentation prefix, the case resource's template and
     * method, and the pinned verdicts for the {@code .json} and {@code .yaml} URLs of document
     * {@code public} under the prefix.
     */
    static Stream<MatcherCase> matcherCases() {
        return Stream.of(
                new MatcherCase(
                        "(1)",
                        "/*",
                        "/apidocs",
                        "/apidocs/public/openapi.json",
                        HttpMethod.GET,
                        GetPublicJsonDocumentResource::new,
                        Pinned.JSON_ONLY),
                new MatcherCase("(2)", "/*", "/apidocs", "/{id}", HttpMethod.GET, GetIdResource::new, Pinned.NEITHER),
                new MatcherCase(
                        "(3)",
                        "/*",
                        "/apidocs",
                        "/{a}/{b}",
                        HttpMethod.GET,
                        GetTwoSegmentsResource::new,
                        Pinned.NEITHER),
                new MatcherCase(
                        "(4)",
                        "/*",
                        "/apidocs",
                        "/{a}/{b}/{c}",
                        HttpMethod.GET,
                        GetThreeSegmentsResource::new,
                        Pinned.BOTH),
                new MatcherCase(
                        "(5)",
                        "/*",
                        "/apidocs",
                        "/{id: [0-9]+}",
                        HttpMethod.GET,
                        GetDigitsIdResource::new,
                        Pinned.NEITHER),
                new MatcherCase(
                        "(6)", "/*", "/apidocs", "/{rest: .*}", HttpMethod.GET, GetRestResource::new, Pinned.BOTH),
                new MatcherCase(
                        "(7)",
                        "/*",
                        "/apidocs",
                        "/apidocs/{rest: .+}",
                        HttpMethod.GET,
                        GetApidocsRestResource::new,
                        Pinned.BOTH),
                new MatcherCase(
                        "(8)",
                        "/*",
                        "/apidocs",
                        "/{a}/{b}/openapi.{ext}",
                        HttpMethod.GET,
                        GetOpenapiExtensionResource::new,
                        Pinned.EQUALITY_ONLY),
                new MatcherCase(
                        "(9)",
                        "/*",
                        "/apidocs",
                        "/APIDOCS/{b}/{c}",
                        HttpMethod.GET,
                        GetUpperApidocsResource::new,
                        Pinned.NEITHER),
                new MatcherCase(
                        "(10)",
                        "/*",
                        "/apidocs",
                        "/{a.b}/{c-d}/{e}",
                        HttpMethod.GET,
                        GetDottedNamesResource::new,
                        Pinned.EQUALITY_ONLY),
                new MatcherCase(
                        "(11)",
                        "/*",
                        "/apidocs",
                        "/{a}/{b}/{c}",
                        HttpMethod.HEAD,
                        HeadThreeSegmentsResource::new,
                        Pinned.BOTH),
                new MatcherCase(
                        "(12)",
                        "/*",
                        "/apidocs",
                        "/{a}/{b}/{c}",
                        HttpMethod.POST,
                        PostThreeSegmentsResource::new,
                        Pinned.NEITHER),
                new MatcherCase(
                        "(13)",
                        "/api/*",
                        "/api/docs",
                        "/docs/{name}/openapi.json",
                        HttpMethod.GET,
                        DocsNameResource::new,
                        Pinned.JSON_ONLY),
                new MatcherCase(
                        "(14)", "/api/*", "/api/docs", "/{x}", HttpMethod.GET, GetXResource::new, Pinned.NEITHER),
                new MatcherCase(
                        "(15)",
                        "/other/*",
                        "/apidocs",
                        "/{a}/{b}/{c}",
                        HttpMethod.GET,
                        GetThreeSegmentsResource::new,
                        Pinned.NEITHER),
                new MatcherCase(
                        "(16)",
                        "/*",
                        "/apidocs",
                        "/{a}/public.openapi.json",
                        HttpMethod.GET,
                        GetDottedDocumentNameResource::new,
                        Pinned.NEITHER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matcherCases")
    @DisplayName(
            "The route matcher's verdict for each document URL equals whether Vert.x routes that URL to the case resource")
    void matcherAgreesWithVertxRouting(MatcherCase matcherCase) throws Exception {
        // Given: no documentation module, no registrations, and one hand-built JAX-RS mount holding
        // the case resource
        CaseResource resource = matcherCase.resource().get();
        CaseMountComponent component = DaggerCollisionTestComponents_CaseMountComponent.factory()
                .create(DocsConfigs.loopback(), new CaseMount(matcherCase.mountPath(), resource));
        StartupDeployments.Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertTrue(outcome.deployed(), () -> "the case mount deploys; it failed with: " + outcome.failure());
            assertNotNull(outcome.port(), "a port is published");
            MountPublication publication = component.publicationSink().publication(matcherCase.mountPath());
            assertNotNull(publication, "the case mount's publication is recorded");
            assertEquals(1, publication.operations().size(), () -> "one operation: " + publication.operations());
            OperationPublication operation = publication.operations().get(0);
            assertEquals(matcherCase.template(), operation.jaxRsPathTemplate(), "the recorded template");
            assertEquals(matcherCase.method().name(), operation.httpMethod(), "the recorded method");

            // When: both URLs of document public under the case prefix are judged by the matcher and
            // requested for real
            List<String> urls = List.of(
                    matcherCase.prefix() + "/public/openapi.json", matcherCase.prefix() + "/public/openapi.yaml");
            HttpMethod requestMethod = matcherCase.requestMethod();
            List<Executable> checks = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                boolean verdict = DocumentRouteMatcher.canAnswer(publication.mountPath(), operation, url);
                int before = resource.hits();
                HttpResponse<Buffer> response = send(requestMethod, outcome.port(), url);
                boolean answered = resource.hits() > before;

                // Then: the verdict equals real routing, and equals the pinned verdict where one is pinned
                checks.add(() -> assertEquals(
                        answered,
                        verdict,
                        "the matcher's verdict for " + url + " equals whether Vert.x routed "
                                + requestMethod + " " + url + " to the case resource (status "
                                + response.statusCode() + ")"));
                Boolean pinned = matcherCase.pinned().verdict(i);
                if (pinned != null) {
                    checks.add(() -> assertEquals(pinned, Boolean.valueOf(verdict), "the pinned verdict for " + url));
                }
            }
            assertAll(
                    matcherCase.label() + " " + matcherCase.method() + " " + matcherCase.template() + " on "
                            + matcherCase.mountPath(),
                    checks.stream());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** The pinned verdicts for the {@code .json} and {@code .yaml} URLs. */
    enum Pinned {

        /** Only the {@code .json} URL can be answered. */
        JSON_ONLY(true, false),

        /** Both URLs can be answered. */
        BOTH(true, true),

        /** Neither URL can be answered. */
        NEITHER(false, false),

        /** Nothing is pinned: the row asserts only that the verdict equals real routing. */
        EQUALITY_ONLY(null, null);

        private final Boolean json;

        private final Boolean yaml;

        Pinned(Boolean json, Boolean yaml) {
            this.json = json;
            this.yaml = yaml;
        }

        /** Returns the pinned verdict for URL {@code 0} ({@code .json}) or {@code 1} ({@code .yaml}), or {@code null}. */
        @Nullable
        Boolean verdict(int index) {
            return index == 0 ? json : yaml;
        }
    }

    /**
     * One row of the matcher table.
     *
     * @param label     the case number
     * @param mountPath the case mount's literal path
     * @param prefix    the documentation prefix the URLs lie under
     * @param template  the case resource's JAX-RS template
     * @param method    the case resource's method, as the publication records it
     * @param resource  creates the case resource
     * @param pinned    the pinned verdicts
     */
    record MatcherCase(
            String label,
            String mountPath,
            String prefix,
            String template,
            HttpMethod method,
            Supplier<CaseResource> resource,
            Pinned pinned) {

        /**
         * Returns the method of the real document request: {@code HEAD} for a {@code HEAD} route,
         * {@code GET} otherwise, as a document is requested.
         */
        HttpMethod requestMethod() {
            return HttpMethod.HEAD.equals(method) ? HttpMethod.HEAD : HttpMethod.GET;
        }

        @Override
        public String toString() {
            return label + " " + method + " " + template + " on " + mountPath + " with prefix " + prefix;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------------

    private static HttpResponse<Buffer> send(HttpMethod method, int port, String uri) throws Exception {
        return Futures.await(
                client.request(method, port, LOOPBACK, uri).send(), Duration.ofSeconds(REQUEST_BOUND_SECONDS));
    }
}
