// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.examples.services.codegen.MavenComparisonNormalizer.Difference;
import dev.vertique.examples.services.codegen.MavenComparisonNormalizer.ExpectedDifference;
import dev.vertique.examples.services.codegen.MavenComparisonNormalizer.Reconciliation;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Compares the document the running example publishes at {@code /apidocs/services/openapi.json}
 * with the document the Maven plugin writes at build time from the same annotations.
 *
 * <p>The application starts with the same loopback configuration as the other integration tests and
 * no {@code apidocs} section, so the {@code services} document is enabled by its declaration alone.
 * Both documents are normalized by {@link MavenComparisonNormalizer}; every difference must be named
 * by an entry of {@code apidocs/maven-comparison-expected-differences.json}, which also records the
 * authority that explains it, and every entry must still occur.
 *
 * <p>The Maven document is the {@code openapi.json} resource on the test classpath, which the plugin
 * regenerates at {@code compile}; the comparison is only as fresh as the last build of the module.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ApiDocsMavenComparisonIT {

    private static final String DOCUMENT_PATH = "/apidocs/services/openapi.json";

    private static final String EXPECTED_DIFFERENCES = "apidocs/maven-comparison-expected-differences.json";

    private static final Set<String> OPERATION_IDS = Set.of("charge", "ship", "notifyDispatch");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("management", new JsonObject().put("enabled", false)));

    /**
     * Resets RestAssured configuration after all tests complete.
     */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    /**
     * Fetches the runtime document, reads the Maven document, and requires that they differ only by
     * the recorded expected differences, that both describe the same operations and {@code info},
     * that {@code orderId} is described and optional in both, and that the Maven plugin's model
     * converter is not on the runtime classpath.
     */
    @Test
    @DisplayName("the runtime document differs from the Maven document only by recorded authorities")
    void runtimeDocumentDiffersOnlyByRecordedAuthorities() throws IOException {
        // Given: the running example and the Maven plugin's document from the same annotations
        Response response =
                given().baseUri("http://127.0.0.1").port(app.httpPort()).when().get(DOCUMENT_PATH);
        assertEquals(200, response.statusCode(), "GET " + DOCUMENT_PATH + " answered " + response.statusCode());
        JsonNode runtime = MAPPER.readTree(response.asByteArray());
        JsonNode maven = mavenDocument();
        List<ExpectedDifference> expected = MavenComparisonNormalizer.readExpected(classpathJson(EXPECTED_DIFFERENCES));

        // When: both documents are normalized and compared
        List<Difference> observed = MavenComparisonNormalizer.compare(maven, runtime);
        Reconciliation reconciliation = MavenComparisonNormalizer.reconcile(observed, expected);

        // Then: every difference is expected and every expected entry still occurs
        assertTrue(
                reconciliation.clean(),
                "the normalized comparison does not match the expected differences:" + reconciliation.report()
                        + "\nall observed differences:\n  "
                        + String.join(
                                "\n  ", observed.stream().map(Difference::line).toList()));

        // Then: the runtime path cannot load the Maven plugin's model converter
        assertThrows(ClassNotFoundException.class, () -> Class.forName("dev.vertique.openapi.FutureModelConverter"));

        // Then: both documents describe the example's operations, or the gap is a recorded presence entry
        assertOperationIds(maven, "Maven", expected);
        assertOperationIds(runtime, "runtime", expected);

        // Then: both documents carry the same info
        assertInfo(maven.path("info"), "Maven");
        assertInfo(runtime.path("info"), "runtime");

        // Then: orderId is described and not required in both documents
        assertOrderId(maven, "Maven");
        assertOrderId(runtime, "runtime");
    }

    /**
     * Sends a dispatch notification without {@code orderId}: with no Bean Validation validator bound,
     * the query parameter is optional at runtime, matching the published requiredness.
     */
    @Test
    @DisplayName("a dispatch notification without orderId answers 204")
    void notifyWithoutOrderIdAnswersNoContent() {
        // Given: the running example, which binds no Bean Validation validator
        // When: a notification is sent with no orderId query parameter
        Response response =
                given().baseUri("http://127.0.0.1").port(app.httpPort()).when().post("/shipping/notify");

        // Then: the request is accepted like one with an orderId
        assertEquals(
                204,
                response.statusCode(),
                "POST /shipping/notify without orderId answered " + response.statusCode() + ": " + response.asString());
    }

    private static JsonNode mavenDocument() throws IOException {
        URL resource = ApiDocsMavenComparisonIT.class.getClassLoader().getResource("openapi.json");
        assertNotNull(resource, "the Maven plugin's openapi.json is not on the test classpath; build the module");
        try (InputStream in = resource.openStream()) {
            return MAPPER.readTree(in);
        }
    }

    private static JsonNode classpathJson(String name) throws IOException {
        try (InputStream in = ApiDocsMavenComparisonIT.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "the classpath resource " + name + " is missing");
            return MAPPER.readTree(in);
        }
    }

    private static void assertOperationIds(JsonNode document, String which, List<ExpectedDifference> expected) {
        Map<String, JsonNode> operations = MavenComparisonNormalizer.operations(document);
        Set<String> differing = new TreeSet<>(OPERATION_IDS);
        differing.removeAll(operations.keySet());
        Set<String> extra = new TreeSet<>(operations.keySet());
        extra.removeAll(OPERATION_IDS);
        differing.addAll(extra);
        for (String id : differing) {
            boolean recorded = expected.stream()
                    .anyMatch(e -> e.operationId().equals(id)
                            && e.location().equals("operation")
                            && e.dimension().equals("presence"));
            if (!recorded) {
                fail("the " + which + " document's operation ids " + operations.keySet() + " differ from "
                        + new TreeSet<>(OPERATION_IDS) + " at '" + id + "', which no presence entry records");
            }
        }
    }

    private static void assertInfo(JsonNode info, String which) {
        assertEquals("Example Services Codegen API", info.path("title").asText(null), which + " info.title");
        assertEquals("0.1.0", info.path("version").asText(null), which + " info.version");
        assertEquals(
                "Demonstrates compile-time service contract codegen with direct-impl and handler-pattern contracts",
                info.path("description").asText(null),
                which + " info.description");
    }

    private static void assertOrderId(JsonNode document, String which) {
        JsonNode operation = MavenComparisonNormalizer.operations(document).get("notifyDispatch");
        assertNotNull(operation, "the " + which + " document has no notifyDispatch operation");
        JsonNode orderId = null;
        for (JsonNode parameter : operation.path("parameters")) {
            if ("orderId".equals(parameter.path("name").asText())
                    && "query".equals(parameter.path("in").asText())) {
                orderId = parameter;
            }
        }
        assertNotNull(orderId, "the " + which + " document's notifyDispatch has no query parameter orderId");
        assertEquals("The order identifier", orderId.path("description").asText(null), which + " orderId description");
        assertFalse(orderId.path("required").asBoolean(false), which + " orderId is published as required");
    }
}
