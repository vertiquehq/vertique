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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.ContractRefusalTestComponents.DocsProvisions;
import dev.vertique.rest.openapi.docs.ContractRefusalTestComponents.Provisions;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments.Outcome;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Deploys compositions in which the {@code openapi-contract} request-validation strategy is available
 * and observes how a documented application's document meets it: a document for an application whose
 * operations the selected strategy resolves from the shared global contract refuses startup, while a
 * strategy that resolves nothing from a contract publishes the generated document, an application with
 * a contract of its own and no configured {@code info} serves that contract as its document; and without an enabled document the strategy's behavior and the
 * not-installed notice are those of a composition without the documentation module.
 *
 * <p>Every deployment goes through {@link StartupDeployments}, which reads the bound port from the
 * {@code vertique} local map and clears it; requests go through {@link #exchange}, which returns the
 * response's status, {@code Content-Type}, and body.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiContractRefusalIT {

    /** The host every server binds and every request dials. */
    private static final String LOOPBACK = "127.0.0.1";

    /** The id of the contract-validation strategy. */
    private static final String OPENAPI_CONTRACT = "openapi-contract";

    /** The id of the custom test strategy that resolves nothing from a contract. */
    private static final String CUSTOM_DOCS_TEST = "custom-docs-test";

    /** The id of the custom test strategy that resolves operations from the mount's contract. */
    private static final String CUSTOM_CONTRACT_TEST = "custom-contract-test";

    /** A strategy id no registered strategy carries. */
    private static final String UNKNOWN_STRATEGY = "no-such-strategy";

    /** The marker carried by the absent shared contract's location; no message may echo it. */
    private static final String MARKER = "zq7";

    /** The shared global contract location of the refusal cases: absent from the classpath. */
    private static final String ABSENT_SHARED_CONTRACT = MARKER + "-contract.json";

    /** The shared global contract location of the unchanged-behavior builds: a test resource. */
    private static final String SHARED_TEST_CONTRACT = "apidocs-contract-test.json";

    /** The application's own contract location in the own-contract cases: a test resource. */
    private static final String OWN_CONTRACT = "public-contract.json";

    /** The first recommendation fragment of the shared-contract refusal. */
    private static final String SERVE_FRAGMENT = "serve";

    /** The second recommendation fragment of the shared-contract refusal. */
    private static final String ACCESS_CHECK_FRAGMENT = "access check at least as strict as the most restricted mount";

    /** The documented shared application. */
    private static final String PUBLIC = "public";

    /** The binary name of the documented shared application's declaring interface. */
    private static final String PUBLIC_API_BINARY = "dev.vertique.rest.openapi.docs.fixture.PublicApi";

    /** The mount path of the documented shared application. */
    private static final String PUBLIC_MOUNT_PATH = "/api/public/*";

    /** The documented empty application. */
    private static final String EMPTY = "empty";

    /** The binary name of the documented empty application's declaring interface. */
    private static final String EMPTY_API_BINARY = "dev.vertique.rest.openapi.docs.fixture.startup.EmptyApi";

    /** The mount path of the documented empty application. */
    private static final String EMPTY_MOUNT_PATH = "/api/empty/*";

    /** The logger of the declared-application view, which logs the not-installed notice. */
    private static final String VIEW_LOGGER = "dev.vertique.rest.jaxrs.RestApplicationsBuilder";

    /** The fixed text of the notice that a documented application's documentation module is not installed. */
    private static final String NOT_INSTALLED_NOTICE =
            "carries @ApiDocs, but the OpenAPI documentation module is not included";

    /** The documented application as the notice quotes it. */
    private static final String NOTICE_APPLICATION = "'public'";

    /**
     * The request URI of {@code createItem}: the path with {@code dryRun=false}, so the operation's
     * primitive query parameter is bound and only the body differs between the two requests.
     */
    private static final String ITEMS_URI = "/api/public/items?dryRun=false";

    /** The URL of the {@code public} document's JSON form. */
    private static final String PUBLIC_DOCUMENT_URL = "/apidocs/public/openapi.json";

    /** A {@code createItem} body the shared test contract accepts. */
    private static final JsonObject CONFORMING_ITEM =
            new JsonObject().put("name", "widget").put("quantity", 1);

    /** A {@code createItem} body missing the required {@code name}. */
    private static final JsonObject ITEM_WITHOUT_NAME = new JsonObject().put("quantity", 1);

    private WebClient client;

    private Logger viewLogger;
    private Level previousViewLevel;
    private ListAppender<ILoggingEvent> viewAppender;

    @BeforeEach
    void createClient(Vertx vertx) {
        client = WebClient.create(vertx);
    }

    @BeforeEach
    void captureViewLog() {
        viewLogger = (Logger) LoggerFactory.getLogger(VIEW_LOGGER);
        previousViewLevel = viewLogger.getLevel();
        viewLogger.setLevel(Level.INFO);
        viewAppender = new ListAppender<>();
        viewAppender.start();
        viewLogger.addAppender(viewAppender);
    }

    @AfterEach
    void closeClient(Vertx vertx) {
        client.close();
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).remove(StartupDeployments.PORT_KEY);
    }

    @AfterEach
    void releaseViewLog() {
        viewLogger.detachAppender(viewAppender);
        viewAppender.stop();
        viewLogger.setLevel(previousViewLevel);
    }

    // ---------------------------------------------------------------------------------------------
    // A document on the shared contract fails startup
    // ---------------------------------------------------------------------------------------------

    /**
     * One composition of the shared-contract cases.
     *
     * @param label     the case, as the report names it
     * @param component creates the component from the Vert.x instance and the configuration
     * @param config    creates the case's configuration
     * @param document  the document the case enables
     * @param refusal   what the refusal names, or {@code null} for a case that must deploy
     * @param contract  the classpath location of the contract the case's document must serve, or
     *                  {@code null} for a case whose document is generated
     */
    record ContractCase(
            String label,
            BiFunction<Vertx, JsonObject, DocsProvisions> component,
            Supplier<JsonObject> config,
            String document,
            Refusal refusal,
            String contract) {

        ContractCase(
                String label,
                BiFunction<Vertx, JsonObject, DocsProvisions> component,
                Supplier<JsonObject> config,
                String document,
                Refusal refusal) {
            this(label, component, config, document, refusal, null);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * What a shared-contract refusal names besides the document and the recommendation.
     *
     * @param binaryName the binary name of the application's declaring interface
     * @param mountPath  the application's mount path
     * @param strategy   the selected strategy's id
     */
    record Refusal(String binaryName, String mountPath, String strategy) {}

    static Stream<ContractCase> sharedContractCases() {
        Refusal publicOnSharedContract = new Refusal(PUBLIC_API_BINARY, PUBLIC_MOUNT_PATH, OPENAPI_CONTRACT);
        return Stream.of(
                new ContractCase(
                        "(a) the shared fixture under openapi-contract",
                        (vertx, config) -> DaggerContractRefusalTestComponents_SharedContractComponent.factory()
                                .create(vertx, config),
                        () -> sharedConfig(OPENAPI_CONTRACT),
                        PUBLIC,
                        publicOnSharedContract),
                new ContractCase(
                        "(b) an empty documented mount under openapi-contract",
                        (vertx, config) -> DaggerContractRefusalTestComponents_EmptyContractComponent.factory()
                                .create(vertx, config),
                        OpenApiContractRefusalIT::emptyConfig,
                        EMPTY,
                        new Refusal(EMPTY_API_BINARY, EMPTY_MOUNT_PATH, OPENAPI_CONTRACT)),
                new ContractCase(
                        "(c) a custom strategy that resolves nothing from a contract",
                        (vertx, config) -> DaggerContractRefusalTestComponents_DocsTestStrategyComponent.factory()
                                .create(vertx, config),
                        () -> sharedConfig(CUSTOM_DOCS_TEST),
                        PUBLIC,
                        null),
                new ContractCase(
                        "(d) the application declares its own contract",
                        (vertx, config) -> DaggerContractRefusalTestComponents_OwnContractComponent.factory()
                                .create(vertx, config),
                        () -> ownContractConfig(sharedConfig(OPENAPI_CONTRACT)),
                        PUBLIC,
                        null,
                        OWN_CONTRACT),
                new ContractCase(
                        "(e) the application's contract is configured",
                        (vertx, config) -> DaggerContractRefusalTestComponents_SharedContractComponent.factory()
                                .create(vertx, config),
                        () -> withApplicationContract(
                                ownContractConfig(sharedConfig(OPENAPI_CONTRACT)), PUBLIC, OWN_CONTRACT),
                        PUBLIC,
                        null,
                        OWN_CONTRACT),
                new ContractCase(
                        "(f) a custom strategy that resolves operations from the mount's contract",
                        (vertx, config) -> DaggerContractRefusalTestComponents_ContractTestStrategyComponent.factory()
                                .create(vertx, config),
                        () -> sharedConfig(CUSTOM_CONTRACT_TEST),
                        PUBLIC,
                        new Refusal(PUBLIC_API_BINARY, PUBLIC_MOUNT_PATH, CUSTOM_CONTRACT_TEST)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedContractCases")
    @DisplayName("a document whose operations the selected strategy resolves from the shared contract fails startup")
    void documentOnTheSharedContractFailsStartup(ContractCase contractCase, Vertx vertx) throws Exception {
        // Given: the case's composition and configuration
        DocsProvisions component =
                contractCase.component().apply(vertx, contractCase.config().get());

        // When: it is deployed
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: the refused cases fail naming the document, and the others serve it
            if (contractCase.refusal() != null) {
                assertRefused(contractCase, outcome, component);
            } else {
                assertServed(contractCase, outcome);
            }
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    private static void assertRefused(ContractCase contractCase, Outcome outcome, DocsProvisions component) {
        Refusal expected = contractCase.refusal();
        String document = contractCase.document();
        Set<String> stored = component.documentStore().names();
        assertAll(
                contractCase.label() + ": startup is refused",
                () -> assertNotNull(
                        outcome.failure(), "the deployment succeeded; the document was published: " + stored),
                () -> assertNull(outcome.port(), "no port is published"),
                () -> assertEquals(Set.of(), stored, "the store holds no entry"));

        Throwable failure = outcome.failure();
        RestConfigurationException refusal = assertInstanceOf(
                RestConfigurationException.class,
                failure,
                () -> "the refusal's class: " + failure.getClass().getName() + ": " + failure);
        String message = refusal.getMessage();
        assertNotNull(message, "the refusal has a message");
        assertAll(
                contractCase.label() + ": the refusal's message: " + message,
                () -> assertTrue(
                        message.contains("apidocs.documents." + document),
                        "names the configuration path apidocs.documents." + document),
                () -> assertTrue(
                        applicationNamed(document).matcher(message).find(), "names the application " + document),
                () -> assertTrue(
                        message.contains(expected.binaryName()),
                        "names the declaring interface " + expected.binaryName()),
                () -> assertTrue(
                        message.contains(expected.mountPath()), "names the mount path " + expected.mountPath()),
                () -> assertTrue(message.contains(expected.strategy()), "names the strategy " + expected.strategy()),
                () -> assertTrue(message.contains(SERVE_FRAGMENT), "recommends: " + SERVE_FRAGMENT),
                () -> assertTrue(message.contains(ACCESS_CHECK_FRAGMENT), "recommends: " + ACCESS_CHECK_FRAGMENT),
                () -> assertFalse(message.contains(MARKER), "echoes no configuration value"));
    }

    private void assertServed(ContractCase contractCase, Outcome outcome) throws Exception {
        Throwable failure = outcome.failure();
        assertNull(failure, () -> contractCase.label() + ": the deployment failed: " + failure);
        assertNotNull(outcome.port(), contractCase.label() + ": a port is published");

        String url = "/apidocs/" + contractCase.document() + "/openapi.json";
        Exchange served = exchange(client.get(outcome.port(), LOOPBACK, url).send());
        assertAll(
                contractCase.label() + ": GET " + url + " answers from the documentation mount: " + served,
                () -> assertEquals(200, served.status(), "status"),
                () -> assertTrue(
                        served.contentType() != null && served.contentType().startsWith("application/json"),
                        "a JSON document"),
                () -> assertTrue(new JsonObject(served.body()).containsKey("openapi"), "an OpenAPI document"));
        if (contractCase.contract() != null) {
            assertEquals(
                    loadContract(contractCase.contract()),
                    new JsonObject(served.body()),
                    contractCase.label() + ": the document is the application's own contract");
        }
    }

    /** Reads a contract test resource from the classpath. */
    private static JsonObject loadContract(String resource) throws Exception {
        try (InputStream in = OpenApiContractRefusalIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "the test resource " + resource);
            return new JsonObject(Buffer.buffer(in.readAllBytes()));
        }
    }

    @Test
    @DisplayName(
            "an empty documented mount whose configured strategy id names no registered strategy is not refused and serves its document")
    void emptyMountWithAnUnknownStrategyIdServesItsDocument(Vertx vertx) throws Exception {
        // Given: EmptyApi as the sole registration, the shared contract location absent, and a
        // configured strategy id no registered strategy carries; an empty mount selects no strategy
        JsonObject config = emptyConfig();
        config.getJsonObject("jaxrs").put("validationStrategy", UNKNOWN_STRATEGY);
        ContractCase contractCase = new ContractCase(
                "an empty documented mount under an unknown strategy id",
                (v, c) -> DaggerContractRefusalTestComponents_EmptyContractComponent.factory()
                        .create(v, c),
                () -> config,
                EMPTY,
                null);
        DocsProvisions component =
                contractCase.component().apply(vertx, contractCase.config().get());

        // When: it is deployed
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            // Then: it deploys and the documentation mount serves the empty document
            assertServed(contractCase, outcome);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Without an enabled document the strategy and the notice are unchanged
    // ---------------------------------------------------------------------------------------------

    /**
     * One named build of the unchanged-behavior proof.
     *
     * @param label     the build, as the report names it
     * @param component creates the build's component
     */
    record Build(String label, Supplier<Provisions> component) {}

    /**
     * What one build answered.
     *
     * @param conforming    a conforming {@code POST} of an item
     * @param nonConforming a {@code POST} of an item missing {@code name}
     * @param document      {@code GET} of the {@code public} document's URL
     * @param notices       the not-installed notices the build logged
     */
    record Observed(Exchange conforming, Exchange nonConforming, Exchange document, List<String> notices) {}

    @Test
    @DisplayName(
            "without an enabled document openapi-contract validates as without the module, and only a build without it logs the notice")
    void withoutEnabledDocumentOpenApiContractIsUnchanged(Vertx vertx) throws Exception {
        // Given: the shared fixture under openapi-contract with the test contract, built three ways
        Build withoutModule = new Build(
                "(i) without the documentation module",
                () -> DaggerContractRefusalTestComponents_WithoutDocsModuleContractComponent.factory()
                        .create(vertx, sharedTestContractConfig()));
        Build documentOff = new Build(
                "(ii) with the module, the document switched off",
                () -> DaggerContractRefusalTestComponents_DocumentOffContractComponent.factory()
                        .create(vertx, DocsConfigs.withDocumentEnabled(sharedTestContractConfig(), PUBLIC, false)));
        Build apidocsOff = new Build(
                "(iii) with the module, apidocs switched off",
                () -> DaggerContractRefusalTestComponents_ApidocsOffContractComponent.factory()
                        .create(vertx, DocsConfigs.withApidocsEnabled(sharedTestContractConfig(), false)));

        // When: each is deployed and sent the three requests
        Map<String, Observed> observed = new LinkedHashMap<>();
        for (Build build : List.of(withoutModule, documentOff, apidocsOff)) {
            observed.put(build.label(), observe(vertx, build));
        }

        // Then: every build answers alike, and only the build without the module logs the notice
        Observed reference = observed.get(withoutModule.label());
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertTrue(
                reference.conforming().status() >= 200 && reference.conforming().status() < 300,
                "the conforming request succeeds: " + reference.conforming()));
        checks.add(() -> assertEquals(
                400, reference.nonConforming().status(), "the non-conforming request: " + reference.nonConforming()));
        checks.add(() -> assertEquals(404, reference.document().status(), "the document URL: " + reference.document()));
        for (Build build : List.of(documentOff, apidocsOff)) {
            Observed other = observed.get(build.label());
            checks.add(() -> assertEquals(
                    reference.conforming(), other.conforming(), build.label() + ": the conforming request"));
            checks.add(() -> assertEquals(
                    reference.nonConforming(), other.nonConforming(), build.label() + ": the non-conforming request"));
            checks.add(
                    () -> assertEquals(reference.document(), other.document(), build.label() + ": the document URL"));
            checks.add(() -> assertEquals(List.of(), other.notices(), build.label() + ": no not-installed notice"));
        }
        checks.add(() -> assertEquals(
                1, reference.notices().size(), withoutModule.label() + ": one notice: " + reference.notices()));
        checks.add(() -> assertTrue(
                reference.notices().stream().allMatch(notice -> notice.contains(NOTICE_APPLICATION)),
                withoutModule.label() + ": the notice names " + NOTICE_APPLICATION + ": " + reference.notices()));
        assertAll("the three builds: " + observed, checks.stream());
    }

    /** Deploys one build, sends the three requests, undeploys it, and returns what it answered and logged. */
    private Observed observe(Vertx vertx, Build build) throws Exception {
        synchronized (viewAppender) {
            viewAppender.list.clear();
        }
        Provisions component = build.component().get();
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            Throwable failure = outcome.failure();
            assertNull(failure, () -> build.label() + ": the deployment failed: " + failure);
            assertNotNull(outcome.port(), build.label() + ": a port is published");
            int port = outcome.port();
            Exchange conforming =
                    exchange(client.post(port, LOOPBACK, ITEMS_URI).sendJsonObject(CONFORMING_ITEM.copy()));
            Exchange nonConforming =
                    exchange(client.post(port, LOOPBACK, ITEMS_URI).sendJsonObject(ITEM_WITHOUT_NAME.copy()));
            Exchange document =
                    exchange(client.get(port, LOOPBACK, PUBLIC_DOCUMENT_URL).send());
            return new Observed(conforming, nonConforming, document, notices());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** Returns the formatted {@code INFO} notices captured since the appender was last cleared. */
    private List<String> notices() {
        synchronized (viewAppender) {
            return viewAppender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(NOT_INSTALLED_NOTICE))
                    .toList();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * A response as the proofs compare it.
     *
     * @param status      the status code
     * @param contentType the {@code Content-Type} header, or {@code null}
     * @param body        the body as text, empty when there is none
     */
    record Exchange(int status, String contentType, String body) {}

    /** Waits for a response and returns its status, {@code Content-Type}, and body. */
    private static Exchange exchange(Future<HttpResponse<Buffer>> response) throws Exception {
        HttpResponse<Buffer> received;
        try {
            received = response.toCompletionStage()
                    .toCompletableFuture()
                    .get(StartupDeployments.BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError("the request failed", failed.getCause());
        }
        Buffer body = received.body();
        return new Exchange(
                received.statusCode(), received.getHeader("Content-Type"), body == null ? "" : body.toString());
    }

    /** Matches the application's name quoted on its own, not as part of a path, a key, or a class name. */
    private static Pattern applicationNamed(String name) {
        return Pattern.compile("(?<![./\\w])" + Pattern.quote(name) + "(?![/\\w])");
    }

    /**
     * Returns the shared configuration ({@code apidocs.documents.public.info} set) with the given
     * strategy selected and the absent shared contract location.
     */
    private static JsonObject sharedConfig(String strategy) {
        return withContract(DocsConfigs.shared(), strategy, ABSENT_SHARED_CONTRACT);
    }

    /**
     * Returns the loopback configuration with {@code apidocs.documents.empty.info} set, the
     * {@code openapi-contract} strategy selected, and the absent shared contract location.
     */
    private static JsonObject emptyConfig() {
        JsonObject config = DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), EMPTY, "Empty", "1");
        return withContract(config, OPENAPI_CONTRACT, ABSENT_SHARED_CONTRACT);
    }

    /**
     * Removes the configured {@code apidocs.documents.public.info} (and a configured
     * {@code serverUrl}, should one be present) from the configuration: an application that serves
     * its own contract takes its identity from that contract.
     */
    private static JsonObject ownContractConfig(JsonObject config) {
        JsonObject document =
                DocsConfigs.apidocs(config).getJsonObject("documents").getJsonObject(PUBLIC);
        document.remove("info");
        document.remove("serverUrl");
        return config;
    }

    /** Returns the shared configuration with the {@code openapi-contract} strategy and the shared test contract. */
    private static JsonObject sharedTestContractConfig() {
        return withContract(DocsConfigs.shared(), OPENAPI_CONTRACT, SHARED_TEST_CONTRACT);
    }

    /** Sets {@code jaxrs.validationStrategy} and {@code jaxrs.openapiPath}. */
    private static JsonObject withContract(JsonObject config, String strategy, String openapiPath) {
        config.getJsonObject("jaxrs").put("validationStrategy", strategy).put("openapiPath", openapiPath);
        return config;
    }

    /** Sets {@code jaxrs.applications.<name>.openapiPath}. */
    private static JsonObject withApplicationContract(JsonObject config, String name, String openapiPath) {
        config.getJsonObject("jaxrs")
                .put("applications", new JsonObject().put(name, new JsonObject().put("openapiPath", openapiPath)));
        return config;
    }
}
