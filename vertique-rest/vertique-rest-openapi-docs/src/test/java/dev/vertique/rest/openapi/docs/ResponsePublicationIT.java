// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.responses.it.AccountResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.AccountsApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReportResource;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.Validator;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Deploys the documented application {@code accounts} under {@code web-validation} and checks the
 * Responses Objects and output components its public document publishes.
 *
 * <p>An asymmetric DTO, serialized under a snake-case profile its resource selects, publishes a
 * {@code 200} response referencing its output-direction component, distinct from the request
 * component, and the real response body validates against the published component resolved within
 * the whole document. An operation returning {@code Response} publishes only the runtime-determined
 * {@code default} response, and declared content wins over the return type.
 *
 * <p>One loopback-bound deployment and one client serve the class; both are released in {@code
 * AfterAll} on every path. Nothing is asserted while deploying, so a failed deployment fails each
 * test on its own.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ResponsePublicationIT {

    private static final String HOST = "127.0.0.1";

    /** The fixed URI the published document is dereferenced under. */
    private static final String DOCUMENT_URI = "https://responses.test/document.json";

    /** The prefix of every reference to a component schema. */
    private static final String COMPONENTS_PREFIX = "#/components/schemas/";

    /** The JSON media type. */
    private static final String JSON = "application/json";

    /** The options the published component is compiled with: Draft 2020-12. */
    private static final JsonSchemaOptions SCHEMA_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** The inferred response component of {@code create}. */
    private static final String CREATE_RESPONSE = AccountResource.CREATE + ".response";

    /** The request component of {@code create}. */
    private static final String CREATE_REQUEST = AccountResource.CREATE + ".request";

    /** The wire names of {@code Account} in output direction under the snake-case profile. */
    private static final Set<String> ACCOUNT_OUTPUT_NAMES = Set.of("display_name", "id");

    /** The output names of {@code ViewB}, the declared {@code 200} content of {@code explicit}. */
    private static final Set<String> VIEW_B_NAMES = Set.of("bravoLabel", "bravoSize");

    /** The output names of {@code ViewA}, the return type of {@code explicit}. */
    private static final List<String> VIEW_A_NAMES = List.of("alphaTitle", "alphaCount");

    /** The declared {@code 200} content component of {@code explicit}. */
    private static final String EXPLICIT_200 = ReportResource.EXPLICIT + ".response.200";

    /** The inferred component {@code explicit} must not publish. */
    private static final String EXPLICIT_INFERRED = ReportResource.EXPLICIT + ".response";

    private static Vertx vertx;
    private static WebClient client;
    private static Outcome deployment;

    @BeforeAll
    static void deploy(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
        JsonObject config = InputAssemblyIT.webValidationConfig(AccountsApi.NAME);
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        deployment = StartupDeployments.deploy(vertx, () -> DaggerResponseTestComponents_AccountsComponent.factory()
                .create(config)
                .httpVerticle());
    }

    @AfterAll
    static void undeployAndCloseTheClient() throws Exception {
        try {
            StartupDeployments.undeploy(vertx, deployment);
        } finally {
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
            client.close();
        }
    }

    @Test
    @DisplayName(
            "An asymmetric DTO publishes a 200 response referencing its output component, distinct from the request component, and the real body validates against it")
    void asymmetricDtoPublishesDirectionalWireShapes() throws Exception {
        // Given: the accounts application deployed with an enabled public document
        assertDeployed();

        // When: the document is fetched, then a valid body is posted
        JsonObject document = publishedDocument(AccountsApi.NAME);
        JsonObject requestBody = new JsonObject().put("display_name", "Ada").put("password", "pw-1");
        HttpResponse<Buffer> created = Futures.await(
                client.post(deployment.port(), HOST, AccountsApi.PATH + AccountResource.ROUTE)
                        .sendJsonObject(requestBody),
                StartupDeployments.BOUND);

        // Then: exactly one 200 response, described OK, with one JSON media type referencing create.response
        JsonObject responses = responses(document, AccountResource.ROUTE, "post");
        assertEquals(List.of("200"), List.copyOf(responses.fieldNames()), "the response keys of create");
        JsonObject ok = responses.getJsonObject("200");
        assertEquals("OK", ok.getString("description"), "the description of create's 200 response");
        JsonObject content = ok.getJsonObject("content");
        assertNotNull(content, () -> "create's 200 response must carry content: " + ok.encode());
        assertEquals(List.of(JSON), List.copyOf(content.fieldNames()), "the media types of create's 200 response");
        JsonObject schema = content.getJsonObject(JSON).getJsonObject("schema");
        assertNotNull(schema, () -> "create's JSON media type must carry a schema: " + content.encode());
        assertEquals(
                COMPONENTS_PREFIX + CREATE_RESPONSE, schema.getString("$ref"), "the schema reference of create's 200");
        assertAll(
                "no inferred error or default responses",
                () -> assertFalse(responses.containsKey("4XX"), "no 4XX response"),
                () -> assertFalse(responses.containsKey("5XX"), "no 5XX response"),
                () -> assertFalse(responses.containsKey("default"), "no default response"));

        // Then: one property set per wire direction
        JsonObject response = component(document, CREATE_RESPONSE);
        JsonObject request = component(document, CREATE_REQUEST);
        Set<String> responseNames = propertyNames(response, CREATE_RESPONSE);
        Set<String> requestNames = propertyNames(request, CREATE_REQUEST);
        assertAll(
                "the wire shapes of Account",
                () -> assertFalse(response.containsKey("$id"), () -> CREATE_RESPONSE + " has no $id: " + response),
                () -> assertEquals(ACCOUNT_OUTPUT_NAMES, responseNames, "the property names of " + CREATE_RESPONSE),
                () -> assertTrue(
                        requestNames.contains("display_name"),
                        () -> CREATE_REQUEST + " has display_name: " + requestNames),
                () -> assertTrue(
                        requestNames.contains("password"), () -> CREATE_REQUEST + " has password: " + requestNames),
                () -> assertFalse(requestNames.contains("id"), () -> CREATE_REQUEST + " has no id: " + requestNames));

        // Then: the real response body validates against the component resolved within the whole document
        assertEquals(200, created.statusCode(), () -> "POST answers 200: " + created.bodyAsString());
        assertNotNull(created.body(), "POST answers with a body");
        JsonObject body = new JsonObject(created.body());
        OutputUnit verdict = validateAgainstComponent(document, CREATE_RESPONSE, body);
        assertAll(
                "the real response body " + body.encode(),
                () -> assertTrue(
                        Boolean.TRUE.equals(verdict.getValid()),
                        () -> "the body validates against " + CREATE_RESPONSE + ": " + verdict.getErrors()),
                () -> assertTrue(
                        responseNames.containsAll(body.fieldNames()),
                        () -> "the body's keys " + body.fieldNames() + " are published names " + responseNames));

        // Then: the document is a valid OpenAPI 3.1 document
        OpenApi31Toolchain.Verdict toolchain = OpenApi31Toolchain.validate(vertx, document.copy());
        assertTrue(toolchain.valid(), () -> "Invalid OpenAPI 3.1 document: " + toolchain.problems());
    }

    @Test
    @DisplayName(
            "A Response return publishes only the runtime-determined default response, and declared content wins over the return type")
    void dynamicAndExplicitResponsesPublishAsDeclared() throws Exception {
        // Given: the accounts application deployed with an enabled public document
        assertDeployed();

        // When: the document is fetched
        JsonObject document = publishedDocument(AccountsApi.NAME);

        // Then: dynamic publishes exactly the payload-less default response
        JsonObject dynamic = responses(document, ReportResource.ROUTE + ReportResource.DYNAMIC_PATH, "get");
        assertEquals(
                new JsonObject().put("default", new JsonObject().put("description", "Response determined at runtime")),
                dynamic,
                "the responses of dynamic");

        // Then: explicit publishes its declared 200 and 404, in that order
        JsonObject explicit = responses(document, ReportResource.ROUTE + ReportResource.EXPLICIT_PATH, "get");
        assertEquals(List.of("200", "404"), List.copyOf(explicit.fieldNames()), "the response keys of explicit");
        JsonObject ok = explicit.getJsonObject("200");
        JsonObject notFound = explicit.getJsonObject("404");
        JsonObject content = ok.getJsonObject("content");
        assertAll(
                "the declared responses of explicit",
                () -> assertEquals("The B view", ok.getString("description"), "the description of 200"),
                () -> assertEquals("Not found", notFound.getString("description"), "the description of 404"),
                () -> assertFalse(notFound.containsKey("content"), () -> "404 carries no content: " + notFound),
                () -> assertNotNull(content, () -> "200 carries content: " + ok));
        assertEquals(List.of(JSON), List.copyOf(content.fieldNames()), "the media types of explicit's 200");
        JsonObject schema = content.getJsonObject(JSON).getJsonObject("schema");
        assertNotNull(schema, () -> "explicit's JSON media type must carry a schema: " + content.encode());
        assertEquals(COMPONENTS_PREFIX + EXPLICIT_200, schema.getString("$ref"), "the schema reference of 200");

        // Then: the declared component describes ViewB, and nothing describes ViewA
        JsonObject viewB = component(document, EXPLICIT_200);
        JsonObject schemas = schemas(document);
        String encodedSchemas = schemas.encode();
        assertAll(
                "the components of explicit",
                () -> assertEquals(
                        VIEW_B_NAMES, propertyNames(viewB, EXPLICIT_200), "the properties of " + EXPLICIT_200),
                () -> assertFalse(
                        schemas.containsKey(EXPLICIT_INFERRED),
                        () -> "no inferred component " + EXPLICIT_INFERRED + "; keys: " + schemas.fieldNames()),
                () -> assertAll(
                        "no component carries a ViewA name",
                        VIEW_A_NAMES.stream()
                                .<Executable>map(name -> () -> assertFalse(
                                        encodedSchemas.contains("\"" + name + "\""),
                                        () -> "no component carries '" + name + "'"))));

        // Then: the document is a valid OpenAPI 3.1 document
        OpenApi31Toolchain.Verdict toolchain = OpenApi31Toolchain.validate(vertx, document.copy());
        assertTrue(toolchain.valid(), () -> "Invalid OpenAPI 3.1 document: " + toolchain.problems());
    }

    // --- Helpers ---

    private static void assertDeployed() {
        assertNull(deployment.failure(), () -> "the accounts deployment failed: " + deployment.failure());
        assertNotNull(deployment.port(), "the accounts deployment published a port");
    }

    /** Fetches and parses the public JSON document of an application; it must answer {@code 200}. */
    private static JsonObject publishedDocument(String name) throws Exception {
        String path = InputAssemblyIT.documentPath(name, InputAssemblyIT.JSON_FORM);
        assertEquals(DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + name + "/openapi.json", path, "the document URL");
        HttpResponse<Buffer> response =
                Futures.await(client.get(deployment.port(), HOST, path).send(), StartupDeployments.BOUND);
        assertEquals(200, response.statusCode(), () -> "GET " + path + ": " + response.bodyAsString());
        return new JsonObject(response.body());
    }

    /** Returns the component schemas of a document, failing when there are none. */
    private static JsonObject schemas(JsonObject document) {
        JsonObject components = document.getJsonObject("components");
        assertNotNull(components, () -> "the document has components: " + document.encode());
        JsonObject schemas = components.getJsonObject("schemas");
        assertNotNull(schemas, () -> "the document has component schemas: " + components.encode());
        return schemas;
    }

    /** Returns one component schema, failing with the published keys when it is absent. */
    private static JsonObject component(JsonObject document, String name) {
        JsonObject schemas = schemas(document);
        JsonObject component = schemas.getJsonObject(name);
        assertNotNull(component, () -> "component '" + name + "' is published; keys: " + schemas.fieldNames());
        return component;
    }

    /** Returns the property names of a component, failing when it has no {@code properties}. */
    private static Set<String> propertyNames(JsonObject component, String name) {
        JsonObject properties = component.getJsonObject("properties");
        assertNotNull(properties, () -> "component '" + name + "' has properties: " + component.encode());
        return Set.copyOf(properties.fieldNames());
    }

    /** Returns one operation's responses, failing with what is there when they are absent. */
    private static JsonObject responses(JsonObject document, String path, String method) {
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, () -> "the document has paths: " + document.encode());
        JsonObject item = paths.getJsonObject(path);
        assertNotNull(item, () -> "the document has path " + path + "; paths: " + paths.fieldNames());
        JsonObject operation = item.getJsonObject(method);
        assertNotNull(operation, () -> "path " + path + " has " + method + ": " + item.encode());
        JsonObject responses = operation.getJsonObject("responses");
        assertNotNull(responses, () -> method + " " + path + " has responses: " + operation.encode());
        return responses;
    }

    /**
     * Validates an instance against a component resolved within a copy of the whole published
     * document, dereferenced under a fixed URI.
     */
    private static OutputUnit validateAgainstComponent(JsonObject document, String component, Object instance) {
        try {
            // The repository writes into what it dereferences: hand it a copy.
            SchemaRepository repository =
                    SchemaRepository.create(SCHEMA_OPTIONS).dereference(DOCUMENT_URI, JsonSchema.of(document.copy()));
            Validator validator = repository.validator(DOCUMENT_URI + COMPONENTS_PREFIX + pointerEscape(component));
            return validator.validate(instance);
        } catch (RuntimeException failed) {
            return fail("component '" + component + "' must resolve and evaluate within the document", failed);
        }
    }

    /** Escapes a JSON Pointer reference token (RFC 6901). */
    private static String pointerEscape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
