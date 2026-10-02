// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.openapi.docs.ServedConformanceTestComponents.Provisions;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.DocumentRequests.Answer;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.StoreLogCapture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.contract.Operation;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Integration proof that, in compositions mixing served and generated documents, under {@code
 * web-validation} and under {@code openapi-contract}, each served document is its contract file's
 * parsed tree in both forms, loads as a contract in vertx-openapi, is stored once and compared once
 * across compositions with identical bytes and entity tags, and, under {@code openapi-contract}, is
 * the contract the strategy enforces; the other document is generated and conforms to OpenAPI 3.1.
 *
 * <p>Two components are used (see {@link ServedConformanceTestComponents}):
 *
 * <ul>
 *   <li><em>web-validation</em>: global {@code jaxrs.openapiPath} {@value #GLOBAL_CONTRACT}; {@code
 *       partner}, whose declaring interface names {@value #PARTNER_CONTRACT} (OpenAPI 3.1.0, YAML), and
 *       {@code catalog}, which names no contract and is generated;
 *   <li><em>openapi-contract</em>: {@code partner} as above and {@code orders}, whose contract
 *       {@value #ORDERS_CONTRACT} (OpenAPI 3.0.3, JSON) is named by {@code
 *       jaxrs.applications.orders.openapiPath}.
 * </ul>
 *
 * <p>Each component runs in two deployment modes: its {@code HttpVerticle} supplier deployed twice,
 * one deployment after the other, each with one instance on its own port; and a fresh component
 * deployed once with two instances on one port, read over separate connections. No assertion depends
 * on which instance answered.
 *
 * <p>The global contract describes {@code partner}'s two visible operations at mount-relative paths
 * under its own title. Serving it in {@code partner}'s place would therefore pass the served-contract
 * checks, and only the tree comparison tells the two apart. No contract carries {@code servers}:
 * vertx-openapi rejects a relative server URL. Each served document logs a {@code servers} warning,
 * which this proof does not assert.
 *
 * <p>Expected trees are the contract files parsed by their extension, loaded once by name from the
 * test classpath. Every tree, source or served, is decoded through the same path (JSON text into a
 * Vert.x {@link JsonObject}) so equal documents compare equal. Expected operation ids, statuses, and
 * log counts are hand-written literals.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ServedContractConformanceIT {

    private static final String GLOBAL_CONTRACT = "contracts/conformance-global-openapi.json";
    private static final String PARTNER_CONTRACT = "contracts/conformance-partner-openapi.yaml";
    private static final String ORDERS_CONTRACT = "contracts/conformance-orders-openapi.json";

    private static final String PARTNER = "partner";
    private static final String ORDERS = "orders";
    private static final String CATALOG = "catalog";

    private static final String PARTNER_MOUNT = "/api/partner/*";
    private static final String ORDERS_MOUNT = "/api/orders/*";
    private static final String CATALOG_MOUNT = "/api/catalog/*";

    /** The orders resource of {@code partner}; {@code POST} creates an order. */
    private static final String PARTNER_ORDERS_URI = "/api/partner/orders";

    /** The validation-disclosure member only a generated document carries. */
    private static final String DISCLOSURE_MEMBER = "x-vertique-validation";

    /** What the stored line of a served document names as its source. */
    private static final String SERVED_SOURCE = "(source: served contract)";

    /** What the stored line of a generated document names as its source. */
    private static final String GENERATED_SOURCE = "(source: generated)";

    /** The number of requests per form over separate connections to a two-instance deployment. */
    private static final int REQUESTS_PER_FORM = 4;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The two components. */
    private enum Strategy {
        WEB_VALIDATION("web-validation"),
        OPENAPI_CONTRACT("openapi-contract");

        private final String id;

        Strategy(String id) {
            this.id = id;
        }
    }

    /** The two deployment modes. */
    private enum Mode {
        /** The same component's supplier deployed twice, one deployment after the other, one instance each. */
        SEQUENTIAL,
        /** A fresh component deployed once with two instances. */
        TWO_INSTANCES
    }

    /** The document's two forms. */
    private enum Form {
        JSON("openapi.json"),
        YAML("openapi.yaml");

        private final String file;

        Form(String file) {
            this.file = file;
        }

        String url(String document) {
            return "/apidocs/" + document + "/" + file;
        }
    }

    /**
     * One row of the table: a document of one component in one deployment mode.
     *
     * @param strategy the component, named by the strategy its configuration selects
     * @param mode the deployment mode
     * @param document the document (application) name
     * @param mountPath the application's mount path, as the store's log lines name it
     * @param servedContract the contract file the document is served from, or {@code null} when it is
     *     generated
     * @param operationIds the mount's routed, non-hidden operation ids, for a served document
     */
    private record Row(
            Strategy strategy,
            Mode mode,
            String document,
            String mountPath,
            @Nullable String servedContract,
            @Nullable Set<String> operationIds) {

        String label() {
            return strategy.id + " | " + mode + " | " + document;
        }
    }

    /** One deployment group: the rows of one component in one mode. */
    private record Group(Strategy strategy, Mode mode) {}

    private static final Set<String> PARTNER_OPERATIONS = Set.of("listOrders", "createOrder");
    private static final Set<String> ORDERS_OPERATIONS = Set.of("listItems");

    /** The (component, deployment mode, document) table. */
    private static final List<Row> TABLE = List.of(
            new Row(
                    Strategy.WEB_VALIDATION,
                    Mode.SEQUENTIAL,
                    PARTNER,
                    PARTNER_MOUNT,
                    PARTNER_CONTRACT,
                    PARTNER_OPERATIONS),
            new Row(Strategy.WEB_VALIDATION, Mode.SEQUENTIAL, CATALOG, CATALOG_MOUNT, null, null),
            new Row(
                    Strategy.WEB_VALIDATION,
                    Mode.TWO_INSTANCES,
                    PARTNER,
                    PARTNER_MOUNT,
                    PARTNER_CONTRACT,
                    PARTNER_OPERATIONS),
            new Row(Strategy.WEB_VALIDATION, Mode.TWO_INSTANCES, CATALOG, CATALOG_MOUNT, null, null),
            new Row(
                    Strategy.OPENAPI_CONTRACT,
                    Mode.SEQUENTIAL,
                    PARTNER,
                    PARTNER_MOUNT,
                    PARTNER_CONTRACT,
                    PARTNER_OPERATIONS),
            new Row(
                    Strategy.OPENAPI_CONTRACT,
                    Mode.SEQUENTIAL,
                    ORDERS,
                    ORDERS_MOUNT,
                    ORDERS_CONTRACT,
                    ORDERS_OPERATIONS),
            new Row(
                    Strategy.OPENAPI_CONTRACT,
                    Mode.TWO_INSTANCES,
                    PARTNER,
                    PARTNER_MOUNT,
                    PARTNER_CONTRACT,
                    PARTNER_OPERATIONS),
            new Row(
                    Strategy.OPENAPI_CONTRACT,
                    Mode.TWO_INSTANCES,
                    ORDERS,
                    ORDERS_MOUNT,
                    ORDERS_CONTRACT,
                    ORDERS_OPERATIONS));

    @Test
    @DisplayName(
            "Served contracts are their parsed files in both forms, load in vertx-openapi, are stored once, and are enforced, across instances and strategies")
    void servedContractsConformAcrossInstancesAndStrategies(Vertx vertx) throws Exception {
        // Given: the contract files, parsed once by name
        Map<String, JsonObject> sources = Map.of(
                GLOBAL_CONTRACT, sourceTree(GLOBAL_CONTRACT),
                PARTNER_CONTRACT, sourceTree(PARTNER_CONTRACT),
                ORDERS_CONTRACT, sourceTree(ORDERS_CONTRACT));
        Map<Group, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : TABLE) {
            groups.computeIfAbsent(new Group(row.strategy(), row.mode()), ignored -> new ArrayList<>())
                    .add(row);
        }

        WebClient client = DocumentRequests.separateConnectionsClient(vertx);
        List<String> deployments = new ArrayList<>();
        List<Executable> checks = new ArrayList<>();
        try {
            for (Map.Entry<Group, List<Row>> group : groups.entrySet()) {
                runGroup(vertx, client, deployments, group.getKey(), group.getValue(), sources, checks);
            }
            assertAll("served-contract conformance", checks.stream());
        } finally {
            client.close();
            Deployments.undeployAll(vertx, deployments);
        }
    }

    /**
     * Deploys one component in one mode, requests every document's forms on every port, and adds the
     * group's checks.
     */
    private static void runGroup(
            Vertx vertx,
            WebClient client,
            List<String> deployments,
            Group group,
            List<Row> rows,
            Map<String, JsonObject> sources,
            List<Executable> checks)
            throws Exception {
        try (StoreLogCapture capture = StoreLogCapture.attach()) {
            // When: the component is deployed in the group's mode
            List<Integer> ports = new ArrayList<>();
            int requestsPerPort;
            if (group.mode() == Mode.SEQUENTIAL) {
                Provisions component = component(vertx, group.strategy());
                ports.add(Deployments.deployAndReadPort(
                        vertx, component::httpVerticle, new DeploymentOptions(), deployments));
                ports.add(Deployments.deployAndReadPort(
                        vertx, component::httpVerticle, new DeploymentOptions(), deployments));
                requestsPerPort = 1;
            } else {
                Provisions component = component(vertx, group.strategy());
                ports.add(Deployments.deployAndReadPort(
                        vertx, component::httpVerticle, new DeploymentOptions().setInstances(2), deployments));
                requestsPerPort = REQUESTS_PER_FORM;
            }

            for (Row row : rows) {
                // When: each form is requested on every port
                Map<Form, List<Answer>> answers = new LinkedHashMap<>();
                for (Form form : Form.values()) {
                    List<Answer> formAnswers = new ArrayList<>();
                    for (int port : ports) {
                        for (int request = 0; request < requestsPerPort; request++) {
                            formAnswers.add(DocumentRequests.get(client, port, form.url(row.document()), null));
                        }
                    }
                    answers.put(form, formAnswers);
                }
                List<String> stored = capture.storedLines(row.document(), row.mountPath());
                List<String> compared = capture.comparisonLines(row.document(), row.mountPath());

                String label = row.label();
                for (Form form : Form.values()) {
                    checks.add(() -> assertOneRepresentation(label + " | " + form, answers.get(form)));
                }
                Answer json = answers.get(Form.JSON).get(0);
                Answer yaml = answers.get(Form.YAML).get(0);
                if (row.servedContract() != null) {
                    checks.add(() -> assertServed(vertx, row, sources, json, yaml, stored, compared));
                } else {
                    checks.add(() -> assertGenerated(row, json, yaml, stored, compared));
                }
            }

            if (group.strategy() == Strategy.OPENAPI_CONTRACT) {
                // When: an order without and with sku is posted on every port
                for (int port : ports) {
                    PostResult withoutSku = post(client, port, new JsonObject().put("quantity", 1));
                    PostResult withSku = post(client, port, new JsonObject().put("sku", "ABC-1234"));
                    String label = group.strategy().id + " | " + group.mode() + " | POST " + PARTNER_ORDERS_URI;
                    // Then: the strategy enforces the served contract, which requires sku: the body
                    // without it is refused as a missing required body field, and the body with it is
                    // accepted
                    checks.add(() -> assertMissingRequiredBodyField(label, withoutSku));
                    checks.add(() -> assertEquals(204, withSku.status(), label + ": the body with sku is accepted"));
                }
            }
        }
    }

    /** Then: every answer of one form is a 200 with one body and one entity tag. */
    private static void assertOneRepresentation(String label, List<Answer> answers) {
        for (Answer answer : answers) {
            assertEquals(
                    200,
                    answer.status(),
                    () -> label + ": status, body " + new String(answer.body(), StandardCharsets.UTF_8));
            assertNotNull(answer.etag(), label + ": an entity tag");
        }
        Set<String> bodies = answers.stream()
                .map(answer -> new String(answer.body(), StandardCharsets.UTF_8))
                .collect(Collectors.toSet());
        Set<String> tags = answers.stream().map(Answer::etag).collect(Collectors.toSet());
        assertEquals(1, bodies.size(), label + ": distinct bodies over " + answers.size() + " requests");
        assertEquals(1, tags.size(), () -> label + ": distinct entity tags " + tags);
    }

    /** Then: a served document is its contract file, loads in vertx-openapi, and is stored once. */
    private static void assertServed(
            Vertx vertx,
            Row row,
            Map<String, JsonObject> sources,
            Answer json,
            Answer yaml,
            List<String> stored,
            List<String> compared)
            throws Exception {
        String label = row.label();
        JsonObject source = sources.get(row.servedContract());
        JsonObject jsonTree = new JsonObject(Buffer.buffer(json.body()));
        JsonObject yamlTree = yamlTree(yaml.body());
        assertAll(
                label,
                () -> assertEquals(source, jsonTree, label + ": the JSON form's tree is the contract file's"),
                () -> assertEquals(source, yamlTree, label + ": the YAML form's tree is the contract file's"),
                () -> assertNotEquals(
                        sources.get(GLOBAL_CONTRACT), jsonTree, label + ": the served tree is not the global contract"),
                () -> assertFalse(
                        hasMemberNamed(jsonTree, DISCLOSURE_MEMBER),
                        label + ": the JSON form holds " + DISCLOSURE_MEMBER),
                () -> assertFalse(
                        hasMemberNamed(yamlTree, DISCLOSURE_MEMBER),
                        label + ": the YAML form holds " + DISCLOSURE_MEMBER),
                () -> assertEquals(
                        row.operationIds(),
                        loadedOperationIds(vertx, jsonTree),
                        label + ": the operation ids vertx-openapi loads are the mount's routed, non-hidden ones"),
                () -> assertEquals(1, stored.size(), () -> label + ": stored lines " + stored),
                () -> assertTrue(
                        stored.stream().allMatch(line -> line.contains(SERVED_SOURCE)),
                        () -> label + ": the stored line names the served contract: " + stored),
                () -> assertEquals(1, compared.size(), () -> label + ": comparison lines " + compared));
    }

    /** Then: a generated document declares OpenAPI 3.1.1, discloses its validation, and conforms. */
    private static void assertGenerated(Row row, Answer json, Answer yaml, List<String> stored, List<String> compared)
            throws Exception {
        String label = row.label();
        JsonObject jsonTree = new JsonObject(Buffer.buffer(json.body()));
        JsonObject yamlTree = yamlTree(yaml.body());
        JsonObject disclosure = jsonTree.getJsonObject(DISCLOSURE_MEMBER);
        assertAll(
                label,
                () -> assertEquals("3.1.1", jsonTree.getString("openapi"), label + ": openapi"),
                () -> assertTrue(
                        disclosure != null && disclosure.containsKey("patternDialect"),
                        label + ": " + DISCLOSURE_MEMBER + ".patternDialect"),
                () -> assertEquals(
                        "Conformance catalog",
                        jsonTree.getJsonObject("info").getString("title"),
                        label + ": info.title from the declaring interface"),
                () -> assertEquals(jsonTree, yamlTree, label + ": the YAML form's tree is the JSON form's"),
                () -> OpenApi31Toolchain.assertValid(jsonTree),
                () -> assertEquals(1, stored.size(), () -> label + ": stored lines " + stored),
                () -> assertTrue(
                        stored.stream().allMatch(line -> line.contains(GENERATED_SOURCE)),
                        () -> label + ": the stored line names the generated source: " + stored),
                () -> assertEquals(1, compared.size(), () -> label + ": comparison lines " + compared));
    }

    /**
     * Loads a copy of a document with vertx-openapi and returns the operation ids of the loaded
     * contract. The argument is never handed to vertx-openapi itself.
     */
    private static Set<String> loadedOperationIds(Vertx vertx, JsonObject document) throws Exception {
        OpenAPIContract contract = Deployments.await(OpenAPIContract.from(vertx, document.copy()));
        return contract.operations().stream()
                .map(Operation::getOperationId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * One answer to a posted order.
     *
     * @param status the status code
     * @param body   the body text, empty when there was none
     */
    private record PostResult(int status, String body) {}

    private static PostResult post(WebClient client, int port, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = Deployments.await(
                client.post(port, "127.0.0.1", PARTNER_ORDERS_URI).sendJsonObject(body));
        Buffer answer = response.body();
        return new PostResult(response.statusCode(), answer == null ? "" : answer.toString(StandardCharsets.UTF_8));
    }

    /**
     * Then: the order without {@code sku} is refused with {@code 400} and a problem body whose {@code
     * errors} hold a body error of type {@code required} with the detail {@code is missing a required
     * field}. The served contract's request body declares {@code sku} as its only {@code required}
     * member and the posted body violates nothing else, so this error is the refusal of the missing
     * {@code sku}. The strategy's error does not name the missing member, so the name itself is not
     * asserted.
     */
    private static void assertMissingRequiredBodyField(String label, PostResult answer) {
        assertEquals(400, answer.status(), () -> label + ": the body without sku is refused: " + answer.body());
        JsonArray errors = new JsonObject(answer.body()).getJsonArray("errors", new JsonArray());
        boolean missingRequired = errors.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .anyMatch(error -> "required".equals(error.getString("type"))
                        && "is missing a required field".equals(error.getString("detail"))
                        && "body".equals(error.getString("location")));
        assertTrue(
                missingRequired, () -> label + ": the refusal names a missing required body field: " + answer.body());
    }

    /** Creates a fresh component for the strategy, with its configuration. */
    private static Provisions component(Vertx vertx, Strategy strategy) {
        return switch (strategy) {
            case WEB_VALIDATION ->
                DaggerServedConformanceTestComponents_WebValidationComponent.factory()
                        .create(vertx, config(Strategy.WEB_VALIDATION));
            case OPENAPI_CONTRACT ->
                DaggerServedConformanceTestComponents_OpenApiContractComponent.factory()
                        .create(vertx, config(Strategy.OPENAPI_CONTRACT));
        };
    }

    /**
     * Returns a fresh configuration: the server on {@code 127.0.0.1} port {@code 0}, the strategy, the
     * global {@code jaxrs.openapiPath}, and, under {@code openapi-contract}, {@code
     * jaxrs.applications.orders.openapiPath}. No {@code apidocs} entry is set.
     */
    private static JsonObject config(Strategy strategy) {
        JsonObject jaxrs =
                new JsonObject().put("validationStrategy", strategy.id).put("openapiPath", GLOBAL_CONTRACT);
        if (strategy == Strategy.OPENAPI_CONTRACT) {
            jaxrs.put(
                    "applications", new JsonObject().put(ORDERS, new JsonObject().put("openapiPath", ORDERS_CONTRACT)));
        }
        return new JsonObject()
                .put("http", new JsonObject().put("host", "127.0.0.1").put("port", 0))
                .put("jaxrs", jaxrs);
    }

    /** Parses a test classpath contract file by its extension: JSON for {@code .json}, YAML otherwise. */
    private static JsonObject sourceTree(String location) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(location)) {
            assertNotNull(in, "no test classpath resource " + location);
            JsonNode tree = (location.endsWith(".json") ? JSON : YAML).readTree(in);
            return new JsonObject(JSON.writeValueAsString(tree));
        }
    }

    /** Parses a YAML form into a Vert.x tree through JSON text, as {@link #sourceTree} does. */
    private static JsonObject yamlTree(byte[] yaml) throws IOException {
        return new JsonObject(JSON.writeValueAsString(YAML.readTree(yaml)));
    }

    /** Returns whether any object in the tree has a member with the name. */
    private static boolean hasMemberNamed(Object node, String name) {
        if (node instanceof JsonObject object) {
            if (object.containsKey(name)) {
                return true;
            }
            for (Map.Entry<String, Object> member : object) {
                if (hasMemberNamed(member.getValue(), name)) {
                    return true;
                }
            }
        } else if (node instanceof JsonArray array) {
            for (Object element : array) {
                if (hasMemberNamed(element, name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
