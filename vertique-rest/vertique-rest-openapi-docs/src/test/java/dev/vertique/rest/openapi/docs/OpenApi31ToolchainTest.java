// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.openapi.docs.OpenApi31Toolchain.Verdict;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the test-only OpenAPI 3.1 validation toolchain the documentation tests rely on: it
 * accepts a valid document without touching the caller's copy, and rejects documents whose
 * {@code info.version}, component schemas or parameter schemas are invalid, naming the JSON
 * pointer of the offending part. It also accepts what OpenAPI 3.1.1 allows but the contract loader
 * rejects (relative server URLs, operations without responses) without hiding a malformed absolute
 * server URL or a malformed responses member.
 */
@DisplayName("The OpenAPI 3.1 validation toolchain")
class OpenApi31ToolchainTest {

    private static final String PARAMETER_SCHEMA_POINTER = "/paths/~1things/get/parameters/0/schema";

    @Test
    @DisplayName("validates a copy of the document and rejects invalid documents naming the offending pointer")
    void validatesACopyAndRejectsInvalidDocuments() {
        // Given four documents and a valid twin of the fourth
        JsonObject valid = minimalValid();
        JsonObject validBefore = valid.copy();
        JsonObject missingVersion = missingInfoVersion();
        JsonObject badComponentSchema = withBadComponentSchema();
        JsonObject badParameterSchema = withParameterSchema(new JsonObject().put("type", 7));
        JsonObject goodParameterSchema = withParameterSchema(new JsonObject().put("type", "string"));

        // When each is validated
        Verdict validVerdict = OpenApi31Toolchain.validate(valid);
        Verdict missingVersionVerdict = OpenApi31Toolchain.validate(missingVersion);
        Verdict componentVerdict = OpenApi31Toolchain.validate(badComponentSchema);
        Verdict parameterVerdict = OpenApi31Toolchain.validate(badParameterSchema);
        Verdict goodParameterVerdict = OpenApi31Toolchain.validate(goodParameterSchema);

        // Then the valid document passes and the caller's object is untouched
        assertTrue(validVerdict.valid(), "minimal document: " + validVerdict.problems());
        assertEquals(validBefore, valid, "the caller's document must not be mutated");
        assertFalse(valid.encode().contains("__absolute_uri__"), "the caller's document must not be annotated");
        OpenApi31Toolchain.assertValid(valid);

        // And a document without info.version is rejected
        assertFalse(missingVersionVerdict.valid(), "missing info.version must be rejected");

        // And an invalid component schema is rejected, naming its pointer
        assertFalse(componentVerdict.valid(), "invalid component schema must be rejected");
        assertTrue(
                componentVerdict.problems().stream().anyMatch(p -> p.startsWith("/components/schemas/Bad")),
                "problems must name /components/schemas/Bad: " + componentVerdict.problems());

        // And an invalid parameter schema is rejected, naming its pointer, while a valid one is accepted
        assertFalse(parameterVerdict.valid(), "invalid parameter schema must be rejected");
        assertTrue(
                parameterVerdict.problems().stream().anyMatch(p -> p.startsWith(PARAMETER_SCHEMA_POINTER)),
                "problems must name " + PARAMETER_SCHEMA_POINTER + ": " + parameterVerdict.problems());
        assertTrue(goodParameterVerdict.valid(), "valid parameter schema: " + goodParameterVerdict.problems());

        // And assertValid fails for an invalid document
        assertThrows(AssertionError.class, () -> OpenApi31Toolchain.assertValid(badComponentSchema));
    }

    @Test
    @DisplayName("accepts relative server URLs and operations without responses without mutating the caller")
    void acceptsRelativeServerUrlsAndOperationsWithoutResponses() {
        // Given a published-shape document with a mount-path server URL, an operation without
        // responses and a referenced request body schema, and a twin whose server URL is "/"
        JsonObject mountPath = publishedShape("/api/public");
        JsonObject mountPathBefore = mountPath.copy();
        JsonObject root = publishedShape("/");

        // When both are validated
        Verdict mountPathVerdict = OpenApi31Toolchain.validate(mountPath);
        Verdict rootVerdict = OpenApi31Toolchain.validate(root);

        // Then both are valid
        assertTrue(mountPathVerdict.valid(), "server URL /api/public: " + mountPathVerdict.problems());
        assertTrue(rootVerdict.valid(), "server URL /: " + rootVerdict.problems());

        // And the caller's document is unchanged: no placeholder response, no placeholder origin
        assertEquals(mountPathBefore, mountPath, "the caller's document must not be mutated");
        assertFalse(
                mountPath
                        .getJsonObject("paths")
                        .getJsonObject("/items")
                        .getJsonObject("post")
                        .containsKey("responses"),
                "the caller's operation must still have no responses");
        assertEquals(
                "/api/public",
                mountPath.getJsonArray("servers").getJsonObject(0).getString("url"),
                "the caller's server URL must stay relative");
    }

