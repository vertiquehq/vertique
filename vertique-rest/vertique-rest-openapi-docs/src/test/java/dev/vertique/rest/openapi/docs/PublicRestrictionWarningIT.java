// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.security.catalog.WarningCapture;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proof that a public document of a discovery application mixing open and restricted
 * resources logs one warning that names every published operation restricting callers.
 *
 * <p>The sole discovery application {@code catalog} at {@code /api} carries
 * {@code @ApiDocs(access = PUBLIC)}. Its mixed catalog holds one open operation ({@code @PermitAll}
 * {@code listCatalog}) and three that each restrict callers in a different way: a role
 * ({@code adminReport}), a scopeless security requirement ({@code scopelessGet}), and a required
 * action ({@code actionGet}). Three deployments are observed:
 *
 * <ul>
 *   <li>the mixed catalog, one {@code HttpVerticle} instance: exactly one restriction warning, which
 *       starts with the document's configuration path, names the mount as registered, lists the
 *       three restricting operations sorted by path then method, and names no open operation, role,
 *       or action;
 *   <li>the mixed catalog, two instances of one component: still exactly one warning;
 *   <li>a catalog holding only the open resource: no restriction warning.
 * </ul>
 *
 * <p>Restriction warnings are the {@code WARN} events on the documentation module's warning logger
 * whose message starts with {@value #DOCUMENT_PATH} and contains {@value #RESTRICT_FRAGMENT}; any
 * other warning on that logger, or on another logger, is not part of this proof.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PublicRestrictionWarningIT {

    /** The documentation module's warning logger. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The configuration path of the {@code catalog} document, which starts each of its warnings. */
    private static final String DOCUMENT_PATH = "apidocs.documents.catalog";

    /** The fragment that marks the restriction warning among the document's warnings. */
    private static final String RESTRICT_FRAGMENT = "restrict callers";

    /** The fragment after which the warning lists its entries, joined by {@value #ENTRY_SEPARATOR}. */
    private static final String LIST_FRAGMENT = "served without authentication: ";

    /** The separator between two listed entries. */
    private static final String ENTRY_SEPARATOR = ", ";

    /** The quoted mount, as the application registers it at {@code /api}. */
    private static final String MOUNT_FRAGMENT = "'/api";

    /** The restricting operations, sorted by path then method. */
    private static final List<String> RESTRICTED_ENTRIES =
            List.of("GET /action (actionGet)", "GET /admin (adminReport)", "GET /scopeless (scopelessGet)");

    /** What the warning must never name: the open operation, the role, and the action. */
    private static final List<String> ABSENT_FROM_WARNING = List.of("listCatalog", "catalog-admin", "items.read");

    /** Every operation the mixed catalog's document publishes: path to the operation id of its GET. */
    private static final Map<String, String> MIXED_OPERATIONS = Map.of(
            "/catalog", "listCatalog",
            "/admin", "adminReport",
            "/scopeless", "scopelessGet",
            "/action", "actionGet");

    /** The URL of the document's JSON form. */
    private static final String DOCUMENT_URL = "/apidocs/catalog/openapi.json";

    /** The number of instances the two-instance case deploys. */
    private static final int INSTANCES = 2;

    /** The longest one document request is awaited. */
    private static final long REQUEST_SECONDS = 5;

    /** Captures the warning logger's events for the current test. */
    private WarningCapture capture;

    /** Sends the document requests; closed after each test. */
    private WebClient client;

    @BeforeEach
    void captureWarnings() {
        capture = WarningCapture.attach(WARNINGS_LOGGER);
    }

    @AfterEach
    void releaseWarningsAndClient() {
        capture.detach();
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /**
     * Deploys the mixed catalog once and twice, and the open catalog once, and checks the
     * restriction warnings each deployment logs.
     *
     * @param vertx the Vert.x instance
     */
    @Test
    @DisplayName(
            "A public document of a mixed discovery application warns once, naming only its restricting operations")
    void publicDocumentWarnsOnceNamingRestrictedOperations(Vertx vertx) {
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));

        assertAll(
                () -> mixedCatalogOneInstance(vertx), () -> mixedCatalogTwoInstances(vertx), () -> openCatalog(vertx));
    }

    /** Case (a): the mixed catalog, one instance. */
    private void mixedCatalogOneInstance(Vertx vertx) throws Exception {
        // Given: the mixed catalog's component, its public document enabled with configured info
        capture.clear();
        PublicRestrictionTestComponents.MixedCatalogComponent component =
                DaggerPublicRestrictionTestComponents_MixedCatalogComponent.factory()
                        .create(catalogConfig());
        StartupDeployments.Outcome outcome = null;
        try {
            // When: one HttpVerticle instance is deployed and the document is fetched
            outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            StartupDeployments.Outcome deployed = outcome;
            assertTrue(deployed.deployed(), () -> "one instance: deployment failed: " + deployed.failure());
            JsonObject document = fetchDocument(deployed, "one instance");
            List<String> warnings = restrictionWarnings();

            // Then: exactly one restriction warning, for the document at its mount as registered
            assertEquals(1, warnings.size(), () -> "one instance: restriction warnings: " + warnings);
            String warning = warnings.get(0);
            assertTrue(
                    warning.contains(MOUNT_FRAGMENT),
                    () -> "one instance: the warning does not name the mount " + MOUNT_FRAGMENT + ": " + warning);

            // Then: it lists exactly the three restricting operations, sorted by path then method
            assertEquals(RESTRICTED_ENTRIES, listedEntries(warning), () -> "one instance: listed entries: " + warning);

            // Then: it names neither the open operation, nor the role, nor the action
            for (String absent : ABSENT_FROM_WARNING) {
                assertFalse(
                        warning.contains(absent), () -> "one instance: the warning names " + absent + ": " + warning);
            }

            // Then: the document is served and lists all four operations
            assertOperations(MIXED_OPERATIONS, document, "one instance");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** Case (b): the mixed catalog, two instances of one component. */
    private void mixedCatalogTwoInstances(Vertx vertx) throws Exception {
        // Given: a fresh mixed catalog component
        capture.clear();
        PublicRestrictionTestComponents.MixedCatalogComponent component =
                DaggerPublicRestrictionTestComponents_MixedCatalogComponent.factory()
                        .create(catalogConfig());
        StartupDeployments.Outcome outcome = null;
        try {
            // When: its HttpVerticle is deployed as two instances and the document is fetched
            outcome = StartupDeployments.deploy(
                    vertx, component::httpVerticle, new DeploymentOptions().setInstances(INSTANCES));
            StartupDeployments.Outcome deployed = outcome;
            assertTrue(deployed.deployed(), () -> "two instances: deployment failed: " + deployed.failure());
            JsonObject document = fetchDocument(deployed, "two instances");
            List<String> warnings = restrictionWarnings();

            // Then: the warning is still logged exactly once, and the document lists all four operations
            assertEquals(1, warnings.size(), () -> "two instances: restriction warnings: " + warnings);
            assertOperations(MIXED_OPERATIONS, document, "two instances");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** Case (c): a catalog holding only the open resource. */
    private void openCatalog(Vertx vertx) throws Exception {
        // Given: the open catalog's component, its public document enabled with configured info
        capture.clear();
        PublicRestrictionTestComponents.OpenCatalogComponent component =
                DaggerPublicRestrictionTestComponents_OpenCatalogComponent.factory()
                        .create(catalogConfig());
        StartupDeployments.Outcome outcome = null;
        try {
            // When: one instance is deployed and the document is fetched
            outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            StartupDeployments.Outcome deployed = outcome;
            assertTrue(deployed.deployed(), () -> "open catalog: deployment failed: " + deployed.failure());
            JsonObject document = fetchDocument(deployed, "open catalog");
            List<String> warnings = restrictionWarnings();

            // Then: the document lists the open operation only, and no restriction warning is logged
            assertOperations(Map.of("/catalog", "listCatalog"), document, "open catalog");
            assertEquals(List.of(), warnings, "open catalog: restriction warnings");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // --- Helpers ---

    /** Returns a fresh loopback configuration with the {@code catalog} document's configured info. */
    private static JsonObject catalogConfig() {
        return DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), "catalog", "Catalog", "1.0");
    }

    /** Returns the captured restriction warnings: the document's WARN events marked as such. */
    private List<String> restrictionWarnings() {
        return capture.warnings().stream()
                .filter(message -> message.startsWith(DOCUMENT_PATH) && message.contains(RESTRICT_FRAGMENT))
                .toList();
    }

    /** Returns the entries a restriction warning lists: its suffix after the list fragment, split. */
    private static List<String> listedEntries(String warning) {
        int at = warning.indexOf(LIST_FRAGMENT);
        assertTrue(at >= 0, () -> "the warning has no entry list after '" + LIST_FRAGMENT + "': " + warning);
        return List.of(warning.substring(at + LIST_FRAGMENT.length()).split(ENTRY_SEPARATOR, -1));
    }

    /** Asserts that the document publishes exactly the given paths, each with a GET of the given id. */
    private static void assertOperations(Map<String, String> expected, JsonObject document, String variant) {
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, () -> variant + ": the document has no paths: " + document.encode());
        assertEquals(expected.keySet(), Set.copyOf(paths.fieldNames()), () -> variant + ": the published paths");
        for (Map.Entry<String, String> operation : expected.entrySet()) {
            JsonObject get = paths.getJsonObject(operation.getKey()).getJsonObject("get");
            assertNotNull(get, () -> variant + ": " + operation.getKey() + " has no GET: " + paths.encode());
            assertEquals(
                    operation.getValue(),
                    get.getString("operationId"),
                    () -> variant + ": the operation id of GET " + operation.getKey());
        }
    }

    /** Fetches the document's JSON form, asserting a {@code 200} answer. */
    private JsonObject fetchDocument(StartupDeployments.Outcome deployed, String variant) throws Exception {
        assertNotNull(deployed.port(), () -> variant + ": no port was published");
        HttpResponse<Buffer> response = client.get(deployed.port(), "127.0.0.1", DOCUMENT_URL)
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(REQUEST_SECONDS, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), () -> variant + ": " + DOCUMENT_URL + ": status");
        assertNotNull(response.body(), () -> variant + ": " + DOCUMENT_URL + ": empty body");
        return response.bodyAsJsonObject();
    }
}
