// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Reads the two published OpenAPI documents of the example: the public document anonymously, and the
 * management document as an anonymous caller, as an authenticated caller without the {@code admin}
 * role, and as an {@code admin}.
 *
 * <p>The example starts with its shipped configuration plus the test-only HTTP binding and JWT key
 * of {@link TestConfiguration}. Every expected value is a literal written here, never read from the
 * code under test.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ApiDocumentsIT {

    private static final String PUBLIC_JSON = "/apidocs/public/openapi.json";
    private static final String PUBLIC_YAML = "/apidocs/public/openapi.yaml";
    private static final String MANAGEMENT_JSON = "/apidocs/management/openapi.json";

    /** The public document's path keys, each with its operations by method. */
    private static final Map<String, Map<String, String>> PUBLIC_OPERATIONS =
            Map.of("/items", Map.of("get", "listItems"), "/items/{id}", Map.of("get", "getItem"));

    /** The management document's path keys, each with its operations by method. */
    private static final Map<String, Map<String, String>> MANAGEMENT_OPERATIONS =
            Map.of("/status", Map.of("get", "getStatus"), "/items/{id}", Map.of("put", "updateItem"));

    /** {@code PublicApi}'s {@code @OpenAPIDefinition} info. */
    private static final String PUBLIC_INFO = "{\"title\":\"Catalog API\",\"version\":\"1.0\"}";

    /** The shipped configuration's {@code apidocs.documents.management.info}. */
    private static final String MANAGEMENT_INFO = "{\"title\":\"Management API\",\"version\":\"1.0\"}";

    /** The shipped configuration's {@code apidocs.documents.public.serverUrl}. */
    private static final String PUBLIC_SERVER_URL = "https://api.example.com/api/public";

    /** Text that names something only the management application has. */
    private static final List<String> MANAGEMENT_ONLY_TEXT =
            List.of("/status", "getStatus", "updateItem", "ItemUpdate", "ServiceStatus", "bearerAuth", "/api/mgmt");

    private static final String BEARER_SCHEMES =
            "{\"bearerAuth\":{\"type\":\"http\",\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\"}}";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(TestConfiguration.forTest());

    private static JWTAuth tokens;

    /** Configures RestAssured and the token issuer that shares the test-only key. */
    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        tokens = JwtAuthFactory.fromSymmetricKey(app.vertx(), "HS256", TestConfiguration.JWT_KEY);
    }

    /** Resets RestAssured configuration after all tests complete. */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    @DisplayName("the public document describes only the public application and is served anonymously")
    void publicDocumentDescribesOnlyThePublicApplication() throws IOException {
        // Given: the example started with its shipped configuration
        // When: both forms of the public document are requested without credentials
        Fetched json = fetch(anonymous(), PUBLIC_JSON);
        Fetched yaml = fetch(anonymous(), PUBLIC_YAML);

        // Then: both answer with their media type, a strong entity tag, and no-store caching
        assertDocumentAnswer(json, "application/json", "no-store");
        assertDocumentAnswer(yaml, "application/yaml", "no-store");
        assertEquals(json.tree(), yaml.tree(), "the YAML form must parse to the JSON form's tree");

        // Then: the document describes exactly the public application
        JsonNode document = json.tree();
        assertAll(
                () -> assertEquals(
                        1, document.path("servers").size(), "servers must have exactly one entry: " + document),
                () -> assertEquals(
                        PUBLIC_SERVER_URL,
                        document.path("servers").path(0).path("url").asText(),
                        "servers[0].url must be the configured serverUrl"),
                () -> assertEquals(JSON.readTree(PUBLIC_INFO), document.path("info"), "info"),
                () -> assertEquals(PUBLIC_OPERATIONS, operations(document), "paths, methods, and operation ids"),
                () -> assertTrue(
                        securityOf(document).isEmpty(),
                        "no public operation may have security: " + securityOf(document)),
                () -> assertTrue(
                        document.path("components").path("securitySchemes").isMissingNode(),
                        "the public document must have no components.securitySchemes"),
                () -> assertEquals(
                        JSON.readTree("{\"patternDialect\":\"java.util.regex\"}"),
                        document.path("x-vertique-validation"),
                        "x-vertique-validation of a public document"),
                () -> assertTrue(
                        MANAGEMENT_ONLY_TEXT.stream().noneMatch(json.body()::contains),
                        "the public document must not name "
                                + MANAGEMENT_ONLY_TEXT.stream()
                                        .filter(json.body()::contains)
                                        .toList()));
    }

    @Test
    @DisplayName("the management document is served only to an authenticated admin")
    void managementDocumentServedOnlyToAdmins() throws IOException {
        // Given: tokens minted with the test-only key for a user and an admin
        String bob = token("bob", "user");
        String ada = token("ada", "admin");

        // When: the management document is requested by each caller
        Fetched anonymous = fetch(anonymous(), MANAGEMENT_JSON);
        Fetched asBob = fetch(as(bob), MANAGEMENT_JSON);
        Fetched asAda = fetch(as(ada), MANAGEMENT_JSON);
        String adaTag = asAda.response().header("ETag");
        Fetched conditionalAnonymous =
                fetch(anonymous().header("If-None-Match", String.valueOf(adaTag)), MANAGEMENT_JSON);

        // Then: every caller but the admin is denied without document content
        assertDenied(anonymous, 401, "Unauthorized");
        assertDenied(conditionalAnonymous, 401, "Unauthorized");
        assertDenied(asBob, 403, "Forbidden");

        // Then: the admin receives the management document, privately cached
        assertDocumentAnswer(asAda, "application/json", "private, no-store");
        JsonNode document = asAda.tree();
        assertAll(
                () -> assertEquals(
                        Set.of("Authorization"), headerTokens(asAda.response(), "Vary"), "Vary of the admin's answer"),
                () -> assertEquals(
                        "/api/mgmt",
                        document.path("servers").path(0).path("url").asText(),
                        "servers[0].url"),
                () -> assertEquals(MANAGEMENT_OPERATIONS, operations(document), "paths, methods, and operation ids"),
                () -> assertEquals(
                        Map.of("/status get", "[{\"bearerAuth\":[]}]", "/items/{id} put", "[{\"bearerAuth\":[]}]"),
                        securityOf(document),
                        "every management operation requires bearerAuth"),
                () -> assertEquals(
                        JSON.readTree(BEARER_SCHEMES),
                        document.path("components").path("securitySchemes"),
                        "components.securitySchemes"),
                () -> assertEquals(JSON.readTree(MANAGEMENT_INFO), document.path("info"), "info"),
                () -> assertEquals(
                        "web-validation",
                        document.path("x-vertique-validation").path("strategy").asText(),
                        "x-vertique-validation.strategy"),
                () -> assertEquals(
                        "active",
                        document.path("x-vertique-validation")
                                .path("enforcement")
                                .asText(),
                        "x-vertique-validation.enforcement"));
    }

    /** A response with its body read once, and the body's tree when it is a document. */
    private record Fetched(Response response, String body, JsonNode tree) {}

    /**
     * Sends {@code GET path} and reads the answer.
     *
     * @param request the request to send
     * @param path    the request path
     * @return the answer; its tree is parsed from JSON or YAML by the path's extension when the
     *     status is 200, otherwise {@code null}
     */
    private static Fetched fetch(RequestSpecification request, String path) throws IOException {
        Response response = request.when().get(path);
        String body = response.asString();
        JsonNode tree = null;
        if (response.statusCode() == 200) {
            tree = path.endsWith(".yaml") ? YAML.readTree(body) : JSON.readTree(body);
        }
        return new Fetched(response, body, tree);
    }

    /** Returns a request without credentials. */
    private static RequestSpecification anonymous() {
        return given();
    }

    /**
     * Returns a request carrying a bearer token.
     *
     * @param token the token
     * @return the request
     */
    private static RequestSpecification as(String token) {
        return given().header("Authorization", "Bearer " + token);
    }

    /**
     * Mints a token with the test-only key.
     *
     * @param subject the token's subject
     * @param role    the single entry of its {@code roles} claim
     * @return the signed token
     */
    private static String token(String subject, String role) {
        return tokens.generateToken(new JsonObject().put("sub", subject).put("roles", new JsonArray().add(role)));
    }

    /**
     * Asserts a successful document answer: status 200, the exact media type, a strong entity tag, and
     * the expected {@code Cache-Control}.
     */
    private static void assertDocumentAnswer(Fetched fetched, String mediaType, String cacheControl) {
        Response response = fetched.response();
        assertEquals(200, response.statusCode(), "status; body: " + fetched.body());
        assertAll(
                () -> assertEquals(mediaType, response.header("Content-Type"), "Content-Type"),
                () -> assertTrue(
                        String.valueOf(response.header("ETag")).matches("\"[0-9a-f]{64}\""),
                        "ETag must be strong and hold a SHA-256 hex digest: " + response.header("ETag")),
                () -> assertEquals(cacheControl, response.header("Cache-Control"), "Cache-Control"));
    }

    /**
     * Asserts a denial: the problem body, no-store caching, no entity tag, and no document content.
     *
     * @param fetched the answer
     * @param status  the expected status
     * @param title   the status's reason phrase
     */
    private static void assertDenied(Fetched fetched, int status, String title) throws IOException {
        Response response = fetched.response();
        String body = fetched.body();
        assertEquals(status, response.statusCode(), "status; body: " + body);
        assertAll(
                () -> assertEquals(
                        "application/problem+json",
                        String.valueOf(response.header("Content-Type"))
                                .split(";")[0]
                                .trim(),
                        "Content-Type"),
                () -> assertEquals(
                        JSON.readTree(
                                "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + "}"),
                        JSON.readTree(body),
                        "problem body"),
                () -> assertEquals("no-store", response.header("Cache-Control"), "Cache-Control"),
                () -> assertNull(response.header("ETag"), "a denial must carry no ETag"),
                () -> assertFalse(body.contains("openapi"), "a denial must not contain 'openapi': " + body),
                () -> assertFalse(body.contains("paths"), "a denial must not contain 'paths': " + body));
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
     * Collects the {@code security} member of every operation that has one.
     *
     * @param document the document
     * @return {@code "<path> <method>"} mapped to the compact JSON of its {@code security}
     */
    private static Map<String, String> securityOf(JsonNode document) {
        Map<String, String> security = new HashMap<>();
        document.path("paths")
                .properties()
                .forEach(path -> path.getValue().properties().forEach(method -> {
                    JsonNode requirement = method.getValue().get("security");
                    if (requirement != null) {
                        security.put(path.getKey() + " " + method.getKey(), requirement.toString());
                    }
                }));
        return security;
    }

    /**
     * Splits every value of a comma-separated header into its trimmed tokens.
     *
     * @param response the response
     * @param name     the header name
     * @return the tokens, empty when the header is absent
     */
    private static Set<String> headerTokens(Response response, String name) {
        List<String> tokens = new ArrayList<>();
        response.headers().getValues(name).forEach(value -> Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(tokens::add));
        return new TreeSet<>(tokens);
    }
}