    @Test
    @DisplayName("still rejects a malformed absolute server URL as a contract problem")
    void stillRejectsAMalformedServerUrl() {
        // Given two documents whose absolute server URL is malformed
        JsonObject spaceInHost = minimalValid().put("servers", serverUrl("https://exa mple.test/"));
        JsonObject unknownScheme = minimalValid().put("servers", serverUrl("unknown-scheme://example.test/"));

        // When each is validated
        Verdict spaceInHostVerdict = OpenApi31Toolchain.validate(spaceInHost);
        Verdict unknownSchemeVerdict = OpenApi31Toolchain.validate(unknownScheme);

        // Then each is rejected with a contract problem
        assertRejectedByContract(spaceInHostVerdict, "a space in the host");
        assertRejectedByContract(unknownSchemeVerdict, "an unknown URL scheme");
    }

    @Test
    @DisplayName("still rejects a malformed responses member as a contract problem")
    void stillRejectsAMalformedResponses() {
        // Given two documents whose operation carries a malformed responses member
        JsonObject responsesString = withResponses("nope");
        JsonObject responseNumber = withResponses(new JsonObject().put("200", 5));

        // When each is validated
        Verdict stringVerdict = OpenApi31Toolchain.validate(responsesString);
        Verdict numberVerdict = OpenApi31Toolchain.validate(responseNumber);

        // Then each is rejected with a contract problem
        assertRejectedByContract(stringVerdict, "responses as a string");
        assertRejectedByContract(numberVerdict, "a response as a number");
    }

    private static void assertRejectedByContract(Verdict verdict, String label) {
        assertFalse(verdict.valid(), label + " must be rejected");
        assertTrue(
                verdict.problems().stream().anyMatch(p -> p.startsWith("contract:")),
                label + " must be a contract problem: " + verdict.problems());
    }

    private static JsonArray serverUrl(String url) {
        return new JsonArray().add(new JsonObject().put("url", url));
    }

    private static JsonObject withResponses(Object responses) {
        JsonObject operation = new JsonObject().put("operationId", "listThings").put("responses", responses);
        return minimalValid().put("paths", new JsonObject().put("/things", new JsonObject().put("get", operation)));
    }

    /**
     * The shape this module publishes: a relative server URL (the mount path), an operation without
     * responses whose request body references a component schema carrying its own {@code $schema},
     * and the root {@code x-vertique-validation} member.
     */
    private static JsonObject publishedShape(String serverUrl) {
        JsonObject reference = new JsonObject().put("$ref", "#/components/schemas/createItem.request");
        JsonObject requestBody = new JsonObject()
                .put("content", new JsonObject().put("application/json", new JsonObject().put("schema", reference)));
        JsonObject operation = new JsonObject().put("operationId", "createItem").put("requestBody", requestBody);
        JsonObject bodySchema = new JsonObject()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("properties", new JsonObject().put("name", new JsonObject().put("type", "string")))
                .put("type", "object");
        return new JsonObject()
                .put("openapi", "3.1.1")
                .put("info", new JsonObject().put("title", "Catalog").put("version", "1.0"))
                .put("jsonSchemaDialect", "https://json-schema.org/draft/2020-12/schema")
                .put("servers", serverUrl(serverUrl))
                .put("paths", new JsonObject().put("/items", new JsonObject().put("post", operation)))
                .put(
                        "components",
                        new JsonObject().put("schemas", new JsonObject().put("createItem.request", bodySchema)))
                .put("x-vertique-validation", new JsonObject().put("patternDialect", "java.util.regex"));
    }

    private static JsonObject minimalValid() {
        return new JsonObject()
                .put("openapi", "3.1.1")
                .put("info", new JsonObject().put("title", "Toolchain probe").put("version", "1.0.0"))
                .put("paths", new JsonObject());
    }

    private static JsonObject missingInfoVersion() {
        JsonObject document = minimalValid();
        document.getJsonObject("info").remove("version");
        return document;
    }

    private static JsonObject withBadComponentSchema() {
        return minimalValid()
                .put(
                        "components",
                        new JsonObject()
                                .put(
                                        "schemas",
                                        new JsonObject().put("Bad", new JsonObject().put("minProperties", "one"))));
    }

    private static JsonObject withParameterSchema(JsonObject schema) {
        JsonObject parameter =
                new JsonObject().put("name", "limit").put("in", "query").put("schema", schema);
        JsonObject operation = new JsonObject()
                .put("parameters", new JsonArray().add(parameter))
                .put("responses", new JsonObject().put("200", new JsonObject().put("description", "OK")));
        return minimalValid().put("paths", new JsonObject().put("/things", new JsonObject().put("get", operation)));
    }
}
