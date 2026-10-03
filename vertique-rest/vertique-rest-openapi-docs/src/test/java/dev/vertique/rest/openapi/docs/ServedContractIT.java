// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.openapi.docs.ServedContractTestComponents.DocsProvisions;
import dev.vertique.rest.openapi.docs.ServedContractTestComponents.GatedProvisions;
import dev.vertique.rest.openapi.docs.ServedContractTestComponents.JwtProvisions;
import dev.vertique.rest.openapi.docs.fixture.RecordingPublicationHook;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractConfigs;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractFiles;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractMounts;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractTexts;
import dev.vertique.rest.openapi.docs.fixture.contract.WorkingDirectoryFile;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import dev.vertique.rest.openapi.docs.fixture.support.Cleanup;
import dev.vertique.rest.openapi.docs.fixture.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.OwnedWebClient;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Integration proof that an application whose effective {@code openapiPath} is its own serves that
 * contract, parsed and unchanged, as its document, while every other enabled document is generated.
 *
 * <p>The shared fixture declares three public documents under {@code web-validation} with the global
 * contract {@code contracts/global-openapi.json}: {@code partner} at {@code /api/partner}, whose
 * declaring interface names {@code contracts/partner-openapi.yaml}; {@code orders} at {@code
 * /api/orders}, whose contract {@code contracts/orders-openapi.json} is configured; and {@code catalog}
 * at {@code /api/catalog}, which names no contract and is generated. Further arrangements add two
 * applications sharing a contract location, a protected served document beside a protected generated
 * one, the contract-validation strategy, and an operation that restricts its callers.
 *
 * <p>Every failing contract fixture carries the marker {@value #MARKER} in a description or value; no
 * failure message, and no message of any exception in its cause chain, may contain it. Expected trees,
 * titles, identifiers, JSON Pointers, and message fragments are hand-written literals; a fixture
 * file's expected tree is the file itself, parsed by its extension.
 *
 * <p>Two log captures are attached before each test: one on the documentation module's warning logger
 * {@value #WARNINGS_LOGGER}, which carries the per-document source line and the warnings, and one on
 * the module's package logger {@value #DOCS_LOGGER} at {@code DEBUG}, which also carries the store's
 * lines and the loader's timing line. Both resolve each event's thread name while it is appended.
 *
 * <p>The class timeout is 60 seconds rather than 20: several methods deploy a sequence of graphs, up
 * to four in one method, each serving requests, and one method runs the gated worker-pool harness with
 * its own Vert.x instance and bounded waits.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ServedContractIT {

    // --- Loggers and markers ---

    /** The documentation module's package logger, the parent of every logger the module uses. */
    private static final String DOCS_LOGGER = "dev.vertique.rest.openapi.docs";

    /** The documentation module's warning logger, which also carries the per-document source line. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The marker every failing fixture carries and no message may echo. */
    private static final String MARKER = "zq7";

    // --- Hosts, bounds, and URLs ---

    /** The host every server binds and every request dials. */
    private static final String HOST = "127.0.0.1";

    /** The longest one request is awaited. */
    private static final long REQUEST_SECONDS = 10;

    private static final String PARTNER = "partner";
    private static final String ORDERS = "orders";
    private static final String CATALOG = "catalog";
    private static final String MANAGEMENT = "management";
    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";

    private static final String JSON_TYPE = "application/json";
    private static final String YAML_TYPE = "application/yaml";

    /** The orders resource of {@code partner}, which accepts {@code createOrder}. */
    private static final String PARTNER_ORDERS_URI = "/api/partner/orders";

    // --- Contract locations, as the oracles read them ---

    private static final String PARTNER_CONTRACT = "contracts/partner-openapi.yaml";
    private static final String PARTNER_STRATEGY_CONTRACT = "contracts/partner-strategy-openapi.yaml";
    private static final String PARTNER_OVERRIDE_CONTRACT = "contracts/partner-override-openapi.json";
    private static final String ORDERS_CONTRACT = "contracts/orders-openapi.json";
    private static final String GLOBAL_CONTRACT = "contracts/global-openapi.json";
    private static final String PARTNER_WITH_HIDDEN_CONTRACT = "contracts/partner-with-hidden.yaml";
    private static final String PARTNER_RESTRICTED_CONTRACT = "contracts/partner-restricted-openapi.yaml";
    private static final String ALPHA_CATALOG_CONTRACT = "contracts/alpha-catalog-openapi.json";
    private static final String BETA_ADMIN_USERS_CONTRACT = "contracts/beta-admin-users-openapi.json";
    private static final String SHADOWED_CONTRACT = "contracts/shadowed-openapi.json";
    private static final String LATE_CONTRACT = "contracts/late-openapi.json";
    private static final String PARTNER_TXT_CONTRACT = "contracts/partner-openapi.txt";

    // --- Message fragments of the contract checks ---

    /** How a contract check names the application; compared ignoring letter case. */
    private static final String APPLICATION_NAMED = "application '%s'";

    /** An id the contract describes that the mount does not route. */
    private static final String NOT_ROUTED = "is not routed by the mount";

    /** A routed operation that is not hidden and that the contract does not describe. */
    private static final String NOT_DESCRIBED = "is not described";

    /** A {@code $ref} that does not start with {@code #/}. */
    private static final String NOT_LOCAL = "is not a local reference";

    /** A route operation whose method or path differs from its routed twin's. */
    private static final String NOT_BOUND = "is not bound to its routed operation";

    /** A webhook or callback operation whose id is routed. */
    private static final String REUSES_ROUTED_ID = "reuses a routed operation id";

    /** A described parameter or form field the routed operation hides. */
    private static final String HIDDEN_INPUT = "describes a hidden input";

    // --- Message fragments of loading and metadata refusals ---

    /** A {@code .json} contract the JSON parser rejects. */
    private static final String NOT_JSON = "is not valid JSON";

    /** A {@code .yaml} contract the YAML parser rejects. */
    private static final String NOT_YAML = "is not valid YAML";

    /** A contract location neither the working directory nor the classpath holds. */
    private static final String UNREADABLE = "cannot be read";

    /** The supported contract extensions, as the extension refusal lists them. */
    private static final String SUPPORTED_EXTENSIONS = ".json, .yaml, or .yml";

    /**
     * How the extension refusal names a configured location with a line feed: the line feed as a
     * backslash, the letter u, and the four uppercase hexadecimal digits 000A, nothing truncated.
     */
    private static final String LINE_FEED_LOCATION_SHOWN = "'contracts/partner\\u000Azz-openapi.txt'";

    /** The file name of an absolute contract location with a line feed between its two words. */
    private static final String LINE_FEED_FILE_NAME = "absolute" + (char) 10 + "openapi.json";

    /** How a source line names {@link #LINE_FEED_FILE_NAME}: the line feed shown as in a refusal. */
    private static final String LINE_FEED_FILE_NAME_SHOWN = "absolute\\u000Aopenapi.json";

    /** A line feed, which no message that names a location may carry raw. */
    private static final char LINE_FEED = (char) 10;

    /** The configured {@code info} a served document refuses. */
    private static final String CONFIGURED_INFO_PATH = "apidocs.documents.partner.info";

    /** The configured {@code serverUrl} a served document refuses. */
    private static final String CONFIGURED_SERVER_URL_PATH = "apidocs.documents.partner.serverUrl";

    /** The annotation a served document's declaring interface may not carry. */
    private static final String OPENAPI_DEFINITION = "@OpenAPIDefinition";

    /** The declaring interface that carries {@code @OpenAPIDefinition} beside its own contract. */
    private static final String ANNOTATED_INFO_BINARY =
            "dev.vertique.rest.openapi.docs.fixture.contract.AnnotatedInfoPartnerApi";

    // --- Message fragments of the composition validator ---

    /** The aggregate message of every composition-validator violation. */
    private static final String INVALID_MOUNT_CONFIGURATION = "Invalid mount configuration";

    /** Two served documents whose contract locations normalize to one. */
    private static final String ONE_CONTRACT_LOCATION = "one contract location";

    /** The setting of {@code alpha}'s unparseable contract location. */
    private static final String ALPHA_OPENAPI_PATH_SETTING = "jaxrs.applications.alpha.openapiPath";

    /** The text {@code java.nio.file.InvalidPathException} carries for a NUL character. */
    private static final String NUL_EXCEPTION_TEXT = "Nul character";

    // --- Log line fragments ---

    /** The statement of the warning about a contract whose {@code servers} differ from the mount. */
    private static final String NOT_REWRITTEN = "is not rewritten";

    /** The statement of the warning about a working-directory file shadowing a classpath resource. */
    private static final String SHADOWS = "shadows";

    /** The source line of a generated document. */
    private static final String GENERATED_SOURCE = "is generated";

    /** The source line of a served document, followed by the resolved location. */
    private static final String SERVED_FROM = "is served from ";

    /** The loader's timing line and the store's source label of a served document. */
    private static final String SERVED_CONTRACT = "served contract";

    /** The keyword of the store's line, which the source line never carries. */
    private static final String STORED = "stored";

    /** The keyword of the store's snapshot comparison line, compared ignoring letter case. */
    private static final String COMPARISON = "compar";

    /** The part of a Vert.x classpath-cache copy's path, which a source line never names. */
    private static final String VERTX_CACHE = "vertx-cache-";

    // --- The public restriction warning, as the generated document's warning formats it ---

    private static final String RESTRICT_FRAGMENT = "restrict callers";
    private static final String LIST_FRAGMENT = "served without authentication: ";
    private static final String ENTRY_SEPARATOR = ", ";
    private static final String PARTNER_MOUNT_FRAGMENT = "at mount '/api/partner/*'";
    private static final List<String> RESTRICTED_ENTRIES = List.of("DELETE /orders/{id} (cancelOrder)");

    // --- The shared-contract refusal, as the contract-validation refusal proof names it ---

    private static final String CATALOG_BINARY = "dev.vertique.rest.openapi.docs.fixture.contract.CatalogApi";
    private static final String CATALOG_MOUNT_PATH = "/api/catalog/*";
    private static final String OPENAPI_CONTRACT = "openapi-contract";
    private static final String CUSTOM_CONTRACT_TEST = "custom-contract-test";
    private static final String SERVE_FRAGMENT = "serve";
    private static final String ACCESS_CHECK_FRAGMENT = "access check at least as strict as the most restricted mount";

    // --- The gated harness ---

    /** The name of the gated test's one-thread worker pool, shared by the gate and the deployment. */
    private static final String GATED_POOL = "served-contract-gated-pool";

    /** The mount path whose {@code mountBuilt} calls the gated test waits for. */
    private static final String PARTNER_MOUNT_PATH = "/api/partner/*";

    private static final int COMPOSITIONS = 2;
    private static final int REQUESTS_PER_FORM = 6;
    private static final Duration GATE_START_BOUND = Duration.ofSeconds(2);
    private static final Duration BOTH_CALLS_BOUND = Duration.ofSeconds(8);
    private static final Duration GATED_DEPLOY_BOUND = Duration.ofSeconds(8);
    private static final Duration GATED_CLOSE_BOUND = Duration.ofSeconds(3);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final AtomicLong REQUESTS = new AtomicLong();

    private Logger docsLogger;
    private Level previousDocsLevel;
    private CapturingAppender docs;

    private Logger warningsLogger;
    private Level previousWarningsLevel;
    private CapturingAppender warnings;

    /** Sends the requests of the extension-driven tests; closed before the Vert.x instance. */
    private WebClient client;

    @BeforeEach
    void captureLogsAndCreateClient(Vertx vertx) {
        docsLogger = (Logger) LoggerFactory.getLogger(DOCS_LOGGER);
        previousDocsLevel = docsLogger.getLevel();
        docsLogger.setLevel(Level.DEBUG);
        docs = new CapturingAppender();
        docs.start();
        docsLogger.addAppender(docs);

        warningsLogger = (Logger) LoggerFactory.getLogger(WARNINGS_LOGGER);
        previousWarningsLevel = warningsLogger.getLevel();
        warningsLogger.setLevel(Level.DEBUG);
        warnings = new CapturingAppender();
        warnings.start();
        warningsLogger.addAppender(warnings);

        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost(HOST));
    }

    @AfterEach
    void closeClientAndReleaseLogs(Vertx vertx) {
        try {
            if (client != null) {
                client.close();
                client = null;
            }
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).remove(StartupDeployments.PORT_KEY);
        } finally {
            warningsLogger.detachAppender(warnings);
            warnings.stop();
            warningsLogger.setLevel(previousWarningsLevel);
            docsLogger.detachAppender(docs);
            docs.stop();
            docsLogger.setLevel(previousDocsLevel);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // An application's own contract is its document; the others are generated
    // ---------------------------------------------------------------------------------------------

    /**
     * One document of one variant and its expected source: a fixture location served from the
     * classpath, or {@code null} for a generated document.
     */
    private record SourceRow(String document, String servedLocation) {}

    @Test
    @DisplayName("An application's own contract is served as its document, and a document without one is generated")
    void ownContractIsServedAndOtherDocumentsAreGenerated(Vertx vertx) throws Exception {
        // (i) Given: the shared fixture; partner's contract comes from its declaring interface,
        // orders's from configuration, and catalog has none.
        List<SourceRow> annotated = List.of(
                new SourceRow(PARTNER, PARTNER_CONTRACT),
                new SourceRow(ORDERS, ORDERS_CONTRACT),
                new SourceRow(CATALOG, null));
        // (ii) Given: the same with partner's contract location configured, which wins over the annotation.
        List<SourceRow> configured = List.of(
                new SourceRow(PARTNER, PARTNER_OVERRIDE_CONTRACT),
                new SourceRow(ORDERS, ORDERS_CONTRACT),
                new SourceRow(CATALOG, null));

        assertSources(vertx, "(i) the shared fixture", ContractConfigs.shared(), annotated);
        assertSources(
                vertx,
                "(ii) partner's contract location configured",
                ContractConfigs.sharedWithPartnerContract(ContractFiles.PARTNER_OVERRIDE),
                configured);
    }

    private void assertSources(Vertx vertx, String variant, JsonObject config, List<SourceRow> rows) throws Exception {
        warnings.clear();
        docs.clear();
        DocsProvisions component = sharedComponent(vertx, config);

        // When: the composition is deployed and the JSON form of each document is requested
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertDeployed(variant, outcome);
            List<Executable> checks = new ArrayList<>();
            for (SourceRow row : rows) {
                String label = variant + " | " + row.document();
                Exchange served = send(outcome.port(), HttpMethod.GET, jsonUrl(row.document()));
                JsonNode tree = parseJson(label, served);
                if (row.servedLocation() != null) {
                    // Then: a served document is the parsed contract file
                    JsonNode expected = fixtureTree(row.servedLocation());
                    checks.add(() -> assertEquals(expected, tree, label + ": the served tree is the contract file's"));
                    checks.add(() -> assertServedFromClasspath(label, row.document(), row.servedLocation()));
                } else {
                    // Then: the generated document is built from the running code, not from a contract
                    checks.add(() -> assertGeneratedCatalog(label, tree));
                    checks.add(() -> assertGeneratedSource(label, row.document()));
                }
            }
            checks.add(() -> assertNoStoredSourceLine(variant));
            assertAll(variant, checks.stream());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    private static void assertGeneratedCatalog(String label, JsonNode tree) throws IOException {
        assertEquals("3.1.1", tree.path("openapi").asText(), label + ": openapi");
        assertTrue(tree.has("jsonSchemaDialect"), label + ": a generated document names its JSON Schema dialect");
        assertTrue(
                tree.path("x-vertique-validation").has("patternDialect"),
                label + ": a generated document carries x-vertique-validation.patternDialect");
        assertEquals("Catalog", tree.path("info").path("title").asText(), label + ": info.title");
        assertEquals("1.0", tree.path("info").path("version").asText(), label + ": info.version");
        assertEquals(List.of("/entries"), memberNames(tree.path("paths")), label + ": only catalog's own paths");
        assertEquals(
                "listEntries",
                tree.path("paths")
                        .path("/entries")
                        .path("get")
                        .path("operationId")
                        .asText(),
                label + ": the operation id of GET /entries");
        assertNotEquals(
                fixtureTree(GLOBAL_CONTRACT), tree, label + ": the generated document is not the global contract");
    }

    // ---------------------------------------------------------------------------------------------
    // Both forms render the one parsed tree unchanged
    // ---------------------------------------------------------------------------------------------

    /** A served document, the location of its contract, and the contract's root members in order. */
    private record RenderRow(String document, String location, List<String> rootMembers) {}

    @Test
    @DisplayName("Both forms of a served document render the parsed contract unchanged, with strong entity tags")
    void bothFormsRenderTheParsedTreeUnchanged(Vertx vertx) throws Exception {
        // Given: the shared fixture; listOrders also carries an @Operation summary its contract does not state
        List<RenderRow> rows = List.of(
                new RenderRow(
                        PARTNER,
                        PARTNER_CONTRACT,
                        List.of("openapi", "info", "servers", "tags", "x-partner-extension", "paths", "components")),
                new RenderRow(ORDERS, ORDERS_CONTRACT, List.of("openapi", "info", "servers", "paths")));
        DocsProvisions component = sharedComponent(vertx, ContractConfigs.shared());

        // When: each form is requested with GET twice, with HEAD, and with If-None-Match
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertDeployed("the shared fixture", outcome);
            int port = outcome.port();
            List<Executable> checks = new ArrayList<>();
            for (RenderRow row : rows) {
                JsonNode source = fixtureTree(row.location());
                for (boolean yaml : List.of(false, true)) {
                    String url = yaml ? yamlUrl(row.document()) : jsonUrl(row.document());
                    String type = yaml ? YAML_TYPE : JSON_TYPE;
                    Exchange first = send(port, HttpMethod.GET, url);
                    Exchange second = send(port, HttpMethod.GET, url);
                    Exchange head = send(port, HttpMethod.HEAD, url);
                    String tag = first.header("ETag");
                    Exchange conditional = send(port, HttpMethod.GET, url, null, ifNoneMatch(tag));
                    checks.add(() -> assertRendered(url, type, yaml, source, row, first, second, head, conditional));
                }
            }
            assertAll("both forms of the served documents", checks.stream());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    private static void assertRendered(
            String url,
            String type,
            boolean yaml,
            JsonNode source,
            RenderRow row,
            Exchange first,
            Exchange second,
            Exchange head,
            Exchange conditional)
            throws IOException {
        // Then: the form is the parsed contract, members in the source's order, nothing added
        assertEquals(200, first.status(), url + ": status");
        assertTrue(
                first.contentType() != null && first.contentType().startsWith(type),
                url + ": Content-Type " + first.contentType());
        JsonNode tree = (yaml ? YAML : JSON).readTree(first.body().getBytes());
        assertEquals(source, tree, url + ": the form's tree is the contract file's");
        assertEquals(row.rootMembers(), memberNames(tree), url + ": the root members in the source's order");
        assertFalse(hasMemberNamed(tree, "x-vertique-validation"), url + ": no x-vertique-validation member anywhere");
        assertEquals(source.get("servers"), tree.get("servers"), url + ": servers");
        assertEquals(source.get("info"), tree.get("info"), url + ": info");
        if (PARTNER.equals(row.document())) {
            assertEquals(
                    "/api/partner", tree.path("servers").path(0).path("url").asText(), url + ": servers[0].url");
            assertEquals("Partner orders", tree.path("info").path("title").asText(), url + ": info.title");
            assertEquals(source.get("tags"), tree.get("tags"), url + ": tags");
            assertEquals(
                    source.get("x-partner-extension"), tree.get("x-partner-extension"), url + ": x-partner-extension");
            assertFalse(
                    tree.path("paths").path("/orders").path("get").has("summary"),
                    url + ": listOrders has no summary, as its contract states none");
        } else {
            assertEquals("/api/orders", tree.path("servers").path(0).path("url").asText(), url + ": servers[0].url");
            assertEquals("Orders", tree.path("info").path("title").asText(), url + ": info.title");
        }

        // Then: a strong entity tag, equal bytes on both reads, HEAD without a body, and a bodiless 304
        String tag = first.header("ETag");
        assertNotNull(tag, url + ": an entity tag");
        assertFalse(tag.startsWith("W/"), url + ": the entity tag is strong: " + tag);
        assertEquals(200, second.status(), url + ": the second read's status");
        assertArrayEquals(first.body().getBytes(), second.body().getBytes(), url + ": equal bytes on both reads");
        assertEquals(tag, second.header("ETag"), url + ": the second read's entity tag");
        assertEquals(200, head.status(), url + ": HEAD status");
        assertEquals(tag, head.header("ETag"), url + ": HEAD entity tag");
        assertEquals(0, head.body().length(), url + ": HEAD has no body");
        assertEquals(304, conditional.status(), url + ": a matching If-None-Match");
        assertEquals(0, conditional.body().length(), url + ": the 304 has no body");
    }

    // ---------------------------------------------------------------------------------------------
    // Invalid contracts and metadata overrides fail startup naming the application
    // ---------------------------------------------------------------------------------------------

    /**
     * One deployment of a startup table and what it must show.
     *
     * @param label     the row, as the report names it
     * @param component creates the component from the Vert.x instance and the configuration
     * @param config    creates the row's configuration
     * @param check     checks the row's outcome
     */
    record StartupRow(
            String label,
            BiFunction<Vertx, JsonObject, DocsProvisions> component,
            Supplier<JsonObject> config,
            RowCheck check) {

        @Override
        public String toString() {
            return label;
        }
    }

    /** What a row of a startup table checks. */
    @FunctionalInterface
    interface RowCheck {

        /**
         * Checks the row's outcome.
         *
         * @param row the deployed row
         * @throws Exception when a request fails
         */
        void verify(Deployed row) throws Exception;
    }

    /**
     * A deployed row of a startup table.
     *
     * @param label     the row's label
     * @param outcome   the deployment's outcome
     * @param component the deployed component
     * @param test      the running test, for its client and log captures
     */
    record Deployed(String label, Outcome outcome, DocsProvisions component, ServedContractIT test) {}

    static Stream<StartupRow> invalidContractsAndMetadataOverrides() {
        return Stream.of(
                partnerRefused(
                        "the contract describes deleteOrder, which the mount does not route",
                        ContractFiles.PARTNER_EXTRA_OPERATION,
                        "deleteOrder",
                        NOT_ROUTED),
                partnerRefused(
                        "the contract omits createOrder",
                        ContractFiles.PARTNER_MISSING_OPERATION,
                        "createOrder",
                        NOT_DESCRIBED),
                partnerRefused(
                        "the contract references another file",
                        ContractFiles.PARTNER_EXTERNAL_REF,
                        "/paths/~1orders/post/requestBody/content/application~1json/schema/$ref",
                        NOT_LOCAL),
                partnerRefused("the contract is not valid JSON", ContractFiles.PARTNER_MALFORMED_JSON, NOT_JSON),
                partnerRefused("the contract is not valid YAML", ContractFiles.PARTNER_MALFORMED_YAML, NOT_YAML),
                partnerRefused("the contract location does not exist", ContractFiles.ABSENT, UNREADABLE),
                partnerRefused(
                        "the contract has an unsupported extension",
                        ContractFiles.PARTNER_TXT,
                        PARTNER_TXT_CONTRACT,
                        SUPPORTED_EXTENSIONS),
                new StartupRow(
                        "apidocs.documents.partner.info is configured",
                        ServedContractIT::sharedComponent,
                        () -> ContractConfigs.withPartnerInfo(ContractConfigs.shared()),
                        withoutPartnerNotices(
                                row -> assertStartupFailure(row, PARTNER, PARTNER, null, CONFIGURED_INFO_PATH))),
                new StartupRow(
                        "the declaring interface also carries @OpenAPIDefinition",
                        ServedContractIT::annotatedInfoComponent,
                        ContractConfigs::shared,
                        withoutPartnerNotices(row -> assertStartupFailure(
                                row, PARTNER, PARTNER, null, OPENAPI_DEFINITION, ANNOTATED_INFO_BINARY))),
                new StartupRow(
                        "apidocs.documents.partner.serverUrl is configured",
                        ServedContractIT::sharedComponent,
                        () -> ContractConfigs.withPartnerServerUrl(ContractConfigs.shared()),
                        withoutPartnerNotices(
                                row -> assertStartupFailure(row, PARTNER, PARTNER, null, CONFIGURED_SERVER_URL_PATH))),
                partnerRefused(
                        "a webhook operation reuses the routed id getOrderInternal",
                        ContractFiles.PARTNER_WEBHOOK_REUSE,
                        "/webhooks/orderLookup/post",
                        REUSES_ROUTED_ID),
                partnerRefused(
                        "the contract describes listOrders's hidden query parameter debug",
                        ContractFiles.PARTNER_HIDDEN_PARAM,
                        "listOrders",
                        "/paths/~1orders/get/parameters/1",
                        HIDDEN_INPUT),
                new StartupRow(
                        "control: the contract also describes the hidden operation getOrderInternal",
                        ServedContractIT::sharedComponent,
                        () -> ContractConfigs.sharedWithPartnerContract(ContractFiles.PARTNER_WITH_HIDDEN),
                        ServedContractIT::assertServesTheHiddenOperation),
                new StartupRow(
                        "control: the contract describing the unrouted deleteOrder, with partner's document disabled",
                        ServedContractIT::sharedComponent,
                        () -> ContractConfigs.withDocumentEnabled(
                                ContractConfigs.sharedWithPartnerContract(ContractFiles.PARTNER_EXTRA_OPERATION),
                                PARTNER,
                                false),
                        ServedContractIT::assertDisabledDocumentReadsNothing),
                partnerRefused(
                        "the YAML contract holds a second document after a valid first one",
                        ContractFiles.PARTNER_MULTI_DOCUMENT,
                        NOT_YAML),
                new StartupRow(
                        "the configured contract location holds a line feed and an unsupported extension",
                        ServedContractIT::sharedComponent,
                        () -> ContractConfigs.sharedWithPartnerContract(ContractFiles.PARTNER_LINE_FEED_TXT),
                        withoutPartnerNotices(ServedContractIT::assertLineFeedLocationShownEscaped)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidContractsAndMetadataOverrides")
    @DisplayName("An invalid contract or a metadata override fails startup naming the application, never the content")
    void invalidContractOrMetadataOverrideFailsStartup(StartupRow row, Vertx vertx) throws Exception {
        runStartupRow(row, vertx);
    }

    /**
     * A {@code partner} row whose contract the shared fixture's check or loader refuses, logging no
     * notice or warning for {@code partner}.
     */
    private static StartupRow partnerRefused(String label, String location, String... fragments) {
        return new StartupRow(
                label,
                ServedContractIT::sharedComponent,
                () -> ContractConfigs.sharedWithPartnerContract(location),
                withoutPartnerNotices(row -> assertStartupFailure(row, PARTNER, PARTNER, null, fragments)));
    }

    /**
     * Runs a refused row's check, then asserts that the warning logger captured no {@code INFO} or
     * {@code WARN} line for {@code partner}: a document that fails to load or to provision logs no
     * source notice and none of its warnings.
     */
    private static RowCheck withoutPartnerNotices(RowCheck check) {
        return row -> {
            check.verify(row);
            List<String> notices = row.test().noticeLines(PARTNER);
            assertEquals(
                    List.of(),
                    notices,
                    () -> row.label() + ": INFO or WARN lines for apidocs.documents.partner: " + notices);
        };
    }

    /**
     * Asserts the extension refusal of a configured location with a line feed: it names the location
     * with the line feed escaped, carries no raw line feed, and no message in the cause chain carries
     * the raw location.
     */
    private static void assertLineFeedLocationShownEscaped(Deployed row) {
        Throwable refusal =
                assertStartupFailure(row, PARTNER, PARTNER, null, LINE_FEED_LOCATION_SHOWN, SUPPORTED_EXTENSIONS);
        String message = refusal.getMessage();
        List<Throwable> chain = causeChain(row.outcome().failure());
        assertAll(
                row.label() + ": the refusal: " + message,
                () -> assertTrue(message.indexOf(LINE_FEED) < 0, "the refusal carries no raw line feed"),
                () -> assertTrue(
                        chain.stream().noneMatch(cause -> String.valueOf(cause.getMessage())
                                .contains(ContractFiles.PARTNER_LINE_FEED_TXT)),
                        "no message in the cause chain carries the raw location"));
    }

    private static void assertServesTheHiddenOperation(Deployed row) throws Exception {
        assertDeployed(row.label(), row.outcome());
        JsonNode tree = parseJson(row.label(), row.test().send(row.outcome().port(), HttpMethod.GET, jsonUrl(PARTNER)));
        assertAll(
                row.label(),
                () -> assertEquals(fixtureTree(PARTNER_WITH_HIDDEN_CONTRACT), tree, "the served tree is the file's"),
                () -> assertEquals(
                        "getOrderInternal",
                        tree.path("paths")
                                .path("/orders/{id}")
                                .path("get")
                                .path("operationId")
                                .asText(),
                        "the hidden operation is described as the file describes it"));
    }

    private static void assertDisabledDocumentReadsNothing(Deployed row) throws Exception {
        assertDeployed(row.label(), row.outcome());
        Exchange document = row.test().send(row.outcome().port(), HttpMethod.GET, jsonUrl(PARTNER));
        ServedContractIT test = row.test();
        assertAll(
                row.label(),
                () -> assertEquals(404, document.status(), "the docs mount does not answer the document URL"),
                () -> assertFalse(
                        PublicationAccess.names(row.component().documentStore()).contains(PARTNER),
                        "no entry for partner"),
                () -> assertEquals(List.of(), test.sourceLines(PARTNER), "no source line for partner"),
                () -> assertEquals(List.of(), test.loaderLines(PARTNER), "no contract was loaded for partner"));
    }

    // ---------------------------------------------------------------------------------------------
    // Shared contract locations and borrowed routes fail startup
    // ---------------------------------------------------------------------------------------------

    static Stream<StartupRow> sharedLocationsAndBorrowedRoutes() {
        return Stream.of(
                new StartupRow(
                        "(i) alpha and beta name one contract under web-validation",
                        ServedContractIT::alphaBetaComponent,
                        () -> ContractConfigs.alphaBeta(ContractFiles.SHARED, ContractFiles.SHARED),
                        row -> assertOneContractLocation(row, "shared-openapi")),
                new StartupRow(
                        "(ii) alpha and beta name one contract under openapi-contract",
                        ServedContractIT::alphaBetaOpenApiContractComponent,
                        () -> ContractConfigs.alphaBetaUnderOpenApiContract(ContractFiles.SHARED, ContractFiles.SHARED),
                        row -> assertOneContractLocation(row, "shared-openapi")),
                new StartupRow(
                        "(iii) alpha's contract describes beta's route under alpha's routed id",
                        ServedContractIT::alphaCatalogBetaAdminComponent,
                        () -> ContractConfigs.alphaBeta(
                                ContractFiles.ALPHA_BORROWS_BETA, ContractFiles.BETA_ADMIN_USERS),
                        row -> assertStartupFailure(row, ALPHA, ALPHA, null, "/paths/~1admin~1users/get", NOT_BOUND)),
                new StartupRow(
                        "(iv) control: each application's contract describes only its own route",
                        ServedContractIT::alphaCatalogBetaAdminComponent,
                        () -> ContractConfigs.alphaBeta(ContractFiles.ALPHA_CATALOG, ContractFiles.BETA_ADMIN_USERS),
                        ServedContractIT::assertBothContractsServed),
                new StartupRow(
                        "(v) one contract file under two spellings",
                        ServedContractIT::alphaBetaComponent,
                        () -> ContractConfigs.alphaBeta(ContractFiles.MGMT_DOT_SLASH, ContractFiles.MGMT),
                        row -> assertOneContractLocation(row, "mgmt.yaml")),
                new StartupRow(
                        "(vi) alpha's configured contract location contains a NUL character",
                        ServedContractIT::alphaBetaComponent,
                        () -> ContractConfigs.alphaBeta(ContractFiles.NUL_LOCATION, ContractFiles.BETA),
                        ServedContractIT::assertUnparseableLocation));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedLocationsAndBorrowedRoutes")
    @DisplayName("Two served documents with one contract location, or a borrowed route, fail startup")
    void sharedLocationsAndBorrowedRoutesFailStartup(StartupRow row, Vertx vertx) throws Exception {
        runStartupRow(row, vertx);
    }

    /**
     * Asserts the composition validator's refusal of two served documents with one contract location:
     * it names {@code alpha} and {@code beta}, sorted, and never the location.
     */
    private static void assertOneContractLocation(Deployed row, String locationPart) {
        Throwable violation = assertStartupFailure(
                row, null, null, IllegalStateException.class, INVALID_MOUNT_CONFIGURATION, ONE_CONTRACT_LOCATION);
        String message = violation.getMessage();
        int alpha = quotedIndex(message, ALPHA);
        int beta = quotedIndex(message, BETA);
        assertAll(
                row.label() + ": the violation: " + message,
                () -> assertTrue(alpha >= 0, "names 'alpha'"),
                () -> assertTrue(beta >= 0, "names 'beta'"),
                () -> assertTrue(alpha < beta, "names the applications sorted"),
                () -> assertFalse(message.contains(locationPart), "does not name the contract location"),
                () -> assertFalse(message.contains("contracts/"), "does not name any contract path"));
    }

    /**
     * Asserts the composition validator's refusal of an unparseable contract location: it names the
     * application and the setting, and neither the value nor the parser's exception.
     */
    private static void assertUnparseableLocation(Deployed row) {
        Throwable violation = assertStartupFailure(
                row, null, null, IllegalStateException.class, INVALID_MOUNT_CONFIGURATION, ALPHA_OPENAPI_PATH_SETTING);
        String message = violation.getMessage();
        List<Throwable> chain = causeChain(row.outcome().failure());
        assertAll(
                row.label() + ": the violation: " + message,
                () -> assertTrue(quotedIndex(message, ALPHA) >= 0, "names 'alpha'"),
                () -> assertTrue(
                        chain.stream()
                                .noneMatch(cause ->
                                        String.valueOf(cause.getMessage()).indexOf('\0') >= 0),
                        "no message in the cause chain carries the NUL character"),
                () -> assertTrue(
                        chain.stream().noneMatch(cause -> String.valueOf(cause.getMessage())
                                .contains("-alpha")),
                        "no message in the cause chain carries a fragment of the configured value"),
                () -> assertTrue(
                        chain.stream().noneMatch(cause -> String.valueOf(cause.getMessage())
                                .contains(NUL_EXCEPTION_TEXT)),
                        "no message in the cause chain carries the path parser's text"),
                () -> assertTrue(
                        chain.stream().noneMatch(cause -> cause instanceof InvalidPathException),
                        "no InvalidPathException in the cause chain: " + chain));
    }

    private static void assertBothContractsServed(Deployed row) throws Exception {
        assertDeployed(row.label(), row.outcome());
        int port = row.outcome().port();
        JsonNode alpha = parseJson(row.label(), row.test().send(port, HttpMethod.GET, jsonUrl(ALPHA)));
        JsonNode beta = parseJson(row.label(), row.test().send(port, HttpMethod.GET, jsonUrl(BETA)));
        assertAll(
                row.label(),
                () -> assertEquals(fixtureTree(ALPHA_CATALOG_CONTRACT), alpha, "alpha serves its own contract"),
                () -> assertEquals(fixtureTree(BETA_ADMIN_USERS_CONTRACT), beta, "beta serves its own contract"));
    }

    // ---------------------------------------------------------------------------------------------
    // A servers mismatch logs one warning and the contract is served unchanged
    // ---------------------------------------------------------------------------------------------

    /**
     * One {@code servers} arrangement.
     *
     * @param label           the row, as the report names it
     * @param location        partner's configured contract location
     * @param expectedServers the served {@code servers} member as JSON, or {@code null} when absent
     * @param expectedWarnings the number of {@code servers} warnings for {@code partner}
     */
    private record ServersRow(String label, String location, String expectedServers, int expectedWarnings) {}

    @Test
    @DisplayName("A contract whose servers differ from the mount logs one warning and is served unchanged")
    void serversMismatchLogsOneWarningAndServesUnchanged(Vertx vertx) throws Exception {
        List<ServersRow> rows = List.of(
                new ServersRow("no servers member", ContractFiles.PARTNER_NO_SERVERS, null, 1),
                new ServersRow(
                        "an absolute servers[0].url",
                        ContractFiles.PARTNER_ABSOLUTE_SERVER,
                        "[{\"url\":\"https://partner.example.com/api/partner\"}]",
                        1),
                new ServersRow("an empty servers array", ContractFiles.PARTNER_EMPTY_SERVERS, "[]", 1),
                new ServersRow(
                        "control: servers[0].url is the mount path",
                        ContractFiles.PARTNER,
                        "[{\"url\":\"/api/partner\"}]",
                        0));
        List<Executable> checks = new ArrayList<>();
        for (ServersRow row : rows) {
            // Given: partner served from the row's contract, deployed as two instances of one component
            warnings.clear();
            DocsProvisions component =
                    sharedComponent(vertx, ContractConfigs.sharedWithPartnerContract(row.location()));
            Outcome outcome = StartupDeployments.deploy(
                    vertx, component::httpVerticle, new DeploymentOptions().setInstances(COMPOSITIONS));
            try {
                assertDeployed(row.label(), outcome);

                // When: partner's JSON form is requested
                JsonNode tree = parseJson(row.label(), send(outcome.port(), HttpMethod.GET, jsonUrl(PARTNER)));
                List<String> partnerWarnings = serversWarnings(PARTNER);
                List<String> otherWarnings = new ArrayList<>(serversWarnings(ORDERS));
                otherWarnings.addAll(serversWarnings(CATALOG));
                JsonNode expectedServers = row.expectedServers() == null ? null : JSON.readTree(row.expectedServers());

                // Then: the row's warning count, each naming partner, and servers exactly as the file states
                checks.add(() -> assertEquals(
                        row.expectedWarnings(),
                        partnerWarnings.size(),
                        row.label() + ": servers warnings for partner: " + partnerWarnings));
                for (String warning : partnerWarnings) {
                    checks.add(() -> assertTrue(
                            namesApplication(warning, PARTNER),
                            row.label() + ": the warning names partner: " + warning));
                }
                checks.add(() -> assertEquals(
                        List.of(), otherWarnings, row.label() + ": orders and catalog log no servers warning"));
                checks.add(() -> assertEquals(
                        expectedServers, tree.get("servers"), row.label() + ": servers exactly as the file states"));
                checks.add(() -> assertEquals(
                        fixtureTree(row.location()), tree, row.label() + ": the served tree is the file's"));
            } finally {
                StartupDeployments.undeploy(vertx, outcome);
            }
        }
        assertAll("servers warnings", checks.stream());
    }

    /** Returns the captured {@code servers} warnings of a document. */
    private List<String> serversWarnings(String document) {
        return warnings.lines().stream()
                .filter(line -> line.level() == Level.WARN)
                .map(LogLine::message)
                .filter(message -> startsWithDocument(message, document) && message.contains(NOT_REWRITTEN))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Racing compositions load the contract once per component, on a worker thread
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Racing compositions load the contract once, on the worker thread, and serve one body per form")
    void racingCompositionsLoadTheContractOnceOnAWorkerThread() throws Exception {
        // Given: a test-owned Vert.x instance with one event loop.
        Vertx gatedVertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        CountDownLatch gateStarted = new CountDownLatch(1);
        CountDownLatch gateRelease = new CountDownLatch(1);
        AtomicReference<String> gateThread = new AtomicReference<>();
        try (Cleanup cleanup = new Cleanup()) {
            // Teardown, last registered first: the client is closed, the deployment undone, the gate
            // released, and the instance closed, each attempted and each failure or timeout reported.
            cleanup.await("close the test-owned Vert.x instance", gatedVertx::close, GATED_CLOSE_BOUND);
            cleanup.step("release the gate", gateRelease::countDown);

            // Given: partner as the only documented application, with the recording hook beside the docs hook.
            docs.clear();
            GatedProvisions component = DaggerServedContractTestComponents_GatedPartnerComponent.factory()
                    .create(gatedVertx, ContractConfigs.partnerOnly());
            RecordingPublicationHook recordingHook = component.recordingHook();
            DocumentStore store = component.documentStore();

            // Given: the gate occupies the named pool's only thread, submitted from this JUnit thread.
            WorkerExecutor gateExecutor = gatedVertx.createSharedWorkerExecutor(GATED_POOL, 1);
            Future<Boolean> gate = gateExecutor.executeBlocking(() -> {
                gateThread.set(Thread.currentThread().getName());
                gateStarted.countDown();
                return gateRelease.await(
                        GATE_START_BOUND
                                .plus(BOTH_CALLS_BOUND)
                                .plus(GATED_DEPLOY_BOUND)
                                .toMillis(),
                        TimeUnit.MILLISECONDS);
            });
            assertTrue(
                    gateStarted.await(GATE_START_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                    "the gate task never started on the named pool");

            boolean bothCallsWhileGated;
            int loaderLinesWhileGated;
            boolean storedWhileGated;
            Future<String> deployment;
            try {
                // When: two instances deploy with the named one-thread pool, and both compositions
                // reach the hooks while the pool is gated.
                DeploymentOptions options = new DeploymentOptions()
                        .setInstances(COMPOSITIONS)
                        .setWorkerPoolName(GATED_POOL)
                        .setWorkerPoolSize(1);
                gatedVertx
                        .sharedData()
                        .getLocalMap(StartupDeployments.LOCAL_MAP)
                        .remove(StartupDeployments.PORT_KEY);
                deployment = Deployments.deploy(gatedVertx, component::httpVerticle, options);
                bothCallsWhileGated = recordingHook.awaitCalls(PARTNER_MOUNT_PATH, COMPOSITIONS, BOTH_CALLS_BOUND);
                loaderLinesWhileGated = loaderLines(PARTNER).size();
                storedWhileGated = store.lookup(PARTNER).isPresent();
            } finally {
                // When: the gate is released from this JUnit thread.
                gateRelease.countDown();
            }
            assertTrue(
                    bothCallsWhileGated,
                    () -> "startup did not reach both mountBuilt calls for " + PARTNER_MOUNT_PATH
                            + " while the named pool was gated; recorded calls: "
                            + recordingHook.calls(PARTNER_MOUNT_PATH));

            // When: the deployment completes; then the gate's own outcome is observed.
            String deploymentId = Futures.await(deployment, GATED_DEPLOY_BOUND);
            cleanup.await("undeploy the gated deployment", () -> gatedVertx.undeploy(deploymentId), GATED_CLOSE_BOUND);
            assertTrue(Futures.await(gate, GATED_DEPLOY_BOUND), "the gate was released by its bound, not by the test");
            List<LogLine> lines = docs.lines();
            String poolThread = gateThread.get();

            // Then: exactly one served-contract line for partner, logged on the pool's only thread.
            List<LogLine> loads = loaderLines(PARTNER);
            assertEquals(1, loads.size(), () -> "served-contract lines for partner: " + lines);
            assertEquals(
                    poolThread, loads.get(0).thread(), "the contract was not loaded on the named pool's only thread");

            // Then: exactly one stored line naming partner, and one comparison line.
            List<LogLine> stored = lines.stream()
                    .filter(line -> line.level() == Level.INFO)
                    .filter(line -> line.message().contains(STORED))
                    .filter(line -> line.message().contains("'" + PARTNER + "'")
                            && line.message().contains(PARTNER_MOUNT_PATH))
                    .toList();
            assertEquals(1, stored.size(), () -> "stored lines for partner: " + lines);
            assertTrue(
                    stored.get(0).message().contains(SERVED_CONTRACT),
                    () -> "the stored line names the served contract as the source: " + stored.get(0));
            List<LogLine> comparisons = lines.stream()
                    .filter(line -> line.level() == Level.DEBUG)
                    .filter(line -> line.message().toLowerCase(Locale.ROOT).contains(COMPARISON))
                    .toList();
            assertEquals(1, comparisons.size(), () -> "comparison lines: " + lines);

            // Then: nothing was loaded or stored while the named pool was gated.
            assertEquals(0, loaderLinesWhileGated, "a contract was loaded while the named pool was gated");
            assertFalse(storedWhileGated, "a document was stored while the named pool was gated");

            // When: both forms are requested over separate connections several times.
            Object port = gatedVertx
                    .sharedData()
                    .getLocalMap(StartupDeployments.LOCAL_MAP)
                    .get(StartupDeployments.PORT_KEY);
            assertNotNull(port, "the deployment published no http.port");
            // The client wraps a raw client so its close is awaited before the owned instance closes.
            OwnedWebClient owned = OwnedWebClient.create(
                    gatedVertx, new HttpClientOptions().setDefaultHost(HOST).setKeepAlive(false));
            cleanup.await("close the gated client", owned::close, GATED_CLOSE_BOUND);
            WebClient gatedClient = owned.client();
            for (String url : List.of(jsonUrl(PARTNER), yamlUrl(PARTNER))) {
                Set<String> bodies = new HashSet<>();
                Set<String> etags = new HashSet<>();
                for (int request = 0; request < REQUESTS_PER_FORM; request++) {
                    Exchange served = send(gatedClient, (Integer) port, HttpMethod.GET, url, null, Map.of(), null);
                    assertEquals(200, served.status(), () -> url + ": status");
                    bodies.add(served.body().toString());
                    etags.add(served.header("ETag"));
                }
                // Then: every response for a form has one body and one entity tag.
                assertEquals(1, bodies.size(), () -> url + ": distinct bodies over " + REQUESTS_PER_FORM + " requests");
                assertEquals(1, etags.size(), () -> url + ": distinct entity tags: " + etags);
                assertFalse(etags.contains(null), () -> url + ": a response without an entity tag");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The contract location resolves as the validation strategy resolves it
    // ---------------------------------------------------------------------------------------------

    /**
     * A relative contract location resolves to a working-directory file before the classpath resource,
     * an absolute one to its file, and under {@code openapi-contract} the strategy validates against the
     * same file the document is served from.
     *
     * <p>The shadowed contracts carry no {@code servers} member, because (d) also deploys them under the
     * contract-validation strategy, which accepts only absolute server URLs or none. Their documents
     * therefore also log the warning about {@code servers}; the warning checks count only the shadowing
     * warning.
     */
    @Test
    @DisplayName(
            "A relative contract location resolves to a working-directory file before the classpath, as the strategy resolves it")
    void contractLocationResolvesAsTheValidationStrategyDoes(Vertx vertx, @TempDir Path tempDir) throws Exception {
        String workingDirectoryPath =
                Path.of(SHADOWED_CONTRACT).toAbsolutePath().normalize().toString();

        // (a) Given: the shadowed location as a classpath resource and as a working-directory file
        try (WorkingDirectoryFile ignored =
                WorkingDirectoryFile.write(ContractFiles.SHADOWED, ContractTexts.WORKING_DIRECTORY_SHADOWED)) {
            Resolved resolved = deployAndReadPartner(
                    vertx,
                    "a working-directory file shadows the classpath resource",
                    sharedComponent(vertx, ContractConfigs.sharedWithPartnerContract(ContractFiles.SHADOWED)),
                    null);
            assertAll(
                    resolved.label(),
                    () -> assertEquals("working-directory", resolved.title(), "info.title"),
                    () -> assertServedFromFile(resolved, workingDirectoryPath),
                    () -> assertEquals(1, resolved.shadowWarnings().size(), "one shadowing warning: " + resolved),
                    () -> assertShadowWarning(resolved));
        }

        // (b) Given: the same relative location with the working-directory file absent
        assertFalse(Files.exists(Path.of(SHADOWED_CONTRACT)), "the working-directory file was removed");
        Resolved classpath = deployAndReadPartner(
                vertx,
                "only the classpath resource exists",
                sharedComponent(vertx, ContractConfigs.sharedWithPartnerContract(ContractFiles.SHADOWED)),
                null);
        assertAll(
                classpath.label(),
                () -> assertEquals("classpath", classpath.title(), "info.title"),
                () -> assertClasspathLocation(classpath.label(), classpath.sourceLines(), SHADOWED_CONTRACT),
                () -> assertEquals(List.of(), classpath.shadowWarnings(), "no shadowing warning"));

        // (c) Given: an absolute location in a temporary directory
        Path absoluteFile = tempDir.resolve("absolute-openapi.json");
        Files.writeString(absoluteFile, ContractTexts.ABSOLUTE);
        String absoluteLocation = absoluteFile.toString();
        String absolutePath =
                Path.of(absoluteLocation).toAbsolutePath().normalize().toString();
        Resolved absolute = deployAndReadPartner(
                vertx,
                "an absolute contract location",
                sharedComponent(vertx, ContractConfigs.sharedWithPartnerContract(absoluteLocation)),
                null);
        assertAll(
                absolute.label(),
                () -> assertEquals("absolute", absolute.title(), "info.title"),
                () -> assertServedFromFile(absolute, absolutePath),
                () -> assertEquals(List.of(), absolute.shadowWarnings(), "no shadowing warning"));

        // (d) Given: arrangement (a) under openapi-contract, catalog's document disabled
        try (WorkingDirectoryFile ignored =
                WorkingDirectoryFile.write(ContractFiles.SHADOWED, ContractTexts.WORKING_DIRECTORY_SHADOWED)) {
            JsonObject config = ContractConfigs.withDocumentEnabled(
                    ContractConfigs.withApplicationContract(
                            ContractConfigs.sharedUnderOpenApiContract(), PARTNER, ContractFiles.SHADOWED),
                    CATALOG,
                    false);
            Resolved strategy = deployAndReadPartner(
                    vertx,
                    "the shadowing file under openapi-contract",
                    sharedOpenApiContractComponent(vertx, config),
                    new JsonObject[] {
                        new JsonObject().put("sku", "ABC-1234"),
                        new JsonObject().put("sku", "ABC-1234").put("quantity", 2)
                    });
            assertAll(
                    strategy.label(),
                    () -> assertEquals("working-directory", strategy.title(), "info.title"),
                    () -> assertEquals(
                            400,
                            strategy.postStatuses().get(0),
                            "the body without quantity is refused by the strategy"),
                    () -> assertEquals(204, strategy.postStatuses().get(1), "the body with quantity is accepted"),
                    () -> assertServedFromFile(strategy, workingDirectoryPath),
                    () -> assertEquals(1, strategy.shadowWarnings().size(), "one shadowing warning: " + strategy),
                    () -> assertShadowWarning(strategy));
        }
    }

    /**
     * What one location arrangement showed.
     *
     * @param label          the arrangement
     * @param title          the served document's {@code info.title}
     * @param sourceLines    partner's source lines
     * @param shadowWarnings partner's shadowing warnings
     * @param postStatuses   the statuses of the {@code createOrder} requests, in order
     */
    private record Resolved(
            String label,
            String title,
            List<String> sourceLines,
            List<String> shadowWarnings,
            List<Integer> postStatuses) {}

    private Resolved deployAndReadPartner(Vertx vertx, String label, DocsProvisions component, JsonObject[] orders)
            throws Exception {
        warnings.clear();
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertDeployed(label, outcome);
            JsonNode tree = parseJson(label, send(outcome.port(), HttpMethod.GET, jsonUrl(PARTNER)));
            List<Integer> statuses = new ArrayList<>();
            if (orders != null) {
                for (JsonObject order : orders) {
                    statuses.add(post(outcome.port(), PARTNER_ORDERS_URI, order).status());
                }
            }
            List<String> shadowWarnings = warnings.lines().stream()
                    .filter(line -> line.level() == Level.WARN)
                    .map(LogLine::message)
                    .filter(message -> message.contains(SHADOWS))
                    .toList();
            return new Resolved(
                    label, tree.path("info").path("title").asText(), sourceLines(PARTNER), shadowWarnings, statuses);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    private static void assertServedFromFile(Resolved resolved, String expectedPath) {
        assertEquals(
                1, resolved.sourceLines().size(), () -> resolved.label() + ": partner's source lines: " + resolved);
        String line = resolved.sourceLines().get(0);
        assertTrue(
                line.contains(SERVED_FROM + expectedPath),
                () -> resolved.label() + ": the source line names " + expectedPath + ": " + line);
        assertFalse(line.contains(VERTX_CACHE), () -> resolved.label() + ": names a cache copy: " + line);
    }

    private static void assertShadowWarning(Resolved resolved) {
        String warning = resolved.shadowWarnings().get(0);
        assertAll(
                resolved.label() + ": the shadowing warning: " + warning,
                () -> assertTrue(startsWithDocument(warning, PARTNER), "starts with apidocs.documents.partner"),
                () -> assertTrue(namesApplication(warning, PARTNER), "names partner"),
                () -> assertTrue(warning.contains(SHADOWED_CONTRACT), "names " + SHADOWED_CONTRACT));
    }

    /**
     * An absolute contract location whose file name holds a line feed is served, and its source line
     * names the resolved location with the line feed escaped as a refusal escapes it, never raw.
     *
     * <p>The shadowing warning, the other line that names a configured location, is not arranged: it
     * needs a relative location that names both a working-directory file and a classpath resource
     * with a line feed in its name.
     */
    @Test
    @DisplayName("A source line names a resolved location with its control characters escaped, never raw")
    void sourceLineEscapesAControlCharacterOfTheResolvedLocation(Vertx vertx, @TempDir Path tempDir) throws Exception {
        // Given: a valid contract in a temporary file whose name holds a line feed
        Path file = tempDir.resolve(LINE_FEED_FILE_NAME);
        Files.writeString(file, ContractTexts.ABSOLUTE);
        String expectedEnding = SERVED_FROM + tempDir.toAbsolutePath().normalize() + "/" + LINE_FEED_FILE_NAME_SHOWN;

        // When: partner is deployed with that file as its configured contract location
        Resolved resolved = deployAndReadPartner(
                vertx,
                "an absolute contract location whose file name holds a line feed",
                sharedComponent(vertx, ContractConfigs.sharedWithPartnerContract(file.toString())),
                null);

        // Then: the file is served, and the source line names it escaped, with no raw line feed
        List<String> notices = noticeLines(PARTNER);
        assertAll(
                resolved.label(),
                () -> assertEquals(ContractTexts.ABSOLUTE_TITLE, resolved.title(), "info.title"),
                () -> assertEquals(1, resolved.sourceLines().size(), () -> "source lines: " + resolved),
                () -> assertTrue(
                        resolved.sourceLines().stream().allMatch(line -> line.endsWith(expectedEnding)),
                        () -> "the source line ends with " + expectedEnding + ": " + resolved.sourceLines()),
                () -> assertTrue(
                        notices.stream().noneMatch(line -> line.indexOf(LINE_FEED) >= 0),
                        () -> "a line for partner carries a raw line feed: " + notices));
    }

    // ---------------------------------------------------------------------------------------------
    // Served documents get the access and caching of generated documents
    // ---------------------------------------------------------------------------------------------

    /** A caller of the access table: its name, bearer token or {@code null}, and expected status. */
    private record Caller(String name, String token, int status) {}

    @Test
    @DisplayName("A served protected document answers every caller exactly as a generated protected document does")
    void servedDocumentsShareGeneratedDocumentAccessAndCaching(Vertx vertx) throws Exception {
        // Given: the JWT graph: partner served and protected, management generated and protected alike,
        // orders served and public, and a permissive configured default Cache-Control
        JwtProvisions component = DaggerServedContractTestComponents_ProtectedAccessComponent.factory()
                .create(vertx, ContractConfigs.protectedAccess());
        JWTAuth minter = SharedDeployment.jwtAuth(vertx);
        List<Caller> callers = List.of(
                new Caller("anonymous", null, 401),
                new Caller("bob", SharedDeployment.bob(minter), 403),
                new Caller("alice", SharedDeployment.alice(minter), 200));
        String alice = callers.get(2).token();

        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertDeployed("the protected-access graph", outcome);
            int port = outcome.port();
            Observations observations = component.observations();
            observations.reset();
            List<Executable> checks = new ArrayList<>();

            for (boolean yaml : List.of(false, true)) {
                String form = yaml ? "yaml" : "json";
                String servedUrl = yaml ? yamlUrl(PARTNER) : jsonUrl(PARTNER);
                String generatedUrl = yaml ? yamlUrl(MANAGEMENT) : jsonUrl(MANAGEMENT);

                // Given: each document's entity tag, read once by alice
                Exchange servedBaseline = sendTraced(port, servedUrl, alice, Map.of(), observations);
                Exchange generatedBaseline = sendTraced(port, generatedUrl, alice, Map.of(), observations);
                checks.add(() -> assertEquals(
                        fixtureTree(PARTNER_CONTRACT),
                        (yaml ? YAML : JSON).readTree(servedBaseline.body().getBytes()),
                        form + ": alice reads the partner contract's tree"));

                // When: each caller requests each document, with and without a matching If-None-Match
                for (Caller caller : callers) {
                    for (boolean conditional : List.of(false, true)) {
                        Exchange served = sendTraced(
                                port,
                                servedUrl,
                                caller.token(),
                                conditional ? ifNoneMatch(servedBaseline.header("ETag")) : Map.of(),
                                observations);
                        Exchange generated = sendTraced(
                                port,
                                generatedUrl,
                                caller.token(),
                                conditional ? ifNoneMatch(generatedBaseline.header("ETag")) : Map.of(),
                                observations);
                        String row = form + " | " + caller.name() + " | "
                                + (conditional ? "If-None-Match" : "unconditional");
                        int expectedStatus = caller.status() == 200 && conditional ? 304 : caller.status();
                        // Then: both documents answer alike
                        checks.add(() -> assertSameOutcome(row, expectedStatus, served, generated));
                    }
                }
            }

            // When: orders's public served document is requested anonymously, then with its tag
            Exchange orders = send(port, HttpMethod.GET, jsonUrl(ORDERS));
            String ordersTag = orders.header("ETag");
            Exchange ordersConditional = send(port, HttpMethod.GET, jsonUrl(ORDERS), null, ifNoneMatch(ordersTag));
            checks.add(() -> assertAll(
                    "orders, public and served",
                    () -> assertEquals(200, orders.status(), "status"),
                    () -> assertEquals("no-cache", orders.header("Cache-Control"), "Cache-Control"),
                    () -> assertNotNull(ordersTag, "an entity tag"),
                    () -> assertFalse(ordersTag.startsWith("W/"), "a strong entity tag: " + ordersTag),
                    () -> assertEquals(
                            fixtureTree(ORDERS_CONTRACT),
                            JSON.readTree(orders.body().getBytes()),
                            "tree"),
                    () -> assertEquals(304, ordersConditional.status(), "a matching If-None-Match")));
            assertAll("access and caching", checks.stream());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /**
     * Asserts that the served document answered a row exactly as the generated one did: status,
     * caching headers, a denial's problem body, and the fixture trace.
     */
    private static void assertSameOutcome(String row, int expectedStatus, Exchange served, Exchange generated) {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(expectedStatus, served.status(), "the served document's status"));
        checks.add(() -> assertEquals(generated.status(), served.status(), "the same status"));
        checks.add(() -> assertEquals(
                generated.headers().getAll("Cache-Control"),
                served.headers().getAll("Cache-Control"),
                "Cache-Control"));
        checks.add(() -> assertEquals(
                generated.headers().getAll("Vary"), served.headers().getAll("Vary"), "Vary"));
        checks.add(() -> assertEquals(generated.trace(), served.trace(), "the same fixture trace"));
        if (expectedStatus == 200 || expectedStatus == 304) {
            checks.add(() -> assertEquals("private, no-store", served.header("Cache-Control"), "Cache-Control"));
            checks.add(() -> assertEquals("Authorization", served.header("Vary"), "Vary"));
            checks.add(() -> assertNotNull(served.header("ETag"), "an entity tag"));
        } else {
            checks.add(() -> assertNull(served.header("ETag"), "a denial carries no entity tag"));
            checks.add(() -> assertNull(generated.header("ETag"), "the generated denial carries no entity tag"));
            checks.add(() ->
                    assertEquals(generated.body().toString(), served.body().toString(), "the same problem body"));
            checks.add(() -> assertFalse(served.body().toString().contains("openapi"), "no document content"));
        }
        if (expectedStatus == 304) {
            checks.add(() -> assertEquals(0, served.body().length(), "the 304 has no body"));
        }
        assertAll(row + " | served " + served + " | generated " + generated, checks.stream());
    }

    // ---------------------------------------------------------------------------------------------
    // Prefix, collision, and reserved-id checks cover served documents
    // ---------------------------------------------------------------------------------------------

    static Stream<StartupRow> prefixCollisionAndReservedIdRows() {
        return Stream.of(
                new StartupRow(
                        "catalog's GET /{a}/{b}/{c} can answer partner's document URL under /api/catalog/docs",
                        ServedContractIT::threeSegmentsCatalogComponent,
                        () -> ContractConfigs.withApidocsPath(ContractConfigs.shared(), "/api/catalog/docs"),
                        row -> assertStartupFailure(
                                row, null, null, null, "GET /{a}/{b}/{c}", "/api/catalog/docs/partner/openapi.json")),
                new StartupRow(
                        "a catalog operation uses partner's synthetic id",
                        ServedContractIT::reservedIdCatalogComponent,
                        ContractConfigs::shared,
                        row -> assertStartupFailure(
                                row, null, null, null, "apidocs:partner:json", "apidocs.documents.partner")),
                new StartupRow(
                        "a hand-built mount lies under the documentation prefix",
                        ServedContractIT::extraDocsComponent,
                        ContractConfigs::shared,
                        row -> assertStartupFailure(row, null, null, null, "/apidocs/extra/*", "apidocs.path")),
                new StartupRow(
                        "control for catalog's GET /{a}/{b}/{c}: every document disabled",
                        ServedContractIT::threeSegmentsCatalogComponent,
                        () -> allDocumentsDisabled(
                                ContractConfigs.withApidocsPath(ContractConfigs.shared(), "/api/catalog/docs")),
                        row -> assertDeployed(row.label(), row.outcome())),
                new StartupRow(
                        "control for partner's synthetic id: partner's document disabled",
                        ServedContractIT::reservedIdCatalogComponent,
                        () -> ContractConfigs.withDocumentEnabled(ContractConfigs.shared(), PARTNER, false),
                        row -> assertDeployed(row.label(), row.outcome())),
                new StartupRow(
                        "control for the hand-built mount under the prefix: every document disabled",
                        ServedContractIT::extraDocsComponent,
                        () -> allDocumentsDisabled(ContractConfigs.shared()),
                        row -> assertDeployed(row.label(), row.outcome())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prefixCollisionAndReservedIdRows")
    @DisplayName("The prefix, route-collision, and reserved-id checks cover served documents")
    void prefixCollisionAndReservedIdChecksCoverServedDocuments(StartupRow row, Vertx vertx) throws Exception {
        runStartupRow(row, vertx);
    }

    private static JsonObject allDocumentsDisabled(JsonObject config) {
        for (String document : List.of(PARTNER, ORDERS, CATALOG)) {
            ContractConfigs.withDocumentEnabled(config, document, false);
        }
        return config;
    }

    // ---------------------------------------------------------------------------------------------
    // Under openapi-contract, an own contract is served while the shared global one still fails
    // ---------------------------------------------------------------------------------------------

    /**
     * Under {@code openapi-contract}, partner's own contract is served and validates its requests, while
     * catalog's document on the shared global contract still fails startup, under both the built-in and
     * a custom contract strategy.
     *
     * <p>In (a), partner's contract is configured as {@code contracts/partner-strategy-openapi.yaml}, the
     * declared partner contract without a {@code servers} member: the contract-validation strategy
     * accepts only absolute server URLs or none, and with the declared relative {@code /api/partner} it
     * answers every validated request with 500.
     */
    @Test
    @DisplayName(
            "Under a contract strategy an application's own contract is served, while a document on the shared contract still fails")
    void ownContractServedUnderOpenApiContractWhileSharedGlobalStillFails(Vertx vertx) throws Exception {
        List<Executable> checks = new ArrayList<>();

        // (a) Given: openapi-contract, partner's contract configured without servers (the strategy accepts
        // only absolute server URLs or none), orders's and catalog's documents disabled
        JsonObject ownOnly = ContractConfigs.withDocumentEnabled(
                ContractConfigs.withDocumentEnabled(
                        ContractConfigs.sharedUnderOpenApiContractWithPartnerStrategyContract(), ORDERS, false),
                CATALOG,
                false);
        DocsProvisions served = sharedOpenApiContractComponent(vertx, ownOnly);
        Outcome servedOutcome = StartupDeployments.deploy(vertx, served::httpVerticle);
        try {
            assertDeployed("partner's own contract under openapi-contract", servedOutcome);
            int port = servedOutcome.port();
            JsonNode tree = parseJson("partner's own contract", send(port, HttpMethod.GET, jsonUrl(PARTNER)));
            int withoutSku = post(port, PARTNER_ORDERS_URI, new JsonObject().put("quantity", 1))
                    .status();
            int withSku = post(port, PARTNER_ORDERS_URI, new JsonObject().put("sku", "ABC-1234"))
                    .status();
            checks.add(() -> assertEquals(
                    fixtureTree(PARTNER_STRATEGY_CONTRACT), tree, "partner's tree is its configured contract's"));
            checks.add(() -> assertEquals(400, withoutSku, "the body without sku is refused by the strategy"));
            checks.add(() -> assertEquals(204, withSku, "the body with sku is accepted"));
        } finally {
            StartupDeployments.undeploy(vertx, servedOutcome);
        }

        // (b) Given: the same with catalog's document enabled; catalog validates against the shared contract
        JsonObject withCatalog =
                ContractConfigs.withDocumentEnabled(ContractConfigs.sharedUnderOpenApiContract(), ORDERS, false);
        checks.add(sharedContractRefusal(
                vertx,
                "catalog on the shared contract under openapi-contract",
                sharedOpenApiContractComponent(vertx, withCatalog),
                OPENAPI_CONTRACT));

        // (c) Given: (b) under a custom strategy whose flag says it resolves operations from the contract
        JsonObject custom =
                ContractConfigs.withDocumentEnabled(ContractConfigs.sharedUnderCustomContractTest(), ORDERS, false);
        DocsProvisions customComponent = DaggerServedContractTestComponents_SharedCustomContractTestComponent.factory()
                .create(vertx, custom);
        checks.add(sharedContractRefusal(
                vertx,
                "catalog on the shared contract under custom-contract-test",
                customComponent,
                CUSTOM_CONTRACT_TEST));

        assertAll("own contract and shared contract under contract strategies", checks.stream());
    }

    /** Deploys a composition expected to refuse catalog's document and returns the refusal's checks. */
    private static Executable sharedContractRefusal(
            Vertx vertx, String label, DocsProvisions component, String strategy) throws Exception {
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            Deployed row = new Deployed(label, outcome, component, null);
            Throwable refusal = assertStartupFailure(
                    row,
                    CATALOG,
                    null,
                    null,
                    "apidocs.documents.catalog",
                    CATALOG_BINARY,
                    CATALOG_MOUNT_PATH,
                    strategy,
                    SERVE_FRAGMENT,
                    ACCESS_CHECK_FRAGMENT);
            String message = refusal.getMessage();
            return () ->
                    assertFalse(message.contains(PARTNER), label + ": partner is not named as refused: " + message);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A served public document still warns about restricted operations, from the mount
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A served public document warns about the mount's restricted operations, not from the contract's text")
    void servedPublicDocumentWarnsFromTheMountsOperations(Vertx vertx) throws Exception {
        JsonNode expected = fixtureTree(PARTNER_RESTRICTED_CONTRACT);
        String token = SharedDeployment.alice(SharedDeployment.jwtAuth(vertx));

        // Given: partner served publicly from a contract without security, one operation restricting callers
        warnings.clear();
        JwtProvisions publicComponent = DaggerServedContractTestComponents_RestrictedPublicComponent.factory()
                .create(vertx, ContractConfigs.restricted());
        Outcome publicOutcome = StartupDeployments.deploy(vertx, publicComponent::httpVerticle);
        JsonNode publicTree;
        List<String> publicWarnings;
        try {
            assertDeployed("the public served document", publicOutcome);
            publicTree = parseJson("public", send(publicOutcome.port(), HttpMethod.GET, jsonUrl(PARTNER)));
            publicWarnings = restrictionWarnings();
        } finally {
            StartupDeployments.undeploy(vertx, publicOutcome);
        }

        // Given: the control, the same application with a protected document
        warnings.clear();
        JwtProvisions protectedComponent = DaggerServedContractTestComponents_RestrictedProtectedComponent.factory()
                .create(vertx, ContractConfigs.restricted());
        Outcome protectedOutcome = StartupDeployments.deploy(vertx, protectedComponent::httpVerticle);
        JsonNode protectedTree;
        List<String> protectedWarnings;
        try {
            assertDeployed("the protected served document", protectedOutcome);
            protectedTree = parseJson(
                    "protected", send(protectedOutcome.port(), HttpMethod.GET, jsonUrl(PARTNER), token, Map.of()));
            protectedWarnings = restrictionWarnings();
        } finally {
            StartupDeployments.undeploy(vertx, protectedOutcome);
        }

        // Then: one warning from the mount's operations, and the served tree is the contract's
        assertAll(
                "the public restriction warning of a served document",
                () -> assertEquals(1, publicWarnings.size(), "public: restriction warnings: " + publicWarnings),
                () -> assertTrue(
                        publicWarnings.get(0).contains(PARTNER_MOUNT_FRAGMENT),
                        "public: the warning names " + PARTNER_MOUNT_FRAGMENT + ": " + publicWarnings),
                () -> assertEquals(RESTRICTED_ENTRIES, listedEntries(publicWarnings.get(0)), "public: listed entries"),
                () -> assertEquals(expected, publicTree, "public: the served tree is the contract's"),
                () -> assertFalse(hasMemberNamed(publicTree, "security"), "public: no security member anywhere"),
                () -> assertEquals(expected, protectedTree, "protected: the served tree is the contract's"),
                () -> assertEquals(List.of(), protectedWarnings, "protected: no restriction warning"));
    }

    /** Returns partner's captured restriction warnings. */
    private List<String> restrictionWarnings() {
        return warnings.lines().stream()
                .filter(line -> line.level() == Level.WARN)
                .map(LogLine::message)
                .filter(message -> startsWithDocument(message, PARTNER) && message.contains(RESTRICT_FRAGMENT))
                .toList();
    }

    /** Returns the entries a restriction warning lists: its suffix after the list fragment, split. */
    private static List<String> listedEntries(String warning) {
        int at = warning.indexOf(LIST_FRAGMENT);
        assertTrue(at >= 0, () -> "the warning has no entry list after '" + LIST_FRAGMENT + "': " + warning);
        return List.of(warning.substring(at + LIST_FRAGMENT.length()).split(ENTRY_SEPARATOR, -1));
    }

    // ---------------------------------------------------------------------------------------------
    // A failed contract load is retried by a redeploy of the component
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A contract that could not be read is read again when the same component is deployed again")
    void failedLoadIsRetriedByARedeploy(Vertx vertx) throws Exception {
        // Given: partner's contract location exists neither in the working directory nor on the classpath
        assertFalse(
                Files.exists(Path.of(LATE_CONTRACT)), "precondition: no working-directory file at " + LATE_CONTRACT);
        assertNull(
                Thread.currentThread().getContextClassLoader().getResource(LATE_CONTRACT),
                "precondition: no classpath resource " + LATE_CONTRACT);
        DocsProvisions component =
                sharedComponent(vertx, ContractConfigs.sharedWithPartnerContract(ContractFiles.LATE));
        warnings.clear();

        // When: the component's supplier is deployed
        Outcome beforeFileExists = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: it fails naming partner and the unreadable contract, with no port
            assertStartupFailure(
                    new Deployed("before the file exists", beforeFileExists, component, this),
                    PARTNER,
                    PARTNER,
                    null,
                    UNREADABLE);
        } finally {
            StartupDeployments.undeploy(vertx, beforeFileExists);
        }

        // When: the contract is written and the same supplier is deployed again
        String expectedPath =
                Path.of(LATE_CONTRACT).toAbsolutePath().normalize().toString();
        try (WorkingDirectoryFile ignored = WorkingDirectoryFile.write(ContractFiles.LATE, ContractTexts.LATE)) {
            Outcome afterFileExists = StartupDeployments.deploy(vertx, component::httpVerticle);
            try {
                // Then: it starts and serves the written file, with one source line naming it
                assertDeployed("after the file exists", afterFileExists);
                JsonNode tree = parseJson(
                        "after the file exists", send(afterFileExists.port(), HttpMethod.GET, jsonUrl(PARTNER)));
                List<String> sources = sourceLines(PARTNER);
                assertAll(
                        "after the file exists",
                        () -> assertEquals(
                                JSON.readTree(ContractTexts.LATE), tree, "the served tree is the written file's"),
                        () -> assertEquals(
                                "late", tree.path("info").path("title").asText(), "info.title"),
                        () -> assertEquals(1, sources.size(), "partner's source lines: " + sources),
                        () -> assertTrue(
                                sources.get(0).contains(SERVED_FROM + expectedPath),
                                "the source line names the working-directory file " + expectedPath + ": " + sources));
            } finally {
                StartupDeployments.undeploy(vertx, afterFileExists);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Startup helpers
    // ---------------------------------------------------------------------------------------------

    /** Deploys one row of a startup table, checks it, and undeploys it. */
    private void runStartupRow(StartupRow row, Vertx vertx) throws Exception {
        // Given: the row's composition and configuration
        DocsProvisions component = row.component().apply(vertx, row.config().get());

        // When: it is deployed
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: the row's outcome holds
            row.check().verify(new Deployed(row.label(), outcome, component, this));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    private static void assertDeployed(String label, Outcome outcome) {
        Throwable failure = outcome.failure();
        assertNull(failure, () -> label + ": the deployment failed: " + describeChain(failure));
        assertNotNull(outcome.port(), () -> label + ": the deployment published no port");
    }

    /**
     * Asserts that a deployment failed before listening: no port, no store entry for {@code
     * storeName} when given, one exception in the cause chain whose message names {@code application}
     * (when given) and contains every fragment and that is an instance of {@code type} (when given),
     * and no message in the chain carrying the marker.
     *
     * @return the matching exception
     */
    private static Throwable assertStartupFailure(
            Deployed row, String application, String storeName, Class<?> type, String... fragments) {
        String label = row.label();
        Throwable failure = row.outcome().failure();
        assertNotNull(failure, () -> label + ": the deployment succeeded");
        List<Throwable> chain = causeChain(failure);
        assertNull(row.outcome().port(), () -> label + ": a port was published");
        if (storeName != null) {
            assertFalse(
                    PublicationAccess.names(row.component().documentStore()).contains(storeName),
                    () -> label + ": the store holds an entry for " + storeName);
        }
        Throwable match = chain.stream()
                .filter(cause -> type == null || type.isInstance(cause))
                .filter(cause -> cause.getMessage() != null)
                .filter(cause -> application == null || namesApplication(cause.getMessage(), application))
                .filter(cause -> Stream.of(fragments).allMatch(cause.getMessage()::contains))
                .findFirst()
                .orElse(null);
        assertNotNull(
                match,
                () -> label + ": no exception in the cause chain"
                        + (type == null ? "" : " of type " + type.getSimpleName())
                        + (application == null ? "" : " names application '" + application + "'")
                        + " and contains " + List.of(fragments) + ": " + describeChain(failure));
        for (Throwable cause : chain) {
            assertFalse(
                    String.valueOf(cause.getMessage()).contains(MARKER),
                    () -> label + ": a message in the cause chain echoes the marker: " + describeChain(failure));
        }
        return match;
    }

    /** Returns the failure and its causes, outermost first, each once. */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable cause = failure;
                cause != null && seen.put(cause, Boolean.TRUE) == null;
                cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static String describeChain(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (Throwable cause : causeChain(failure)) {
            text.append("\n  ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
        }
        return text.toString();
    }

    /** Reports whether a message names an application as {@code application '<name>'}, ignoring letter case. */
    private static boolean namesApplication(String message, String application) {
        return message.toLowerCase(Locale.ROOT)
                .contains(String.format(Locale.ROOT, APPLICATION_NAMED, application)
                        .toLowerCase(Locale.ROOT));
    }

    /** Returns the index of {@code '<name>'} in a message, or {@code -1}. */
    private static int quotedIndex(String message, String name) {
        Matcher matcher = Pattern.compile("'" + Pattern.quote(name) + "'").matcher(message);
        return matcher.find() ? matcher.start() : -1;
    }

    private static boolean startsWithDocument(String message, String document) {
        String prefix = "apidocs.documents." + document;
        return message.startsWith(prefix)
                && (message.length() == prefix.length() || !Character.isLetterOrDigit(message.charAt(prefix.length())));
    }

    // ---------------------------------------------------------------------------------------------
    // Components
    // ---------------------------------------------------------------------------------------------

    private static DocsProvisions sharedComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_SharedComponent.factory().create(vertx, config);
    }

    private static DocsProvisions annotatedInfoComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_AnnotatedInfoComponent.factory()
                .create(vertx, config);
    }

    private static DocsProvisions sharedOpenApiContractComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_SharedOpenApiContractComponent.factory()
                .create(vertx, config);
    }

    private static DocsProvisions alphaBetaComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_AlphaBetaComponent.factory().create(vertx, config);
    }

    private static DocsProvisions alphaBetaOpenApiContractComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_AlphaBetaOpenApiContractComponent.factory()
                .create(vertx, config);
    }

    private static DocsProvisions alphaCatalogBetaAdminComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_AlphaCatalogBetaAdminComponent.factory()
                .create(vertx, config);
    }

    private static DocsProvisions threeSegmentsCatalogComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_ThreeSegmentsCatalogComponent.factory()
                .create(vertx, config);
    }

    private static DocsProvisions reservedIdCatalogComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_ReservedIdCatalogComponent.factory()
                .create(vertx, config);
    }

    private static DocsProvisions extraDocsComponent(Vertx vertx, JsonObject config) {
        return DaggerServedContractTestComponents_HandBuiltMountComponent.factory()
                .create(vertx, config, ContractMounts.extraDocs());
    }

    // ---------------------------------------------------------------------------------------------
    // Request helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * A response as the proofs compare it.
     *
     * @param status  the status code
     * @param headers the response headers
     * @param body    the body, empty when there was none
     * @param trace   the fixture contributors that ran, in order; empty when not traced
     */
    private record Exchange(int status, MultiMap headers, Buffer body, List<String> trace) {

        String header(String name) {
            return headers.get(name);
        }

        String contentType() {
            return headers.get("Content-Type");
        }

        @Override
        public String toString() {
            return status + " " + headers.entries() + " trace " + trace;
        }
    }

    private static String jsonUrl(String document) {
        return "/apidocs/" + document + "/openapi.json";
    }

    private static String yamlUrl(String document) {
        return "/apidocs/" + document + "/openapi.yaml";
    }

    private static Map<String, String> ifNoneMatch(String tag) {
        assertNotNull(tag, "no entity tag to send in If-None-Match");
        return Map.of("If-None-Match", tag);
    }

    private Exchange send(int port, HttpMethod method, String url) throws Exception {
        return send(port, method, url, null, Map.of());
    }

    private Exchange send(int port, HttpMethod method, String url, String token, Map<String, String> headers)
            throws Exception {
        return send(client, port, method, url, token, headers, null);
    }

    private Exchange sendTraced(
            int port, String url, String token, Map<String, String> headers, Observations observations)
            throws Exception {
        return send(client, port, HttpMethod.GET, url, token, headers, observations);
    }

    /**
     * Sends one request and waits for its response. With an observation hub, the request carries a
     * fresh trace key and the exchange holds the trace the fixture contributors wrote for it; they
     * write it before the response is produced, so it is complete once the response arrives.
     */
    private static Exchange send(
            WebClient webClient,
            int port,
            HttpMethod method,
            String url,
            String token,
            Map<String, String> headers,
            Observations observations)
            throws Exception {
        HttpRequest<Buffer> request = webClient.request(method, port, HOST, url);
        String key = "served-contract-" + REQUESTS.incrementAndGet();
        if (observations != null) {
            request.putHeader(Observations.REQUEST_HEADER, key);
        }
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        headers.forEach(request::putHeader);
        HttpResponse<Buffer> response = Futures.await(request.send(), Duration.ofSeconds(REQUEST_SECONDS));
        Buffer body = response.body() == null ? Buffer.buffer() : response.body();
        List<String> trace = observations == null ? List.of() : observations.trace(key);
        return new Exchange(response.statusCode(), response.headers(), body, trace);
    }

    private Exchange post(int port, String uri, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = Futures.await(
                client.post(port, HOST, uri).sendJsonObject(body.copy()), Duration.ofSeconds(REQUEST_SECONDS));
        Buffer received = response.body() == null ? Buffer.buffer() : response.body();
        return new Exchange(response.statusCode(), response.headers(), received, List.of());
    }

    // ---------------------------------------------------------------------------------------------
    // Tree helpers
    // ---------------------------------------------------------------------------------------------

    /** Parses a {@code 200} JSON response's body. */
    private static JsonNode parseJson(String label, Exchange exchange) throws IOException {
        assertEquals(200, exchange.status(), () -> label + ": status of the JSON form: " + exchange.body());
        return JSON.readTree(exchange.body().getBytes());
    }

    /** Parses a test classpath contract file by its extension: JSON for {@code .json}, YAML otherwise. */
    private static JsonNode fixtureTree(String location) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(location)) {
            assertNotNull(in, "no test classpath resource " + location);
            return (location.endsWith(".json") ? JSON : YAML).readTree(in);
        }
    }

    /** Returns an object node's member names in order, or an empty list for any other node. */
    private static List<String> memberNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                names.add(member.getKey());
            }
        }
        return names;
    }

    /** Reports whether any object member at any depth has the name. */
    private static boolean hasMemberNamed(JsonNode node, String name) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                if (member.getKey().equals(name) || hasMemberNamed(member.getValue(), name)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (hasMemberNamed(element, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Log helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a document's source lines: {@code INFO} lines of the warning logger that start with
     * {@code apidocs.documents.<name>} and say {@value #GENERATED_SOURCE} or {@value #SERVED_FROM}.
     */
    private List<String> sourceLines(String document) {
        return warnings.lines().stream()
                .filter(line -> line.level() == Level.INFO)
                .map(LogLine::message)
                .filter(message -> startsWithDocument(message, document))
                .filter(message -> message.contains(GENERATED_SOURCE) || message.contains(SERVED_FROM))
                .toList();
    }

    /**
     * Returns a document's notices and warnings: every {@code INFO} or {@code WARN} line of the warning
     * logger that starts with {@code apidocs.documents.<name>}.
     */
    private List<String> noticeLines(String document) {
        return warnings.lines().stream()
                .filter(line -> line.level() == Level.INFO || line.level() == Level.WARN)
                .map(LogLine::message)
                .filter(message -> startsWithDocument(message, document))
                .toList();
    }

    /** Returns the loader's {@code DEBUG} lines for a document, on any of the module's loggers. */
    private List<LogLine> loaderLines(String document) {
        return docs.lines().stream()
                .filter(line -> line.level() == Level.DEBUG)
                .filter(line -> line.message().contains(SERVED_CONTRACT))
                .filter(line -> line.message().contains("apidocs.documents." + document))
                .toList();
    }

    private void assertGeneratedSource(String label, String document) {
        List<String> lines = sourceLines(document);
        assertEquals(1, lines.size(), () -> label + ": source lines: " + lines);
        assertTrue(lines.get(0).contains(GENERATED_SOURCE), () -> label + ": says generated: " + lines);
        assertFalse(lines.get(0).contains(SERVED_FROM), () -> label + ": names no served location: " + lines);
    }

    /** Asserts one served-from line naming the classpath resource's URL, never a cache copy. */
    private void assertServedFromClasspath(String label, String document, String resource) {
        assertClasspathLocation(label, sourceLines(document), resource);
    }

    private static void assertClasspathLocation(String label, List<String> lines, String resource) {
        assertEquals(1, lines.size(), () -> label + ": source lines: " + lines);
        String line = lines.get(0);
        int at = line.indexOf(SERVED_FROM);
        assertTrue(at >= 0, () -> label + ": says served from: " + line);
        assertFalse(line.contains(GENERATED_SOURCE), () -> label + ": says generated: " + line);
        String location = line.substring(at + SERVED_FROM.length());
        assertTrue(
                location.startsWith("file:") || location.startsWith("jar:"),
                () -> label + ": the resolved location is a classpath URL: " + line);
        assertTrue(location.contains(resource), () -> label + ": the URL names " + resource + ": " + line);
        assertFalse(line.contains(VERTX_CACHE), () -> label + ": names a cache copy: " + line);
    }

    /** Asserts that no source line on the warning logger carries the store's keyword. */
    private void assertNoStoredSourceLine(String label) {
        List<String> stored = warnings.lines().stream()
                .filter(line -> line.level() == Level.INFO)
                .map(LogLine::message)
                .filter(message -> message.contains(STORED))
                .toList();
        assertEquals(List.of(), stored, label + ": INFO lines on the warning logger saying " + STORED);
    }

    /** One captured log event: its level, formatted message, and the thread that logged it. */
    private record LogLine(Level level, String message, String thread) {}

    /**
     * A list appender that resolves each event's thread name and message while the event is appended,
     * on the logging thread, and hands out copies of what it captured.
     */
    private static final class CapturingAppender extends ListAppender<ILoggingEvent> {

        private final List<LogLine> captured = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            captured.add(new LogLine(event.getLevel(), event.getFormattedMessage(), event.getThreadName()));
            super.append(event);
        }

        /** Returns a copy of every line captured so far. */
        List<LogLine> lines() {
            synchronized (this) {
                return List.copyOf(captured);
            }
        }

        /** Forgets every line captured so far. */
        void clear() {
            synchronized (this) {
                captured.clear();
                list.clear();
            }
        }
    }
}
