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
import org.junit.jupiter.api.Test;

class OpenApi31ToolchainTest {

    private static final String PARAMETER_SCHEMA_POINTER = "/paths/~1things/get/parameters/0/schema";

    @Test
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
