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
import dev.vertique.rest.openapi.docs.ContractLoadTestComponents.Provisions;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.startup.contractload.AnnotatedContractApi;
import dev.vertique.rest.openapi.docs.fixture.startup.contractload.ContractLoadModules;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deploys compositions with the {@code openapi-contract} request-validation strategy and a JAX-RS mount
 * whose contract that strategy cannot load, and observes that startup fails before any server listens,
 * with a value-free {@link RestConfigurationException} naming the application and the setting its
 * contract location came from (or, for a hand-built mount, the mount path) and the reason class. A
 * loadable contract still starts and validates, loaded once; a broken contract no mount binds, an empty
 * mount, and every other strategy start as before.
 *
 * <p>Every contract location is an absolute path in a temporary directory, except where a declaration or
 * a hand-built mount needs a compile-time constant; every unloadable fixture carries {@value #MARKER} in
 * its file name, its {@code servers} URL, and its {@code info.description}, and no failure may echo it.
 * Every deployment goes through {@link StartupDeployments}, which reads the bound port from the
 * {@code vertique} local map.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class OpenApiContractLoadStartupIT {

    /** The host every server binds and every request dials. */
    private static final String LOOPBACK = "127.0.0.1";

    /** The marker every unloadable fixture carries; no failure may echo it. */
    private static final String MARKER = ContractLoadModules.MARKER;

    /** The id of the contract-validation strategy. */
    private static final String OPENAPI_CONTRACT = "openapi-contract";

    /** The fixed part of every failure, after the mount's identification. */
    private static final String CANNOT_BE_LOADED =
            " cannot be loaded for request-validation strategy 'openapi-contract': ";

    /** The reason for a location whose extension is not json, yaml, or yml. */
    private static final String EXTENSION_REASON = "its location must end in .json, .yaml, or .yml";

    /** The reason for a contract file that cannot be read. */
    private static final String UNREADABLE_REASON = "the file cannot be read";

    /** The reason for a contract with a relative or otherwise malformed server URL. */
    private static final String SERVERS_REASON =
            "a servers url is not a valid absolute URL; use an absolute URL or omit servers";

    /** The reason for any other contract vertx-openapi rejects. */
    private static final String INVALID_REASON = "the file is not a valid OpenAPI contract";

    /** The identification of the {@code public} application with a configured contract location. */
    private static final String PUBLIC_CONFIGURED = "OpenAPI contract of application '" + PublicApi.NAME
            + "' (jaxrs.applications." + PublicApi.NAME + ".openapiPath)";

    /** The classpath resource of a loadable, servers-free contract describing {@code public}'s operations. */
    private static final String PUBLIC_CONTRACT_RESOURCE = "apidocs-contract-test.json";

    /** A contract whose only server URL is relative. */
    private static final String RELATIVE_SERVERS_CONTRACT = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Public\","
            + "\"version\":\"1\",\"description\":\"" + MARKER + " description\"},\"servers\":[{\"url\":\"/" + MARKER
            + "/api\"}],\"paths\":{}}";

    /** A loadable contract with no {@code servers}. */
    private static final String VALID_CONTRACT = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Public\","
            + "\"version\":\"1\",\"description\":\"" + MARKER + " description\"},\"paths\":{}}";

    /** A JSON document that is not an OpenAPI contract: it has no {@code info}. */
    private static final String MISSING_INFO_CONTRACT = "{\"openapi\":\"3.0.3\",\"paths\":{\"/" + MARKER + "\":{}}}";

    /**
     * The request URI of {@code createItem}: the path with {@code dryRun=false}, so the operation's
     * primitive query parameter is bound and only the body differs between requests.
     */
    private static final String ITEMS_URI = "/api/public/items?dryRun=false";

    /** A {@code createItem} body the public contract accepts. */
    private static final JsonObject CONFORMING_ITEM =
            new JsonObject().put("name", "widget").put("quantity", 1);

    /** A {@code createItem} body missing the required {@code name}. */
    private static final JsonObject ITEM_WITHOUT_NAME = new JsonObject().put("quantity", 1);

    @TempDir
    Path tempDir;

    private WebClient client;

    @BeforeEach
    void createClient(Vertx vertx) {
        client = WebClient.create(vertx);
    }

    @AfterEach
    void closeClient(Vertx vertx) {
        client.close();
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).remove(StartupDeployments.PORT_KEY);
    }

    // ---------------------------------------------------------------------------------------------
    // An unloadable contract fails startup
    // ---------------------------------------------------------------------------------------------

    /**
     * One unloadable contract configured for {@code public}.
     *
     * @param label    the case, as the report names it
     * @param fileName the file name inside the temporary directory
     * @param content  the file content, or {@code null} for an absent file
     * @param reason   the reason the failure must give
     */
    record Unloadable(String label, String fileName, String content, String reason) {

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Unloadable> unloadableContracts() {
        return Stream.of(
                new Unloadable(
                        "(1) a relative servers url",
                        MARKER + "-relative-servers.json",
                        RELATIVE_SERVERS_CONTRACT,
                        SERVERS_REASON),
                new Unloadable(
                        "(2) a readable contract with a .txt extension",
                        MARKER + "-contract.txt",
                        VALID_CONTRACT,
                        EXTENSION_REASON),
                new Unloadable("(3) a missing file", MARKER + "-missing.json", null, UNREADABLE_REASON),
                new Unloadable(
                        "(4) a document that is not an OpenAPI contract",
                        MARKER + "-missing-info.json",
                        MISSING_INFO_CONTRACT,
                        INVALID_REASON));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unloadableContracts")
    @DisplayName("an application whose configured contract cannot be loaded fails startup with the reason class")
    void unloadableConfiguredContractFailsStartup(Unloadable unloadable, Vertx vertx) throws Exception {
        // Given: openapi-contract, a loadable global contract, and public's contract configured as the case
        Path location = tempDir.resolve(unloadable.fileName());
        if (unloadable.content() != null) {
            Files.writeString(location, unloadable.content());
        }
        JsonObject config = withApplicationContract(
                config(OPENAPI_CONTRACT, publicContract("global-contract.json")), location.toString());

        // When: it is deployed
        Outcome outcome = deploy(vertx, publicComponent(vertx, config));

        // Then: startup fails naming public, its configured setting, and the reason
        String message = assertRefused(unloadable.label(), outcome, location.toString(), unloadable.fileName());
        assertAll(
                unloadable.label() + ": the failure's message: " + message,
                () -> assertTrue(
                        message.contains(PUBLIC_CONFIGURED + CANNOT_BE_LOADED),
                        "names public, its configured setting, and the strategy"),
                () -> assertTrue(message.contains(unloadable.reason()), "gives the reason: " + unloadable.reason()));
    }

    @Test
    @DisplayName("(2) a contract location with an upper-case .JSON extension starts and validates")
    void upperCaseExtensionStartsAndValidates(Vertx vertx) throws Exception {
        // Given: openapi-contract and public's loadable contract configured with an upper-case extension
        Path location = tempDir.resolve(MARKER + "-contract.JSON");
        Files.writeString(location, publicContractText());
        JsonObject config = withApplicationContract(
                config(OPENAPI_CONTRACT, publicContract("global-contract.json")), location.toString());

        // When: it is deployed and sent a conforming and a non-conforming request
        Outcome outcome = deploy(vertx, publicComponent(vertx, config));
        try {
            assertDeployed("(2) an upper-case .JSON extension", outcome);
            int conforming = postItem(outcome.port(), CONFORMING_ITEM);
            int nonConforming = postItem(outcome.port(), ITEM_WITHOUT_NAME);

            // Then: the contract validates both
            assertAll(
                    () -> assertTrue(conforming >= 200 && conforming < 300, "the conforming request: " + conforming),
                    () -> assertEquals(400, nonConforming, "the request without name"));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    @Test
    @DisplayName("(5) a hand-built JAX-RS mount whose contract cannot be loaded fails startup naming its mount path")
    void unloadableHandBuiltMountContractFailsStartup(Vertx vertx) throws Exception {
        // Given: openapi-contract, a loadable global contract, and a hand-built mount whose contract has a
        // relative servers url
        JsonObject config = config(OPENAPI_CONTRACT, publicContract("global-contract.json"));
        Provisions component = DaggerContractLoadTestComponents_HandBuiltContractLoadComponent.factory()
                .create(vertx, config);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);

        // Then: startup fails naming the mount path and the reason
        String message =
                assertRefused("(5) a hand-built mount", outcome, ContractLoadModules.RELATIVE_SERVERS_RESOURCE);
        assertTrue(
                message.contains("OpenAPI contract of JAX-RS mount '" + ContractLoadModules.HAND_BUILT_MOUNT_PATH + "'"
                        + CANNOT_BE_LOADED + SERVERS_REASON),
                "names the hand-built mount's path, the strategy, and the reason: " + message);
    }

    @Test
    @DisplayName("(6) an application inheriting an unloadable global contract fails startup naming jaxrs.openapiPath")
    void unloadableInheritedGlobalContractNamesTheGlobalSetting(Vertx vertx) throws Exception {
        // Given: openapi-contract and an unloadable global contract public inherits
        Path location = tempDir.resolve(MARKER + "-relative-servers.json");
        Files.writeString(location, RELATIVE_SERVERS_CONTRACT);
        JsonObject config = config(OPENAPI_CONTRACT, location.toString());

        // When: it is deployed
        Outcome outcome = deploy(vertx, publicComponent(vertx, config));

        // Then: startup fails naming public and the global setting
        String message = assertRefused("(6) an inherited global contract", outcome, location.toString());
        assertTrue(
                message.contains("OpenAPI contract of application '" + PublicApi.NAME + "' (jaxrs.openapiPath)"
                        + CANNOT_BE_LOADED + SERVERS_REASON),
                "names public, the global setting, and the reason: " + message);
    }

    @Test
    @DisplayName(
            "(6) an application whose declaration names an unloadable contract fails startup naming the annotation")
    void unloadableDeclaredContractNamesTheAnnotationSetting(Vertx vertx) throws Exception {
        // Given: openapi-contract, a loadable global contract, and an application declaring a contract with
        // a relative servers url
        JsonObject config = config(OPENAPI_CONTRACT, publicContract("global-contract.json"));
        Provisions component = DaggerContractLoadTestComponents_AnnotatedContractLoadComponent.factory()
                .create(vertx, config);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);

        // Then: startup fails naming the application and its declaration's setting
        String message =
                assertRefused("(6) a declared contract", outcome, ContractLoadModules.RELATIVE_SERVERS_RESOURCE);
        assertTrue(
                message.contains("OpenAPI contract of application '" + AnnotatedContractApi.NAME
                        + "' (the @RestApplication annotation's openapiPath)" + CANNOT_BE_LOADED + SERVERS_REASON),
                "names the application, the annotation setting, and the reason: " + message);
    }

    @Test
    @DisplayName("(12) two verticle instances fail startup with the same single failure as one instance")
    void multipleInstancesFailWithTheSameFailure(Vertx vertx) throws Exception {
        // Given: openapi-contract and public's configured contract with a relative servers url
        Path location = tempDir.resolve(MARKER + "-relative-servers.json");
        Files.writeString(location, RELATIVE_SERVERS_CONTRACT);
        JsonObject config = withApplicationContract(
                config(OPENAPI_CONTRACT, publicContract("global-contract.json")), location.toString());

        // When: it is deployed with one instance, then with two
        Outcome single = deploy(vertx, publicComponent(vertx, config.copy()));
        String singleMessage = assertRefused("(12) one instance", single, location.toString());
        Provisions twoInstances = publicComponent(vertx, config.copy());
        Outcome multiple =
                StartupDeployments.deploy(vertx, twoInstances::httpVerticle, new DeploymentOptions().setInstances(2));
        try {
            // Then: both fail with the same message, which gives the reason once
            String multipleMessage = assertRefused("(12) two instances", multiple, location.toString());
            assertAll(
                    "two instances: " + multipleMessage,
                    () -> assertEquals(singleMessage, multipleMessage, "the same message as one instance"),
                    () -> assertEquals(
                            1, occurrences(multipleMessage, SERVERS_REASON), "the reason is given exactly once"),
                    () -> assertEquals(
                            1,
                            occurrences(multipleMessage, PUBLIC_CONFIGURED),
                            "the application is identified exactly once"));
        } finally {
            StartupDeployments.undeploy(vertx, multiple);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A loadable contract is loaded once and reused
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(7) a loadable contract starts, and requests keep validating against the contract loaded at startup")
    void loadableContractIsLoadedOnceAndReused(Vertx vertx) throws Exception {
        // Given: openapi-contract and public's loadable contract configured as a temporary file
        Path location = tempDir.resolve("public-contract.json");
        Files.writeString(location, publicContractText());
        JsonObject config = withApplicationContract(
                config(OPENAPI_CONTRACT, publicContract("global-contract.json")), location.toString());

        // When: it is deployed and sent a conforming and a non-conforming request, the file is overwritten
        // with text that is no contract, and the same requests are sent again
        Outcome outcome = deploy(vertx, publicComponent(vertx, config));
        try {
            assertDeployed("(7) a loadable contract", outcome);
            int conformingBefore = postItem(outcome.port(), CONFORMING_ITEM);
            int nonConformingBefore = postItem(outcome.port(), ITEM_WITHOUT_NAME);
            Files.writeString(location, MARKER + " is no longer a contract {");
            int conformingAfter = postItem(outcome.port(), CONFORMING_ITEM);
            int nonConformingAfter = postItem(outcome.port(), ITEM_WITHOUT_NAME);

            // Then: the requests validate before and after alike, against the contract loaded at startup
            assertAll(
                    "(7) statuses before and after the overwrite",
                    () -> assertTrue(
                            conformingBefore >= 200 && conformingBefore < 300,
                            "the conforming request before: " + conformingBefore),
                    () -> assertEquals(400, nonConformingBefore, "the request without name before"),
                    () -> assertEquals(conformingBefore, conformingAfter, "the conforming request after"),
                    () -> assertEquals(400, nonConformingAfter, "the request without name after"));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unchanged startups
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"web-validation", "none"})
    @DisplayName("(8) another strategy starts with the contract module present and an unloadable contract configured")
    void otherStrategyStartsWithAnUnloadableContract(String strategy, Vertx vertx) throws Exception {
        // Given: the strategy, and an unloadable global and configured contract
        Path location = tempDir.resolve(MARKER + "-relative-servers.json");
        Files.writeString(location, RELATIVE_SERVERS_CONTRACT);
        JsonObject config = withApplicationContract(config(strategy, location.toString()), location.toString());

        // When: it is deployed and sent a conforming request
        Outcome outcome = deploy(vertx, publicComponent(vertx, config));
        try {
            assertDeployed("(8) " + strategy, outcome);
            int conforming = postItem(outcome.port(), CONFORMING_ITEM);

            // Then: it serves the request
            assertTrue(conforming >= 200 && conforming < 300, strategy + ": the conforming request: " + conforming);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    @Test
    @DisplayName("(9) a missing global contract no mount binds does not fail startup")
    void missingUnboundGlobalContractStarts(Vertx vertx) throws Exception {
        // Given: openapi-contract, a missing global contract, and public's own loadable contract
        Path globalLocation = tempDir.resolve(MARKER + "-missing.json");
        JsonObject config = withApplicationContract(
                config(OPENAPI_CONTRACT, globalLocation.toString()), publicContract("public-contract.json"));

        // When: it is deployed and sent a conforming and a non-conforming request
        Outcome outcome = deploy(vertx, publicComponent(vertx, config));
        try {
            assertDeployed("(9) a missing unbound global contract", outcome);
            int conforming = postItem(outcome.port(), CONFORMING_ITEM);
            int nonConforming = postItem(outcome.port(), ITEM_WITHOUT_NAME);

            // Then: public validates against its own contract
            assertAll(
                    () -> assertTrue(conforming >= 200 && conforming < 300, "the conforming request: " + conforming),
                    () -> assertEquals(400, nonConforming, "the request without name"));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    @Test
    @DisplayName("(11) an empty mount under openapi-contract binds no contract and starts")
    void emptyMountStarts(Vertx vertx) throws Exception {
        // Given: openapi-contract, an unloadable global contract, and an application with no resource
        Path location = tempDir.resolve(MARKER + "-relative-servers.json");
        Files.writeString(location, RELATIVE_SERVERS_CONTRACT);
        JsonObject config = config(OPENAPI_CONTRACT, location.toString());
        Provisions component = DaggerContractLoadTestComponents_EmptyContractLoadComponent.factory()
                .create(vertx, config);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            // Then: it starts
            assertDeployed("(11) an empty mount", outcome);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts the deployment failed before any server listened, with a cause-free {@link
     * RestConfigurationException} whose message and whole cause and suppressed chain carry neither the
     * marker nor any of the forbidden values, and returns its message.
     */
    private static String assertRefused(String label, Outcome outcome, String... forbidden) {
        Throwable failure = outcome.failure();
        assertAll(
                label + ": startup is refused",
                () -> assertNotNull(failure, "the deployment succeeded"),
                () -> assertNull(outcome.port(), "no server listens"));
        RestConfigurationException refusal = assertInstanceOf(
                RestConfigurationException.class,
                failure,
                () -> label + ": the failure's class: " + failure.getClass().getName() + ": " + failure);
        String message = refusal.getMessage();
        assertNotNull(message, label + ": the failure has a message");
        String chain = chainText(failure);
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertNull(refusal.getCause(), "carries no cause"));
        checks.add(() -> assertFalse(chain.contains(MARKER), "echoes no contract value: " + chain));
        for (String value : forbidden) {
            checks.add(() -> assertFalse(message.contains(value), "echoes no configured value: " + value));
        }
        assertAll(label + ": the failure: " + message, checks.stream());
        return message;
    }

    /** Returns the string forms of a throwable, its causes, and their suppressed exceptions. */
    private static String chainText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        List<Throwable> pending = new ArrayList<>(List.of(failure));
        while (!pending.isEmpty()) {
            Throwable next = pending.remove(0);
            if (next == null || seen.put(next, Boolean.TRUE) != null) {
                continue;
            }
            text.append(next).append('\n');
            pending.add(next.getCause());
            pending.addAll(List.of(next.getSuppressed()));
        }
        return text.toString();
    }

    private static void assertDeployed(String label, Outcome outcome) {
        Throwable failure = outcome.failure();
        assertNull(failure, () -> label + ": the deployment failed: " + failure);
        assertNotNull(outcome.port(), label + ": a port is published");
    }

    private static Outcome deploy(Vertx vertx, Provisions component) throws Exception {
        return StartupDeployments.deploy(vertx, component::httpVerticle);
    }

    private static Provisions publicComponent(Vertx vertx, JsonObject config) {
        return DaggerContractLoadTestComponents_PublicContractLoadComponent.factory()
                .create(vertx, config);
    }

    /** Posts a {@code createItem} body and returns the response status. */
    private int postItem(int port, JsonObject item) throws Exception {
        Future<HttpResponse<Buffer>> response =
                client.post(port, LOOPBACK, ITEMS_URI).sendJsonObject(item.copy());
        return Futures.await(response, StartupDeployments.BOUND).statusCode();
    }

    /** Writes the loadable public contract to the temporary directory and returns its absolute location. */
    private String publicContract(String fileName) throws Exception {
        Path location = tempDir.resolve(fileName);
        Files.writeString(location, publicContractText());
        return location.toString();
    }

    /** Reads the loadable, servers-free contract describing {@code public}'s operations. */
    private static String publicContractText() throws Exception {
        try (InputStream in =
                OpenApiContractLoadStartupIT.class.getClassLoader().getResourceAsStream(PUBLIC_CONTRACT_RESOURCE)) {
            assertNotNull(in, "the test resource " + PUBLIC_CONTRACT_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Returns the loopback configuration with the given strategy and global contract location. */
    private static JsonObject config(String strategy, String globalLocation) {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", strategy).put("openapiPath", globalLocation);
        return config;
    }

    /** Sets {@code jaxrs.applications.public.openapiPath}. */
    private static JsonObject withApplicationContract(JsonObject config, String location) {
        config.getJsonObject("jaxrs")
                .put(
                        "applications",
                        new JsonObject().put(PublicApi.NAME, new JsonObject().put("openapiPath", location)));
        return config;
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }
}
