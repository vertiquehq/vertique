// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.openapi.docs.HiddenOperationTestComponents.ProtectedHiddenComponent;
import dev.vertique.rest.openapi.docs.HiddenOperationTestComponents.ProtectedVisibleWriteComponent;
import dev.vertique.rest.openapi.docs.HiddenOperationTestComponents.Renders;
import dev.vertique.rest.openapi.docs.HiddenOperationTestComponents.ServedComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.GeneratedHiddenClassResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.GeneratedHiddenContract;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.GeneratedHiddenContractResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenClassResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenContract;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenContractResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenGeneratedApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenOperationsApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.MixedOperationsResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.PartlyHiddenContract;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.ProtectedVisibleWriteApi;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments.Outcome;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Deploys declared applications whose operations are hidden in every way an operation can be hidden
 * (on the method, on the resource class, on a JAX-RS interface the resource implements, and on an
 * interface method) and checks that their documents keep no trace of them while their routes still
 * answer.
 *
 * <p>One composition serves the public documents of {@code hidden} and {@code hiddengen} under
 * {@code web-validation} with the canonical schema source over a real, loopback-bound {@code
 * HttpVerticle}; {@code hiddengen} lists the generated-path twins of the hidden resource class and
 * of the resource implementing a hidden interface. Two rendering components render, without serving,
 * the protected document of {@code hidden} and of the control application {@code visiblewrite},
 * which publishes the hidden write operation's body and hidden query binding on a visible operation.
 * Every hidden operation id, the tag only hidden operations declare, and every hidden name carry the
 * {@code Zx} suffix; no visible operation's content does. Expected values are fixed literals. One
 * Vert.x instance and one client serve the class; every deployment is undeployed, clearing the
 * published port, before the assertions run.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class HiddenOperationIT {

    private static final String HOST = "127.0.0.1";

    /** The root validation member that records reserved names removed from a protected document. */
    private static final String RESERVED_NAMES_REFUSED = "reservedNamesRefused";

    /** The root validation member that counts the inputs left out of a protected document. */
    private static final String HIDDEN_INPUTS = "hiddenInputs";

    /** The status of a {@code void} resource method that completed. */
    private static final int NO_CONTENT = 204;

    /** The only paths the document of {@code hidden} may hold, relative to its mount. */
    private static final List<String> VISIBLE_PATHS = List.of("/a/three", "/d/y");

    /** The path of the hidden operation whose query binding disagrees with its {@code @Parameter}. */
    private static final String DISAGREEING_PATH = "/a/four";

    /** The suffix every hidden operation id and hidden name carries. */
    private static final String HIDDEN_SUFFIX = "Zx";

    /** Every term only hidden content contributes: the operation ids, the tag, and the input names. */
    private static final List<String> HIDDEN_TERMS = List.of(
            "readOneZx",
            "writeTwoZx",
            "readFourZx",
            "readHiddenClassZx",
            "readHiddenContractZx",
            "readHiddenMethodZx",
            "readGeneratedClassZx",
            "readGeneratedContractZx",
            "hiddenOnlyZx",
            "flagZx",
            "modeZx");

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

    @Test
    @DisplayName(
            "Operations hidden on the method, the class, an interface, or an interface method leave no path, component, tag, or root flag in any document, and their routes still answer")
    void hiddenOperationsLeaveNoTraceAndStillRoute() throws Exception {
        // Given: the generated-path twins really are described by their companions, and the
        // reflective resources are not.
        GeneratedJaxRsDescriptorRegistry registry = GeneratedJaxRsDescriptorRegistry.shared();
        assertTrue(
                registry.lookup(GeneratedHiddenClassResource.class).isPresent(),
                "the generated twin of the hidden class must have a generated descriptor companion");
        assertTrue(
                registry.lookup(GeneratedHiddenContractResource.class).isPresent(),
                "the generated twin of the hidden-interface resource must have a generated descriptor companion");
        assertTrue(
                registry.lookup(HiddenClassResource.class).isEmpty(),
                "the reflective hidden class must have no generated descriptor companion");
        assertTrue(
                registry.lookup(HiddenContractResource.class).isEmpty(),
                "the reflective hidden-interface resource must have no generated descriptor companion");

        // Given: one served composition of 'hidden' and 'hiddengen', and two rendering components:
        // the protected twin of 'hidden', and the control 'visiblewrite'.
        ServedComponent served = DaggerHiddenOperationTestComponents_ServedComponent.factory()
                .create(webValidationConfig(HiddenOperationsApi.NAME, HiddenGeneratedApi.NAME));
        ProtectedHiddenComponent protectedHidden =
                DaggerHiddenOperationTestComponents_ProtectedHiddenComponent.factory()
                        .create(webValidationConfig());
        ProtectedVisibleWriteComponent protectedControl =
                DaggerHiddenOperationTestComponents_ProtectedVisibleWriteComponent.factory()
                        .create(webValidationConfig());

        // When: the protected renderings are read ...
        Rendered protectedRendering = renderProtected(protectedHidden, HiddenOperationsApi.NAME);
        Rendered controlRendering = renderProtected(protectedControl, ProtectedVisibleWriteApi.NAME);

        // ... then the served composition is deployed, both public documents read, and every hidden
        // route requested.
        Outcome outcome = StartupDeployments.deploy(vertx, served::httpVerticle);
        DisclosureDocuments.Rendering hiddenPublic;
        DisclosureDocuments.Rendering generatedPublic;
        List<RouteResult> routeResults;
        try {
            assertDeployed("the served composition of 'hidden' and 'hiddengen'", outcome);
            hiddenPublic = fetchPublic(outcome.port(), HiddenOperationsApi.NAME);
            generatedPublic = fetchPublic(outcome.port(), HiddenGeneratedApi.NAME);
            routeResults = requestHiddenRoutes(outcome.port());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }

        // Then: the control's protected rendering publishes the write operation and sets both root
        // flags, so their absence below is not vacuous.
        Executable control = () -> {
            assertTrue(
                    controlRendering.failure().isEmpty(),
                    () -> "the control rendering failed: " + controlRendering.failure());
            JsonObject controlRoot = controlRendering.document().rootValidation();
            assertAll(
                    "the control 'visiblewrite'; root: " + controlRoot,
                    () -> assertEquals(
                            List.of("/a/two"),
                            pathKeys(controlRendering.document().jsonTree()),
                            "the control publishes its visible write operation"),
                    () -> assertEquals(
                            Boolean.TRUE,
                            controlRoot == null ? null : controlRoot.getValue(RESERVED_NAMES_REFUSED),
                            "the control's protected root has " + RESERVED_NAMES_REFUSED + ": true"),
                    () -> assertEquals(
                            Boolean.TRUE,
                            controlRoot == null ? null : controlRoot.getValue(HIDDEN_INPUTS),
                            "the control's protected root has " + HIDDEN_INPUTS + ": true"));
        };

        // Then: 'hidden' publishes only its two visible operations, and 'hiddengen' nothing.
        Executable paths = () -> assertAll(
                "the published paths",
                () -> assertEquals(
                        VISIBLE_PATHS, pathKeys(hiddenPublic.jsonTree()), "the public JSON paths of 'hidden'"),
                () -> assertEquals(
                        VISIBLE_PATHS, pathKeys(hiddenPublic.yamlTree()), "the public YAML paths of 'hidden'"),
                () -> assertEquals(
                        VISIBLE_PATHS,
                        pathKeys(protectedRendering.document().jsonTree()),
                        "the protected JSON paths of 'hidden'"),
                () -> assertEquals(
                        List.of(), pathKeys(generatedPublic.jsonTree()), "the public JSON paths of 'hiddengen'"),
                () -> assertEquals(
                        List.of(), pathKeys(generatedPublic.yamlTree()), "the public YAML paths of 'hiddengen'"));

        // Then: no rendering holds a hidden term in any byte, or a component key from a hidden operation.
        List<Executable> traces = new ArrayList<>();
        traces.addAll(noTrace("the public document of 'hidden'", hiddenPublic));
        traces.addAll(noTrace("the public document of 'hiddengen'", generatedPublic));
        traces.add(() -> assertAll(
                "the protected document of 'hidden'",
                noTrace("the protected document of 'hidden'", protectedRendering.document()).stream()));
        Executable noTrace = () -> assertAll("no trace of a hidden operation", traces.stream());

        // Then: the protected root carries no flag only a hidden operation caused.
        Executable rootFlags = () -> {
            JsonObject protectedRoot = protectedRendering.document().rootValidation();
            assertAll(
                    "the protected root of 'hidden': " + protectedRoot,
                    () -> assertFalse(
                            protectedRoot != null && protectedRoot.containsKey(RESERVED_NAMES_REFUSED),
                            "the protected root has no " + RESERVED_NAMES_REFUSED),
                    () -> assertFalse(
                            protectedRoot != null && protectedRoot.containsKey(HIDDEN_INPUTS),
                            "the protected root has no " + HIDDEN_INPUTS));
        };

        // Then: the operation whose query binding disagrees with its @Parameter fails nothing and
        // publishes nothing.
        Executable disagreeing = () -> assertAll(
                "the hidden " + DISAGREEING_PATH,
                () -> assertTrue(
                        protectedRendering.failure().isEmpty(),
                        () -> "the protected rendering of 'hidden' failed: " + protectedRendering.failure()),
                () -> assertTrue(
                        hiddenPublic
                                .jsonTree()
                                .at("/paths/" + pointerSegment(DISAGREEING_PATH))
                                .isMissingNode(),
                        "the public JSON of 'hidden' has no " + DISAGREEING_PATH),
                () -> assertTrue(
                        hiddenPublic
                                .yamlTree()
                                .at("/paths/" + pointerSegment(DISAGREEING_PATH))
                                .isMissingNode(),
                        "the public YAML of 'hidden' has no " + DISAGREEING_PATH));

        // Then: every hidden route still answers.
        List<Executable> routeChecks = new ArrayList<>();
        for (RouteResult result : routeResults) {
            routeChecks.add(() -> assertEquals(
                    NO_CONTENT, result.exchange().status(), () -> result.route() + ": " + result.exchange()));
        }
        Executable routes = () -> assertAll("the hidden routes", routeChecks.stream());

        assertAll("hidden operations", control, paths, noTrace, rootFlags, disagreeing, routes);
    }

    // --- Hidden routes ---

    /**
     * Lists every hidden route of both applications. The write route is sent a body naming only the
     * published member, so the gate accepts it; the other routes are sent no body.
     */
    private static List<HiddenRoute> hiddenRoutes() {
        String mixed = HiddenOperationsApi.PATH + MixedOperationsResource.ROUTE;
        String hidden = HiddenOperationsApi.PATH;
        String generated = HiddenGeneratedApi.PATH;
        return List.of(
                new HiddenRoute(HttpMethod.GET, mixed + "/one", null),
                new HiddenRoute(
                        HttpMethod.POST,
                        mixed + "/two?" + MixedOperationsResource.FLAG + "=v",
                        new JsonObject().put("name", "n")),
                new HiddenRoute(HttpMethod.GET, mixed + "/four?" + MixedOperationsResource.MODE + "=m", null),
                new HiddenRoute(HttpMethod.GET, hidden + HiddenClassResource.ROUTE, null),
                new HiddenRoute(HttpMethod.GET, hidden + HiddenContract.ROUTE, null),
                new HiddenRoute(HttpMethod.GET, hidden + PartlyHiddenContract.ROUTE + "/x", null),
                new HiddenRoute(HttpMethod.GET, generated + GeneratedHiddenClassResource.ROUTE, null),
                new HiddenRoute(HttpMethod.GET, generated + GeneratedHiddenContract.ROUTE, null));
    }

    /** Requests every hidden route once and returns the answers in order. */
    private static List<RouteResult> requestHiddenRoutes(int port) throws Exception {
        List<RouteResult> results = new ArrayList<>();
        for (HiddenRoute route : hiddenRoutes()) {
            HttpRequest<Buffer> request = client.request(route.method(), port, HOST, route.uri());
            Future<HttpResponse<Buffer>> sent =
                    route.body() == null ? request.send() : request.sendJsonObject(route.body());
            results.add(new RouteResult(route.toString(), exchange(sent)));
        }
        return results;
    }

    /**
     * One hidden route.
     *
     * @param method the request method
     * @param uri    the request URI, query included
     * @param body   the JSON body, or {@code null} when none is sent
     */
    private record HiddenRoute(
            HttpMethod method, String uri, @Nullable JsonObject body) {

        @Override
        public String toString() {
            return method + " " + uri;
        }
    }

    /**
     * The answer of one hidden route.
     *
     * @param route    the route, as its method and URI
     * @param exchange the answer
     */
    private record RouteResult(String route, Exchange exchange) {}

    // --- Document checks ---

    /**
     * Returns the absences one rendering must satisfy in both forms: no hidden term in any byte, and
     * no component key carrying the hidden suffix.
     */
    private static List<Executable> noTrace(String label, DisclosureDocuments.Rendering rendering) {
        List<Executable> checks = new ArrayList<>();
        for (String form : List.of("JSON", "YAML")) {
            String where = label + " (" + form + ")";
            String text = form.equals("JSON") ? rendering.jsonText() : rendering.yamlText();
            for (String term : HIDDEN_TERMS) {
                checks.add(() -> assertFalse(text.contains(term), () -> where + " contains '" + term + "'"));
            }
            checks.add(() -> {
                JsonNode tree = form.equals("JSON") ? rendering.jsonTree() : rendering.yamlTree();
                List<String> keys = componentKeys(tree);
                assertTrue(
                        keys.stream().noneMatch(key -> key.contains(HIDDEN_SUFFIX)),
                        () -> where + " has a component key carrying '" + HIDDEN_SUFFIX + "': " + keys);
            });
        }
        return checks;
    }

    /** Returns the keys under {@code paths}, sorted; empty when {@code paths} is absent or empty. */
    private static List<String> pathKeys(JsonNode tree) {
        TreeSet<String> keys = new TreeSet<>();
        tree.path("paths").fieldNames().forEachRemaining(keys::add);
        return List.copyOf(keys);
    }

    /** Returns every key of every section under {@code components}, as {@code section/key}. */
    private static List<String> componentKeys(JsonNode tree) {
        List<String> keys = new ArrayList<>();
        for (var section : tree.path("components").properties()) {
            section.getValue().fieldNames().forEachRemaining(key -> keys.add(section.getKey() + "/" + key));
        }
        return keys;
    }

    // --- Shared helpers ---

    /**
     * Returns the loopback configuration with the {@code web-validation} strategy and an {@code info}
     * for each named document. A rendering component takes it without names: its sink renders with a
     * fixed {@code info} and reads no {@code apidocs} configuration.
     */
    private static JsonObject webValidationConfig(String... documentNames) {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        for (String name : documentNames) {
            DocsConfigs.withDocumentInfo(config, name, name, "1");
        }
        return config;
    }

    /**
     * Deploys a rendering component, reads the protected rendering of one application, and
     * undeploys it. A recorded assembly failure is returned, not thrown, so the test can assert it.
     */
    private static Rendered renderProtected(Renders component, String application) throws Exception {
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertDeployed("the protected rendering of '" + application + "'", outcome);
            ProtectedRenderingSink sink = component.protectedRendering();
            Optional<String> failure = sink.failure(application);
            if (failure.isPresent()) {
                return new Rendered(null, failure);
            }
            DisclosureDocuments.Rendering rendering =
                    new DisclosureDocuments.Rendering(sink.json(application), sink.yaml(application));
            return new Rendered(rendering, failure);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /**
     * One protected rendering, or the failure its assembly recorded.
     *
     * @param rendering the rendering, or {@code null} when assembly failed
     * @param failure   the recorded failure message, empty when the document was rendered
     */
    private record Rendered(@Nullable DisclosureDocuments.Rendering rendering, Optional<String> failure) {

        /** Returns the rendering, failing with the recorded message when assembly failed. */
        DisclosureDocuments.Rendering document() {
            assertNotNull(rendering, () -> "the protected document failed to assemble: " + failure.orElse(""));
            return rendering;
        }
    }

    /** Fetches both forms of an application's public document; each must answer {@code 200}. */
    private static DisclosureDocuments.Rendering fetchPublic(int port, String application) throws Exception {
        String base = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + application + "/";
        Exchange json = exchange(client.get(port, HOST, base + "openapi.json").send());
        Exchange yaml = exchange(client.get(port, HOST, base + "openapi.yaml").send());
        assertEquals(200, json.status(), () -> "the public JSON of '" + application + "': " + json);
        assertEquals(200, yaml.status(), () -> "the public YAML of '" + application + "': " + yaml);
        return new DisclosureDocuments.Rendering(json.bytes(), yaml.bytes());
    }

    private static void assertDeployed(String what, Outcome outcome) {
        assertNull(outcome.failure(), () -> what + ": the deployment failed: " + outcome.failure());
        assertNotNull(outcome.port(), () -> what + ": the deployment published no port");
    }

    private static String pointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static Exchange exchange(Future<HttpResponse<Buffer>> request) throws Exception {
        HttpResponse<Buffer> response =
                request.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        Buffer body = response.body();
        return new Exchange(response.statusCode(), body == null ? new byte[0] : body.getBytes());
    }

    /**
     * One answered request.
     *
     * @param status the status code
     * @param bytes  the body bytes, empty when there was none
     */
    private record Exchange(int status, byte[] bytes) {

        @Override
        public String toString() {
            return status + " " + new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
