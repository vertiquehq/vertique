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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.security.catalog.WarningCapture;
import dev.vertique.rest.openapi.docs.fixture.security.orders.OrderResource;
import dev.vertique.rest.openapi.docs.fixture.security.orders.OrderSchemeHandlers;
import dev.vertique.rest.openapi.docs.fixture.security.orders.OrdersApi;
import dev.vertique.rest.openapi.docs.fixture.security.vault.UndescribedVaultHandler;
import dev.vertique.rest.openapi.docs.fixture.security.vault.VaultApi;
import dev.vertique.rest.openapi.docs.fixture.security.vault.VaultResource;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deploys documented applications whose operations declare security requirements and checks what
 * their public documents publish about security, and when a scheme they reference cannot be
 * described.
 *
 * <p>The application {@code orders} is composed with the real JWT authentication module (scheme
 * {@code bearerAuth}) and three fixture handlers: {@code apiKeyAuth}, describing an API key header;
 * {@code hiddenOnly}, describing nothing and referenced only by a hidden operation; and {@code
 * unused}, described but never referenced. Its document publishes one Security Requirement Object
 * per declared requirement set, in declaration order, as the last member of each secured Operation
 * Object, and exactly the referenced schemes, from their handlers' descriptions, sorted by name.
 * Roles, hidden operations, and unreferenced handlers leave no trace. Deploying it logs one
 * restriction warning listing its four restricted operations in document order, asks the referenced
 * {@code apiKeyAuth} handler for its description once, and never asks {@code unused}; the warnings
 * are captured around the deployment only. This deployment is shared by the class and released in
 * {@code AfterAll} on every path.
 *
 * <p>The application {@code vault} references {@code vaultAuth}, whose counting handler describes
 * nothing. With its document enabled the deployment fails before listening, naming the document,
 * the mount, the operation, and the scheme, and echoing no configuration value, after asking the
 * handler for its description exactly once and without logging a warning of the document; with the
 * document disabled it starts, the scheme still rejects an unauthenticated request, and the handler's
 * description is never requested. Each case is deployed through {@code deploy} and undeployed before
 * its assertions run. Expected values are fixed literals.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class DocumentSecurityIT {

    private static final String HOST = "127.0.0.1";

    /** The JSON form's file name under a document's path. */
    private static final String JSON_FORM = "openapi.json";

    /** The YAML form's file name under a document's path. */
    private static final String YAML_FORM = "openapi.yaml";

    /** The published Security Scheme Object of {@code apiKeyAuth}, in field order. */
    private static final String API_KEY_SCHEME = "{\"type\":\"apiKey\",\"name\":\"X-Api-Key\",\"in\":\"header\"}";

    /** The published Security Scheme Object of {@code bearerAuth}, in field order. */
    private static final String BEARER_SCHEME = "{\"type\":\"http\",\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\"}";

    /** The {@code security} of {@code listOrders}. */
    private static final String LIST_SECURITY = "[{\"bearerAuth\":[]}]";

    /** The {@code security} of {@code readOrder}. */
    private static final String READ_SECURITY = "[{\"bearerAuth\":[\"orders.read\"]}]";

    /** The {@code security} of {@code searchOrders}: two alternatives in declaration order. */
    private static final String SEARCH_SECURITY = "[{\"bearerAuth\":[]},{\"apiKeyAuth\":[]}]";

    /** The {@code security} of {@code adminOrders}: the requirement only, never the role. */
    private static final String ADMIN_SECURITY = "[{\"bearerAuth\":[]}]";

    /** What no form of the {@code orders} document may contain anywhere. */
    private static final List<String> ORDERS_ABSENT = List.of("order-admin", "hiddenOnly", "unused");

    /** The configured {@code info.title} of the {@code vault} document. */
    private static final String VAULT_TITLE = "Strongroom Zx41";

    /** The configured {@code info.version} of the {@code vault} document. */
    private static final String VAULT_VERSION = "41.0-Zx";

    /** The configured {@code info.description} of the {@code vault} document. */
    private static final String VAULT_DESCRIPTION = "Kept behind Zx41 doors";

    /** The configured {@code serverUrl} of the {@code vault} document. */
    private static final String VAULT_SERVER_URL = "https://strongroom-zx41.invalid/base";

    /** The sentinel every configured {@code vault} value carries, compared ignoring case. */
    private static final String VAULT_SENTINEL = "zx41";

    /** The documentation module's warning logger. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The fragment that marks the restriction warning among a document's warnings. */
    private static final String RESTRICT_FRAGMENT = "restrict callers";

    /** The fragment after which the restriction warning lists its entries. */
    private static final String LIST_FRAGMENT = "served without authentication: ";

    /** The separator between two listed entries. */
    private static final String ENTRY_SEPARATOR = ", ";

    /**
     * The restricted operations the {@code orders} restriction warning lists, in document order:
     * paths in natural order, then methods; the open {@code /orders/ping} and the hidden operation
     * are not among them.
     */
    private static final List<String> ORDERS_RESTRICTED_ENTRIES = List.of(
            "GET /orders (listOrders)",
            "GET /orders/admin (adminOrders)",
            "GET /orders/read (readOrder)",
            "GET /orders/search (searchOrders)");

    /** What the {@code orders} restriction warning never names: the hidden and open operations, role, scope. */
    private static final List<String> ORDERS_ABSENT_FROM_WARNING =
            List.of("internalOrders", "ping", "order-admin", "orders.read");

    private static Vertx vertx;
    private static WebClient client;
    private static Outcome orders;

    /** The documentation warnings logged while {@code orders} was deployed. */
    private static List<String> ordersWarnings = List.of();

    @BeforeAll
    static void deployOrders(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
        JsonObject config = ordersConfig();
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        OrderSchemeHandlers.resetDescriptionCalls();
        WarningCapture capture = WarningCapture.attach(WARNINGS_LOGGER);
        try {
            orders = StartupDeployments.deploy(
                    vertx, () -> DaggerDocumentSecurityTestComponents_OrdersComponent.factory()
                            .create(vertx, config)
                            .httpVerticle());
            ordersWarnings = capture.warnings();
        } finally {
            capture.detach();
        }
    }

    @AfterAll
    static void undeployAndCloseTheClient() throws Exception {
        try {
            StartupDeployments.undeploy(vertx, orders);
        } finally {
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * The {@code orders} document publishes each secured operation's requirement sets and exactly
     * the referenced schemes from their handlers' descriptions, without roles, hidden operations, or
     * unreferenced handlers, in two forms of one valid OpenAPI 3.1 document.
     */
    @Test
    @DisplayName("Referenced schemes and requirement sets are published; roles and hidden or unused schemes are not")
    void referencedSchemesPublishedFromHandlerDescriptions() throws Exception {
        // Given: orders deployed with the JWT handler and the fixture handlers, its document enabled
        assertNull(orders.failure(), () -> "the orders deployment failed: " + orders.failure());
        assertNotNull(orders.port(), "the orders deployment published a port");

        // When: both forms of the document are fetched
        String json = fetchDocument(orders.port(), OrdersApi.NAME, JSON_FORM);
        String yaml = fetchDocument(orders.port(), OrdersApi.NAME, YAML_FORM);
        JsonObject document = new JsonObject(json);

        // Then: each secured operation lists its requirement sets as its last member; no other security
        assertAll(
                "the security of the published operations",
                () -> assertSecurity(document, OrderResource.ROUTE, OrderResource.LIST_ORDERS, LIST_SECURITY),
                () -> assertSecurity(
                        document,
                        OrderResource.ROUTE + OrderResource.READ_PATH,
                        OrderResource.READ_ORDER,
                        READ_SECURITY),
                () -> assertSecurity(
                        document,
                        OrderResource.ROUTE + OrderResource.SEARCH_PATH,
                        OrderResource.SEARCH_ORDERS,
                        SEARCH_SECURITY),
                () -> assertSecurity(
                        document,
                        OrderResource.ROUTE + OrderResource.ADMIN_PATH,
                        OrderResource.ADMIN_ORDERS,
                        ADMIN_SECURITY),
                () -> {
                    JsonObject ping =
                            operation(document, OrderResource.ROUTE + OrderResource.PING_PATH, OrderResource.PING);
                    assertFalse(ping.containsKey("security"), () -> "ping has no security: " + ping.encode());
                },
                () -> assertFalse(
                        document.containsKey("security"),
                        () -> "the root has no security: " + document.getValue("security")));

        // Then: exactly the referenced schemes, sorted by name, each from its handler's description
        JsonObject components = document.getJsonObject("components");
        assertNotNull(components, () -> "the document has components; root keys: " + document.fieldNames());
        JsonObject schemes = components.getJsonObject("securitySchemes");
        assertNotNull(schemes, () -> "the document has security schemes: " + components.encode());
        assertEquals(
                List.of(OrderSchemeHandlers.API_KEY_AUTH, OrderSchemeHandlers.BEARER_AUTH),
                List.copyOf(schemes.fieldNames()),
                "the published security schemes, in order");
        assertAll(
                "the published Security Scheme Objects",
                () -> assertEquals(
                        API_KEY_SCHEME, scheme(schemes, OrderSchemeHandlers.API_KEY_AUTH), "the scheme apiKeyAuth"),
                () -> assertEquals(
                        BEARER_SCHEME, scheme(schemes, OrderSchemeHandlers.BEARER_AUTH), "the scheme bearerAuth"));

        // Then: no role, hidden-only scheme, or unreferenced handler appears in either form
        stringsAbsent(json, "the JSON form", ORDERS_ABSENT);
        stringsAbsent(yaml, "the YAML form", ORDERS_ABSENT);

        // Then: deploying logged exactly one restriction warning for orders, listing its four restricted
        // operations in document order and naming no hidden or open operation, role, or scope
        List<String> restrictions = ordersWarnings.stream()
                .filter(message -> message.startsWith("apidocs.documents." + OrdersApi.NAME)
                        && message.contains(RESTRICT_FRAGMENT))
                .toList();
        assertEquals(1, restrictions.size(), () -> "the orders restriction warnings: " + ordersWarnings);
        String warning = restrictions.get(0);
        assertEquals(ORDERS_RESTRICTED_ENTRIES, listedEntries(warning), () -> "the listed entries: " + warning);
        assertAll(
                "the orders restriction warning names none of " + ORDERS_ABSENT_FROM_WARNING,
                ORDERS_ABSENT_FROM_WARNING.stream()
                        .<Executable>map(text -> () -> assertFalse(
                                warning.contains(text), () -> "the warning names '" + text + "': " + warning)));

        // Then: after deploying and fetching both forms, the referenced apiKeyAuth's handler was asked
        // for its description exactly once, and the unreferenced handler never
        assertAll(
                "the description calls of the fixture handlers",
                () -> assertEquals(
                        1,
                        OrderSchemeHandlers.descriptionCalls(OrderSchemeHandlers.API_KEY_AUTH),
                        "the description calls of apiKeyAuth"),
                () -> assertEquals(
                        0,
                        OrderSchemeHandlers.descriptionCalls(OrderSchemeHandlers.UNUSED),
                        "the description calls of unused"));

        // Then: both forms parse to the same tree, and the document is valid OpenAPI 3.1
        JsonNode jsonTree = new ObjectMapper().readTree(json);
        JsonNode yamlTree = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        assertEquals(jsonTree, yamlTree, "the YAML form parses to the JSON form's tree");
        OpenApi31Toolchain.Verdict toolchain = OpenApi31Toolchain.validate(vertx, document.copy());
        assertTrue(toolchain.valid(), () -> "Invalid OpenAPI 3.1 document: " + toolchain.problems());
    }

    static Stream<Arguments> vaultCases() {
        return Stream.of(
                Arguments.of(Named.of("(a) document enabled", true)),
                Arguments.of(Named.of("(b) document disabled", false)));
    }

    /**
     * A referenced scheme whose handler describes nothing fails an enabled document's deployment
     * before listening, naming the scheme and echoing no configuration value; with the document
     * disabled the deployment starts, the scheme still guards the route, and the description is never
     * requested.
     *
     * @param documentEnabled whether the {@code vault} document is enabled
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("vaultCases")
    @DisplayName("An undescribed referenced scheme fails an enabled document; a disabled one leaves startup untouched")
    void undescribedReferencedSchemeFailsPublication(boolean documentEnabled) throws Exception {
        // Given: vault, whose operation requires the undescribed vaultAuth, and the case's configuration
        UndescribedVaultHandler.resetDescriptionCalls();
        JsonObject config = vaultConfig();
        if (!documentEnabled) {
            DocsConfigs.withDocumentEnabled(config, VaultApi.NAME, false);
        }

        // When: it is deployed while the warning logger is captured and, when it deploys with the
        // document disabled, the route is requested
        Outcome outcome;
        List<String> vaultWarnings;
        WarningCapture capture = WarningCapture.attach(WARNINGS_LOGGER);
        try {
            outcome = deploy(config);
            vaultWarnings = capture.warnings().stream()
                    .filter(message -> message.startsWith("apidocs.documents." + VaultApi.NAME))
                    .toList();
        } finally {
            capture.detach();
        }
        Integer status = null;
        String body = null;
        try {
            if (!documentEnabled && outcome.deployed() && outcome.port() != null) {
                HttpResponse<Buffer> answer = Futures.await(
                        client.get(outcome.port(), HOST, VaultApi.PATH + VaultResource.ROUTE)
                                .send(),
                        StartupDeployments.BOUND);
                status = answer.statusCode();
                body = answer.bodyAsString();
            }
        } finally {
            StartupDeployments.undeployAndClear(vertx, outcome);
        }
        int descriptionCalls = UndescribedVaultHandler.descriptionCalls();

        // Then
        if (documentEnabled) {
            assertRefused(outcome);
            assertAll(
                    "the refused vault deployment's description calls and warnings",
                    () -> assertEquals(1, descriptionCalls, "the description of vaultAuth is requested exactly once"),
                    () -> assertEquals(List.of(), vaultWarnings, "no vault document warning is logged"));
        } else {
            assertStartedAndGuarded(outcome, status, body, descriptionCalls);
        }
    }

    // --- Helpers ---

    /**
     * The {@code orders} configuration: loopback, the {@code none} strategy, the document's {@code
     * info}, and the JWT scheme name.
     */
    private static JsonObject ordersConfig() {
        JsonObject config = DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), OrdersApi.NAME, "Orders", "1");
        config.put("jwt", new JsonObject().put("schemeName", OrderSchemeHandlers.BEARER_AUTH));
        return config;
    }

    /**
     * The {@code vault} configuration: loopback, the {@code none} strategy, and the document's {@code
     * info} and {@code serverUrl}, every value carrying the sentinel.
     */
    private static JsonObject vaultConfig() {
        JsonObject config = DocsConfigs.loopback();
        JsonObject entry = DocsConfigs.document(config, VaultApi.NAME);
        entry.put(
                "info",
                new JsonObject()
                        .put("title", VAULT_TITLE)
                        .put("version", VAULT_VERSION)
                        .put("description", VAULT_DESCRIPTION));
        entry.put("serverUrl", VAULT_SERVER_URL);
        return config;
    }

    /**
     * Checks one operation's {@code security} against its expected literal and that it is the
     * operation's last member.
     */
    private static void assertSecurity(JsonObject document, String path, String operationId, String expected) {
        JsonObject operation = operation(document, path, operationId);
        JsonArray security = operation.getJsonArray("security");
        assertNotNull(security, () -> operationId + " has security: " + operation.encode());
        assertEquals(expected, security.encode(), "the security of " + operationId);
        List<String> members = List.copyOf(operation.fieldNames());
        assertEquals(
                "security",
                members.get(members.size() - 1),
                () -> "security is the last member of " + operationId + "; members: " + members);
    }

    /** Returns one published Security Scheme Object in its compact serialization, field order kept. */
    private static String scheme(JsonObject schemes, String name) {
        JsonObject scheme = schemes.getJsonObject(name);
        assertNotNull(scheme, () -> "the scheme " + name + " is published: " + schemes.encode());
        return scheme.encode();
    }

    /** Returns the {@code get} operation of a path, checking its operation id. */
    private static JsonObject operation(JsonObject document, String path, String operationId) {
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, () -> "the document has paths: " + document.encode());
        JsonObject item = paths.getJsonObject(path);
        assertNotNull(item, () -> "the document has path " + path + "; paths: " + paths.fieldNames());
        JsonObject operation = item.getJsonObject("get");
        assertNotNull(operation, () -> "path " + path + " has get: " + item.encode());
        assertEquals(operationId, operation.getString("operationId"), "the operation id at " + path);
        return operation;
    }

    /** Returns the entries a restriction warning lists: its suffix after the list fragment, split. */
    private static List<String> listedEntries(String warning) {
        int at = warning.indexOf(LIST_FRAGMENT);
        assertTrue(at >= 0, () -> "the warning has no entry list after '" + LIST_FRAGMENT + "': " + warning);
        return List.of(warning.substring(at + LIST_FRAGMENT.length()).split(ENTRY_SEPARATOR, -1));
    }

    /** Checks that none of the strings occurs anywhere in a document's raw text. */
    private static void stringsAbsent(String document, String form, List<String> strings) {
        assertAll(
                form + " contains none of " + strings,
                strings.stream()
                        .<Executable>map(text -> () -> assertFalse(
                                document.contains(text), () -> form + " contains '" + text + "': " + document)));
    }

    /** The enabled document refuses the deployment before listening, naming what failed and no value. */
    private static void assertRefused(Outcome outcome) {
        Throwable failure = outcome.failure();
        assertNotNull(failure, "the deployment with the vault document enabled fails");
        String message = String.valueOf(failure.getMessage());
        String prefix = "apidocs.documents." + VaultApi.NAME + ": Application '" + VaultApi.NAME + "' (declared by "
                + VaultApi.class.getName() + ") at mount '" + VaultApi.MOUNT + "'";
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertNull(outcome.port(), "no port is published"));
        checks.add(() -> assertTrue(message.startsWith(prefix), "the message starts with " + prefix));
        for (String fragment : List.of(
                "apidocs.documents." + VaultApi.NAME,
                "'" + VaultApi.MOUNT + "'",
                "operation '" + VaultResource.READ_VAULT + "'",
                "security scheme '" + UndescribedVaultHandler.VAULT_AUTH + "'",
                "provides no OpenAPI description")) {
            checks.add(() -> assertTrue(message.contains(fragment), "the message contains " + fragment));
        }
        for (String value : Arrays.asList(VAULT_TITLE, VAULT_VERSION, VAULT_DESCRIPTION, VAULT_SERVER_URL)) {
            checks.add(() -> assertFalse(message.contains(value), "the message holds no configured " + value));
        }
        checks.add(() -> assertFalse(
                message.toLowerCase(Locale.ROOT).contains(VAULT_SENTINEL),
                "the message holds no configured value's sentinel " + VAULT_SENTINEL));
        assertAll("the refusal; message: " + message, checks);
    }

    /**
     * Without an enabled document the deployment starts, the scheme rejects the unauthenticated
     * request, and no description was requested.
     */
    private static void assertStartedAndGuarded(Outcome outcome, Integer status, String body, int descriptionCalls) {
        assertAll(
                "the deployment with the vault document disabled",
                () -> assertNull(outcome.failure(), () -> "the deployment succeeds: " + outcome.failure()),
                () -> assertNotNull(outcome.port(), "a port is published"));
        assertAll(
                "the guarded route and the handler",
                () -> assertEquals(401, status, () -> "GET without credentials answers 401: " + body),
                () -> assertEquals(0, descriptionCalls, "the handler's description is never requested"));
    }

    /** Fetches one form of a public document; it must answer {@code 200}. */
    private static String fetchDocument(int port, String name, String form) throws Exception {
        String path = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + name + "/" + form;
        HttpResponse<Buffer> response =
                Futures.await(client.get(port, HOST, path).send(), StartupDeployments.BOUND);
        assertEquals(200, response.statusCode(), () -> "GET " + path + ": " + response.bodyAsString());
        assertNotNull(response.body(), () -> "GET " + path + " answers with a body");
        return response.bodyAsString();
    }

    /**
     * Deploys a fresh {@code vault} component on the class's Vert.x instance; the component is
     * created inside the verticle supplier, so a failure while provisioning it fails the deployment.
     */
    private static Outcome deploy(JsonObject config) throws Exception {
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        return StartupDeployments.deploy(vertx, () -> DaggerDocumentSecurityTestComponents_VaultComponent.factory()
                .create(config)
                .httpVerticle());
    }
}
