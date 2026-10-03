// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.rest.openapi.docs.HiddenParityTestComponents.ServedComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.HiddenParityApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.HiddenInterface;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.HiddenTypeResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.MixedMethodsResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan.PartlyHiddenInterface;
import dev.vertique.rest.openapi.docs.fixture.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.jaxrs2.integration.JaxrsAnnotationScanner;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks that the runtime document of one declared application omits hidden operations exactly as
 * the build-time Maven plugin, configured as the services-codegen example configures it, omits them,
 * and that every hidden operation's route still answers.
 *
 * <p>The application {@code hiddenparity} lists one resource of every hiding placement (see {@link
 * HiddenParityApi}); those resources and the interfaces they implement are the whole content of the
 * package {@value #SCAN_PACKAGE}. Its component serves the public document under {@code
 * web-validation} with the canonical schema source over a real, loopback-bound {@code HttpVerticle} on
 * port 0.
 *
 * <p>The plugin side is modelled in the plugin's {@code resourcePackages} discovery mode, the mode the
 * example's plugin configuration uses: swagger-core's {@code JaxrsAnnotationScanner}, configured with
 * that package as its only resource package, discovers the {@code @Path}-annotated types, and
 * swagger-core's JAX-RS {@code Reader} reads exactly those. For resources whose annotations sit on an
 * interface the scanner finds the interface, not its unannotated implementation; reading the
 * implementation classes directly, as the plugin's {@code resourceClasses} mode would, keeps the
 * operations an interface-level placement hides. The discovered set is asserted against a literal
 * set of types, so the modelled discovery is explicit.
 *
 * <p>Operations are compared as {@code METHOD path} strings with the path relative to the
 * application's mount. The expected visible set is a fixed literal.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class HiddenOperationMavenParityIT {

    /** The longest one deployment, request, or undeployment is awaited. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String HOST = "127.0.0.1";

    /** The package holding exactly the hidden-placement resources and their interfaces. */
    static final String SCAN_PACKAGE = "dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan";

    /**
     * The types a package scan of {@value #SCAN_PACKAGE} discovers: the {@code @Path}-annotated
     * classes and interfaces, never the unannotated implementations of the interfaces.
     */
    private static final Set<Class<?>> SCANNED_TYPES = Set.of(
            MixedMethodsResource.class, HiddenTypeResource.class, HiddenInterface.class, PartlyHiddenInterface.class);

    /**
     * A completed {@code void} resource method answers with no content, status {@code 204}; every
     * fixture method is {@code void}.
     */
    private static final int NO_CONTENT = 204;

    /** The HTTP method keys a Path Item Object may hold; its other members are not operations. */
    private static final Set<String> OPERATION_KEYS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /**
     * The only operations the document may list, relative to the mount. One row per hiding placement:
     *
     * <pre>
     * placement                                   operation      listed
     * method @Hidden                              POST /a/two    no
     * method @Hidden                              GET  /a/four   no
     * method @Operation(hidden = true)            GET  /a/one    no
     * visible method                              GET  /a/three  yes
     * class @Hidden                               GET  /b        no
     * interface @Hidden                           GET  /c        no
     * interface method @Hidden                    GET  /d/x      no
     * visible interface method                    GET  /d/y      yes
     * </pre>
     */
    private static final SortedSet<String> VISIBLE_OPERATIONS = new TreeSet<>(Set.of("GET /a/three", "GET /d/y"));

    @Test
    @DisplayName(
            "The runtime document omits operations hidden on a method, a class, an interface, or an interface method exactly as the Maven plugin's package scan and reader do, and their routes still answer")
    void hiddenOperationsMatchThePluginsPackageScan(Vertx vertx) throws Exception {
        // Given: one documented application listing a resource of every hiding placement.
        ServedComponent component =
                DaggerHiddenParityTestComponents_ServedComponent.factory().create(vertx, webValidationConfig());
        WebClient client = DocumentRequests.separateConnectionsClient(vertx);
        List<String> deploymentIds = new ArrayList<>();

        // When: the composition is deployed, the runtime document fetched, and every hidden route
        // requested ...
        SortedSet<String> runtime;
        List<RouteResult> routeResults;
        try {
            int port = Deployments.deployAndReadPort(
                    vertx, component::httpVerticle, new DeploymentOptions(), deploymentIds, WAIT);
            DocumentRequests.Answer document = DocumentRequests.get(
                    client,
                    port,
                    DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + HiddenParityApi.NAME + "/openapi.json",
                    null,
                    WAIT);
            assertEquals(200, document.status(), () -> "the runtime document: " + text(document.body()));
            runtime = runtimeOperations(new JsonObject(Buffer.buffer(document.body())), HiddenParityApi.PATH);
            routeResults = requestHiddenRoutes(client, port);
        } finally {
            client.close();
            Deployments.undeployAll(vertx, deploymentIds, WAIT);
        }

        // ... and the plugin's discovery scans the package, and the reader reads what it found.
        Set<Class<?>> scanned = scanResourcePackage(SCAN_PACKAGE);
        SortedSet<String> reader = swaggerReaderOperations(scanned);

        // Then: the scan discovers exactly the @Path-annotated types.
        Executable scanDiscovers = () ->
                assertEquals(SCANNED_TYPES, scanned, "the types the package scan of " + SCAN_PACKAGE + " discovers");

        // Then: the runtime document lists exactly the visible operations.
        Executable runtimeVisible = () ->
                assertEquals(VISIBLE_OPERATIONS, runtime, "the runtime document's operations, relative to the mount");

        // Then: the reader keeps exactly the operations the runtime document lists.
        Executable readerAgrees = () -> {
            SortedSet<String> kept = new TreeSet<>(reader);
            kept.removeAll(runtime);
            SortedSet<String> dropped = new TreeSet<>(runtime);
            dropped.removeAll(reader);
            assertEquals(
                    runtime,
                    reader,
                    () -> "the Swagger reader's operations differ from the runtime document's; the reader kept "
                            + kept + " that the runtime hides, and dropped " + dropped
                            + " that the runtime lists");
        };

        // Then: every hidden operation's route still answers.
        List<Executable> routeChecks = new ArrayList<>();
        for (RouteResult result : routeResults) {
            routeChecks.add(() -> assertEquals(
                    NO_CONTENT,
                    result.status(),
                    () -> result.route() + " answered " + result.status() + ": " + result.body()));
        }
        Executable routesAnswer = () -> assertAll("the hidden routes", routeChecks.stream());

        assertAll(
                "hidden operations against the plugin's package scan and reader",
                scanDiscovers,
                runtimeVisible,
                readerAgrees,
                routesAnswer);
    }

    // --- Operation sets ---

    /**
     * Returns the operations a runtime document lists as {@code METHOD path} strings, sorted.
     *
     * <p>The runtime document carries the application's mount in {@code servers}, so its {@code
     * paths} keys must be relative to the mount; they are taken verbatim, and a key that starts with
     * the application path fails the check. The method is the upper-cased Path Item key; members that
     * are not HTTP methods are ignored.
     *
     * @param document        the runtime document
     * @param applicationPath the application's path, for example {@code /api/hiddenparity}
     * @return the operations, sorted
     */
    private static SortedSet<String> runtimeOperations(JsonObject document, String applicationPath) {
        SortedSet<String> operations = new TreeSet<>();
        JsonObject paths = document.getJsonObject("paths", new JsonObject());
        for (Map.Entry<String, Object> path : paths) {
            assertFalse(
                    path.getKey().startsWith(applicationPath),
                    () -> "the runtime document's path key " + path.getKey() + " is not relative to the mount "
                            + applicationPath);
            for (String key : ((JsonObject) path.getValue()).fieldNames()) {
                if (OPERATION_KEYS.contains(key)) {
                    operations.add(key.toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        return operations;
    }

    /**
     * Discovers resource types as the Maven plugin does when configured with {@code
     * resourcePackages}: swagger-core's {@code JaxrsAnnotationScanner}, configured with the package
     * as its only resource package.
     *
     * @param resourcePackage the package to scan
     * @return the discovered types
     */
    private static Set<Class<?>> scanResourcePackage(String resourcePackage) {
        PackageScanner scanner = new PackageScanner();
        scanner.setConfiguration(new SwaggerConfiguration().resourcePackages(Set.of(resourcePackage)));
        return scanner.classes();
    }

    /**
     * swagger-core's {@code JaxrsAnnotationScanner} with its self type bound; it overrides nothing, so
     * it scans exactly as the scanner does.
     */
    private static final class PackageScanner extends JaxrsAnnotationScanner<PackageScanner> {}

    /**
     * Reads the discovered types with swagger-core's JAX-RS {@code Reader}, which the build-time
     * Maven plugin runs, and returns the operations of the document it produces as {@code METHOD path}
     * strings, sorted. The reader is given an empty base document and no application path, so its
     * paths are relative to the resources.
     *
     * @param resourceClasses the discovered types to read
     * @return the operations, sorted
     */
    private static SortedSet<String> swaggerReaderOperations(Set<Class<?>> resourceClasses) {
        Reader reader = new Reader();
        reader.setConfiguration(new SwaggerConfiguration().openAPI(new OpenAPI()));
        OpenAPI openApi = reader.read(resourceClasses);
        SortedSet<String> operations = new TreeSet<>();
        Paths paths = openApi.getPaths();
        if (paths == null) {
            return operations;
        }
        for (Map.Entry<String, PathItem> path : paths.entrySet()) {
            for (PathItem.HttpMethod method :
                    path.getValue().readOperationsMap().keySet()) {
                operations.add(method.name() + " " + path.getKey());
            }
        }
        return operations;
    }

    // --- Hidden routes ---

    /**
     * Lists the route of every hidden operation, under the application path. No route takes input, so
     * none is sent a query or a body.
     */
    private static List<HiddenRoute> hiddenRoutes() {
        String app = HiddenParityApi.PATH;
        String mixed = app + MixedMethodsResource.ROUTE;
        return List.of(
                new HiddenRoute(HttpMethod.GET, mixed + "/one"),
                new HiddenRoute(HttpMethod.POST, mixed + "/two"),
                new HiddenRoute(HttpMethod.GET, mixed + "/four"),
                new HiddenRoute(HttpMethod.GET, app + HiddenTypeResource.ROUTE),
                new HiddenRoute(HttpMethod.GET, app + HiddenInterface.ROUTE),
                new HiddenRoute(HttpMethod.GET, app + PartlyHiddenInterface.ROUTE + "/x"));
    }

    /** Requests every hidden route once and returns the answers in order. */
    private static List<RouteResult> requestHiddenRoutes(WebClient client, int port) throws Exception {
        List<RouteResult> results = new ArrayList<>();
        for (HiddenRoute route : hiddenRoutes()) {
            HttpRequest<Buffer> request = client.request(route.method(), port, HOST, route.uri());
            HttpResponse<Buffer> response = Futures.await(request.send(), WAIT);
            Buffer body = response.body();
            results.add(new RouteResult(
                    route.toString(),
                    response.statusCode(),
                    body == null ? "" : body.toString(StandardCharsets.UTF_8)));
        }
        return results;
    }

    /**
     * One hidden route.
     *
     * @param method the request method
     * @param uri    the request URI
     */
    private record HiddenRoute(HttpMethod method, String uri) {

        @Override
        public String toString() {
            return method + " " + uri;
        }
    }

    /**
     * The answer of one hidden route.
     *
     * @param route  the route, as its method and URI
     * @param status the status code
     * @param body   the body text, empty when there was none
     */
    private record RouteResult(String route, int status, String body) {}

    // --- Shared helpers ---

    /**
     * Returns the loopback configuration with the {@code web-validation} strategy and no {@code
     * apidocs} section: the document's {@code info} comes from the declaring interface.
     */
    private static JsonObject webValidationConfig() {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        return config;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
