// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.BackOfficeApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.OrderBodySwitchingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.DocumentRequests.Answer;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.StoreLogCapture;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import dev.vertique.rest.validation.WebValidationStrategy;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proof that complete documents, a public one and a protected one, are stored once per
 * component and served byte-identically by every composition of that component, and that a schema
 * source that answers a later composition differently fails that composition's startup for the
 * snapshot reason only.
 *
 * <p>The applications are {@code public} at {@code /api/public} ({@code @ApiDocs(access = PUBLIC)})
 * and {@code management} at {@code /api/mgmt} ({@code @ApiDocs(access = PROTECTED, securityScheme =
 * "bearerAuth", rolesAllowed = {"admin"})}); their resources have query, header, and path
 * parameters, a JSON request body, inferred and declared responses, and guarded operations. The
 * management document is read with an {@code admin} bearer token. Requests travel over separate
 * connections, and no assertion depends on which instance of a two-instance deployment answered.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MultiInstanceDocumentIT {

    /** The public application's name. */
    private static final String PUBLIC_NAME = "public";

    /** The public application's mount path, as the store's log lines name it. */
    private static final String PUBLIC_MOUNT = "/api/public/*";

    /** The management application's name. */
    private static final String MANAGEMENT_NAME = "management";

    /** The management application's mount path, as the store's log lines and failures name it. */
    private static final String MANAGEMENT_MOUNT = "/api/mgmt/*";

    /** The public document's JSON form. */
    private static final String PUBLIC_JSON = "/apidocs/public/openapi.json";

    /** The public document's YAML form. */
    private static final String PUBLIC_YAML = "/apidocs/public/openapi.yaml";

    /** The management document's JSON form. */
    private static final String MANAGEMENT_JSON = "/apidocs/management/openapi.json";

    /** The management document's YAML form. */
    private static final String MANAGEMENT_YAML = "/apidocs/management/openapi.yaml";

    /** The operation ids the public document lists. */
    private static final Set<String> PUBLIC_OPERATIONS = Set.of("listEntries", "getEntry");

    /** The operation ids the management document lists. */
    private static final Set<String> MANAGEMENT_OPERATIONS = Set.of("createOrder", "readOrder");

    /** The operation whose request body the switching source varies. */
    private static final String CREATE_ORDER = "createOrder";

    /** The component holding the request body of {@value #CREATE_ORDER}. */
    private static final String CREATE_ORDER_BODY_KEY = "createOrder.request";

    /** The reference from {@value #CREATE_ORDER}'s request body to its component. */
    private static final String CREATE_ORDER_BODY_REF = "#/components/schemas/createOrder.request";

    /** The property names of the order body's first variant. */
    private static final Set<String> FIRST_VARIANT_PROPERTIES = Set.of("item", "quantity");

    /** The property names of the order body's second variant: one more than the first. */
    private static final Set<String> SECOND_VARIANT_PROPERTIES = Set.of("item", "quantity", "giftNote");

    /** The compositions the sequential scenario starts: one deployment after the other. */
    private static final int SEQUENTIAL_COMPOSITIONS = 2;

    /** The instances of the single two-instance deployment. */
    private static final int INSTANCES = 2;

    /** The "stored" lines expected per application per component: only one composition stores. */
    private static final int STORED_LINES_PER_APPLICATION = 1;

    /** The comparison lines expected per application per component: the other composition compares. */
    private static final int COMPARISON_LINES_PER_APPLICATION = 1;

    /** The requests sent per form of each document on each published port. */
    private static final int REQUESTS_PER_FORM = 6;

    /** One document form and the bearer token it is requested with. */
    private record Form(String path, @Nullable String token) {}

    @Test
    @DisplayName("Two compositions store each complete document once and serve identical bytes and entity tags")
    void completeDocumentsIdenticalAcrossInstances(Vertx vertx) throws Exception {
        List<String> deployments = new ArrayList<>();
        WebClient client = DocumentRequests.separateConnectionsClient(vertx);
        String adminToken = SharedDeployment.alice(SharedDeployment.jwtAuth(vertx));
        List<Form> forms = List.of(
                new Form(PUBLIC_JSON, null),
                new Form(PUBLIC_YAML, null),
                new Form(MANAGEMENT_JSON, adminToken),
                new Form(MANAGEMENT_YAML, adminToken));
        try {
            // (i) Given: one component whose schema source counts calls to the canonical source.
            MultiInstanceDocumentTestComponents.CountingSourceComponent sequential =
                    DaggerMultiInstanceDocumentTestComponents_CountingSourceComponent.factory()
                            .create(vertx, webValidationConfig());

            // When: its HttpVerticle supplier is deployed twice, one deployment after the other.
            int firstPort;
            int secondPort;
            try (StoreLogCapture logs = StoreLogCapture.attach()) {
                firstPort = Deployments.deployAndReadPort(
                        vertx, sequential::httpVerticle, new DeploymentOptions(), deployments);
                secondPort = Deployments.deployAndReadPort(
                        vertx, sequential::httpVerticle, new DeploymentOptions(), deployments);

                // Then: per application, one stored document and one comparison.
                assertStoredOnceComparedOnce(logs, "one deployment after the other");
            }

            // Then: every operation was resolved once per composition.
            int sequentialCallsAfterStartup = sequential.countingSource().calls();
            for (String operationId : List.of("listEntries", "getEntry", "createOrder", "readOrder")) {
                assertEquals(
                        SEQUENTIAL_COMPOSITIONS,
                        sequential.countingSource().calls(operationId),
                        () -> "schema-source calls for " + operationId + " over two sequential compositions");
            }

            // When: each form is requested repeatedly on both ports.
            // Then: both ports serve one body and one entity tag per form.
            assertNotEquals(firstPort, secondPort);
            for (Form form : forms) {
                Set<String> bodies = new HashSet<>();
                Set<String> etags = new HashSet<>();
                for (int port : List.of(firstPort, secondPort)) {
                    collect(client, port, form, bodies, etags);
                }
                assertEquals(1, bodies.size(), () -> form.path() + ": distinct bodies over both ports");
                assertEquals(1, etags.size(), () -> form.path() + ": distinct entity tags over both ports: " + etags);
            }
            assertOperations(client, firstPort, adminToken);

            // Then: the requests performed no schema-source call.
            assertEquals(
                    sequentialCallsAfterStartup,
                    sequential.countingSource().calls(),
                    "schema-source calls after the requests, sequential compositions");

            // (ii) Given: a fresh component. When: it is deployed once with two instances.
            MultiInstanceDocumentTestComponents.CountingSourceComponent twoInstances =
                    DaggerMultiInstanceDocumentTestComponents_CountingSourceComponent.factory()
                            .create(vertx, webValidationConfig());
            int sharedPort;
            try (StoreLogCapture logs = StoreLogCapture.attach()) {
                sharedPort = Deployments.deployAndReadPort(
                        vertx,
                        twoInstances::httpVerticle,
                        new DeploymentOptions().setInstances(INSTANCES),
                        deployments);

                // Then: per application, one stored document and one comparison.
                assertStoredOnceComparedOnce(logs, "one deployment of two instances");
            }
            int twoInstanceCallsAfterStartup = twoInstances.countingSource().calls();
            assertTrue(twoInstanceCallsAfterStartup > 0, "the counting source was not asked at startup");

            // When: each form is requested repeatedly over separate connections.
            // Then: one body and one entity tag per form, whichever instance answered.
            for (Form form : forms) {
                Set<String> bodies = new HashSet<>();
                Set<String> etags = new HashSet<>();
                collect(client, sharedPort, form, bodies, etags);
                assertEquals(1, bodies.size(), () -> form.path() + ": distinct bodies over two instances");
                assertEquals(
                        1, etags.size(), () -> form.path() + ": distinct entity tags over two instances: " + etags);
            }
            assertOperations(client, sharedPort, adminToken);

            // Then: the requests performed no schema-source call.
            assertEquals(
                    twoInstanceCallsAfterStartup,
                    twoInstances.countingSource().calls(),
                    "schema-source calls after the requests, two instances");
        } finally {
            client.close();
            Deployments.undeployAll(vertx, deployments);
        }
    }

    @Test
    @DisplayName(
            "A source that answers a later composition differently fails it naming application, mount, and operation")
    void statefulSourceFailsNamingApplicationMountAndOperation(Vertx vertx) throws Exception {
        List<String> deployments = new ArrayList<>();
        WebClient client = DocumentRequests.separateConnectionsClient(vertx);
        String adminToken = SharedDeployment.alice(SharedDeployment.jwtAuth(vertx));
        try {
            // Given: a component whose source describes the order body's first variant on its first
            // resolution and the second variant on every later one.
            MultiInstanceDocumentTestComponents.FirstVariantComponent component =
                    DaggerMultiInstanceDocumentTestComponents_FirstVariantComponent.factory()
                            .create(vertx, webValidationConfig());
            OrderBodySwitchingSchemaSource source = component.switchingSource();

            // (i) When: the winner deploys with one instance and the management document is fetched.
            int winnerPort =
                    Deployments.deployAndReadPort(vertx, component::httpVerticle, new DeploymentOptions(), deployments);
            Answer winnerBefore = DocumentRequests.get(client, winnerPort, MANAGEMENT_JSON, adminToken);

            // Then: it serves the first variant's properties.
            assertEquals(200, winnerBefore.status(), "winner: status of the management document");
            assertNotNull(winnerBefore.etag(), "winner: entity tag of the management document");
            assertEquals(
                    FIRST_VARIANT_PROPERTIES,
                    createOrderBodyProperties(winnerBefore),
                    "winner: the createOrder request component's properties");
            assertEquals(1, source.orderResolutions(), "winner: resolutions of createOrder");

            // (ii) When: the comparer, the same supplier, deploys after the winner completed.
            Throwable comparerFailure;
            try (StoreLogCapture logs = StoreLogCapture.attach()) {
                comparerFailure = Deployments.failureOf(vertx, component::httpVerticle, new DeploymentOptions());
                assertEquals(
                        COMPARISON_LINES_PER_APPLICATION,
                        logs.comparisonLines(MANAGEMENT_NAME, MANAGEMENT_MOUNT).size(),
                        () -> "comparer: comparison lines for management: " + logs.allMessages());
            }
            assertEquals(2, source.orderResolutions(), "comparer: resolutions of createOrder");

            // Then: it fails with the snapshot comparison's failure naming application, mount, and
            // operation.
            RestConfigurationException divergence = assertInstanceOf(
                    RestConfigurationException.class,
                    comparerFailure,
                    () -> "comparer: the failure's class: "
                            + comparerFailure.getClass().getName() + ": " + comparerFailure);
            String message = divergence.getMessage();
            assertNotNull(message, "comparer: the failure has no message");
            for (String part : List.of(
                    "application '" + MANAGEMENT_NAME + "'",
                    BackOfficeApi.class.getName(),
                    "at '" + MANAGEMENT_MOUNT + "'",
                    "differs from the document already published for the application",
                    "operation '" + CREATE_ORDER + "' differs")) {
                assertTrue(message.contains(part), () -> "comparer: the message lacks <" + part + ">: " + message);
            }

            // Then: it is not the redaction-data refusal, and it carries no schema text.
            assertFalse(
                    message.toLowerCase(Locale.ROOT).contains("manifest"),
                    () -> "comparer: the message is the redaction-data refusal: " + message);
            assertFalse(
                    message.contains(OrderBodySwitchingSchemaSource.class.getSimpleName()),
                    () -> "comparer: the message names the schema source: " + message);
            for (String schemaText : List.of("\"properties\"", "\"type\"", "{")) {
                assertFalse(
                        message.contains(schemaText),
                        () -> "comparer: the message carries schema text " + schemaText + ": " + message);
            }

            // Then: the winner still serves the bytes and entity tag it served before.
            Answer winnerAfter = DocumentRequests.get(client, winnerPort, MANAGEMENT_JSON, adminToken);
            assertEquals(200, winnerAfter.status(), "winner after the comparer: status");
            assertArrayEquals(winnerBefore.body(), winnerAfter.body(), "winner after the comparer: body");
            assertEquals(winnerBefore.etag(), winnerAfter.etag(), "winner after the comparer: entity tag");

            // (iii) When: the control, whose source describes the second variant from the start,
            // deploys and its management document is fetched.
            MultiInstanceDocumentTestComponents.SecondVariantComponent control =
                    DaggerMultiInstanceDocumentTestComponents_SecondVariantComponent.factory()
                            .create(vertx, webValidationConfig());
            int variantTwoControlPort =
                    Deployments.deployAndReadPort(vertx, control::httpVerticle, new DeploymentOptions(), deployments);
            Answer variantTwoControl = DocumentRequests.get(client, variantTwoControlPort, MANAGEMENT_JSON, adminToken);

            // Then: it serves the second variant's properties, so that variant alone publishes.
            assertEquals(200, variantTwoControl.status(), "variantTwoControl: status of the management document");
            assertEquals(
                    SECOND_VARIANT_PROPERTIES,
                    createOrderBodyProperties(variantTwoControl),
                    "variantTwoControl: the createOrder request component's properties");
            assertEquals(1, control.switchingSource().orderResolutions(), "variantTwoControl: resolutions");
        } finally {
            client.close();
            Deployments.undeployAll(vertx, deployments);
        }
    }

    /**
     * Returns the shared deployment's configuration without an {@code apidocs} section, with the
     * {@code web-validation} strategy so the schema source is asked.
     */
    private static JsonObject webValidationConfig() {
        JsonObject config = SharedDeployment.withoutApidocs();
        config.getJsonObject("jaxrs").put("validationStrategy", WebValidationStrategy.ID);
        return config;
    }

    /** Asserts one "stored" line and one comparison line for each application. */
    private static void assertStoredOnceComparedOnce(StoreLogCapture logs, String scenario) {
        for (String[] application :
                List.of(new String[] {PUBLIC_NAME, PUBLIC_MOUNT}, new String[] {MANAGEMENT_NAME, MANAGEMENT_MOUNT})) {
            String name = application[0];
            String mount = application[1];
            List<String> storedLines = logs.storedLines(name, mount);
            List<String> comparisonLines = logs.comparisonLines(name, mount);
            assertEquals(
                    STORED_LINES_PER_APPLICATION,
                    storedLines.size(),
                    () -> scenario + ": stored lines for " + name + " at " + mount + ": " + logs.allMessages());
            assertEquals(
                    COMPARISON_LINES_PER_APPLICATION,
                    comparisonLines.size(),
                    () -> scenario + ": comparison lines for " + name + " at " + mount + ": " + logs.allMessages());
        }
    }

    /** Requests one form repeatedly on one port, collecting each body and entity tag. */
    private static void collect(WebClient client, int port, Form form, Set<String> bodies, Set<String> etags)
            throws Exception {
        for (int request = 0; request < REQUESTS_PER_FORM; request++) {
            Answer answer = DocumentRequests.get(client, port, form.path(), form.token());
            assertEquals(200, answer.status(), () -> form.path() + " on port " + port + ": status");
            assertTrue(answer.body().length > 0, () -> form.path() + " on port " + port + ": empty body");
            assertNotNull(answer.etag(), () -> form.path() + " on port " + port + ": no entity tag");
            bodies.add(new String(answer.body(), StandardCharsets.UTF_8));
            etags.add(answer.etag());
        }
    }

    /** Asserts that both JSON documents served on a port are complete: they list every operation. */
    private static void assertOperations(WebClient client, int port, String adminToken) throws Exception {
        assertEquals(
                PUBLIC_OPERATIONS,
                operationIds(DocumentRequests.get(client, port, PUBLIC_JSON, null)),
                "the public document's operations");
        assertEquals(
                MANAGEMENT_OPERATIONS,
                operationIds(DocumentRequests.get(client, port, MANAGEMENT_JSON, adminToken)),
                "the management document's operations");
    }

    /** Returns the operation ids of a JSON document. */
    private static Set<String> operationIds(Answer answer) {
        JsonObject paths = document(answer).getJsonObject("paths");
        Set<String> ids = new HashSet<>();
        for (String path : paths.fieldNames()) {
            JsonObject item = paths.getJsonObject(path);
            for (String method : item.fieldNames()) {
                ids.add(item.getJsonObject(method).getString("operationId"));
            }
        }
        return ids;
    }

    /**
     * Returns the property names of {@value #CREATE_ORDER}'s request-body component, after checking
     * that the operation's JSON body references it.
     */
    private static Set<String> createOrderBodyProperties(Answer answer) {
        JsonObject document = document(answer);
        String reference = document.getJsonObject("paths")
                .getJsonObject("/orders")
                .getJsonObject("post")
                .getJsonObject("requestBody")
                .getJsonObject("content")
                .getJsonObject("application/json")
                .getJsonObject("schema")
                .getString("$ref");
        assertEquals(CREATE_ORDER_BODY_REF, reference, "the createOrder body's reference");
        JsonObject component =
                document.getJsonObject("components").getJsonObject("schemas").getJsonObject(CREATE_ORDER_BODY_KEY);
        assertNotNull(component, () -> "no component " + CREATE_ORDER_BODY_KEY + ": " + document.encode());
        return new HashSet<>(component.getJsonObject("properties").fieldNames());
    }

    /** Parses a JSON document answer. */
    private static JsonObject document(Answer answer) {
        assertEquals(200, answer.status(), "status of a JSON document");
        return new JsonObject(Buffer.buffer(answer.body()));
    }
}
