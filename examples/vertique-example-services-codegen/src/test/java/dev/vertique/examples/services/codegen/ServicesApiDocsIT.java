// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.application.test.VertiqueAppExtension;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Starts the example with its shipped configuration and reads the {@code services} document, then
 * checks that a business route still answers as before.
 *
 * <p>The shipped {@code config/application.json} is read from the classpath and merged with the
 * loopback HTTP binding and the disabled management server of {@link ShippedTestConfiguration}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ServicesApiDocsIT {

    private static final String DOCUMENT = "/apidocs/services/openapi.json";

    /** Each published path with its operations by method. */
    private static final Map<String, Map<String, String>> OPERATIONS = Map.of(
            "/billing/charge", Map.of("post", "charge"),
            "/shipping/ship", Map.of("post", "ship"),
            "/shipping/notify", Map.of("post", "notifyDispatch"));

    /** {@code ServicesApi}'s {@code @OpenAPIDefinition} info. */
    private static final String INFO = "{\"title\":\"Example Services Codegen API\",\"version\":\"0.1.0\","
            + "\"description\":\"Demonstrates compile-time service contract codegen with direct-impl and"
            + " handler-pattern contracts\"}";

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(ShippedTestConfiguration.forTest());

    /** Configures RestAssured base URI, port, and logging filters. */
    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
    }

    /** Resets RestAssured configuration after all tests complete. */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    @DisplayName("the shipped configuration publishes the services document and the routes still answer")
    void shippedConfigurationPublishesTheServicesDocument() throws IOException {
        // Given: the example started with its shipped configuration
        // When: the services document is requested
        Response response = given().when().get(DOCUMENT);

        // Then: it describes exactly the three service operations
        assertEquals(200, response.statusCode(), "GET " + DOCUMENT + "; body: " + response.asString());
        JsonNode document = JSON.readTree(response.asString());
        JsonNode orderId =
                parameter(document.path("paths").path("/shipping/notify").path("post"), "orderId");
        assertAll(
                () -> assertEquals(
                        "/", document.path("servers").path(0).path("url").asText(), "servers[0].url"),
                () -> assertEquals(OPERATIONS, operations(document), "paths, methods, and operation ids"),
                () -> assertEquals(JSON.readTree(INFO), document.path("info"), "info"),
                () -> assertEquals("query", orderId.path("in").asText(), "orderId is a query parameter"),
                () -> assertEquals(
                        "The order identifier", orderId.path("description").asText(), "orderId's description"),
                () -> assertFalse(orderId.has("required"), "orderId must carry no required member: " + orderId));

        // When: a charge is posted with the body the other integration tests use
        // Then: the business route answers as before
        given().contentType(ContentType.JSON)
                .body("{\"orderId\":\"order-001\",\"amountCents\":4999,\"currency\":\"USD\"}")
                .when()
                .post("/billing/charge")
                .then()
                .statusCode(200)
                .body("receiptId", notNullValue())
                .body("orderId", equalTo("order-001"))
                .body("amountCents", equalTo(4999))
                .body("currency", equalTo("USD"));
    }

    /**
     * Lists a document's operations.
     *
     * @param document the document
     * @return each path key with its operation ids by method
     */
    private static Map<String, Map<String, String>> operations(JsonNode document) {
        return document.path("paths").properties().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, path -> path.getValue().properties().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                method -> method.getValue().path("operationId").asText()))));
    }

    /**
     * Finds an operation's parameter by name.
     *
     * @param operation the operation
     * @param name      the parameter name
     * @return the Parameter Object, or a missing node
     */
    private static JsonNode parameter(JsonNode operation, String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(candidate -> name.equals(candidate.path("name").asText()))
                .findFirst()
                .orElse(JSON.missingNode());
    }
}
