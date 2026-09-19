// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The regex precompilation walk never treats a literal schema value as schema: a {@code pattern}
 * member inside {@code const}, {@code enum}, {@code default}, {@code examples} or {@code example} data
 * is data, so an unparseable one must not fail the router build. A property whose own name is one of
 * those keywords is still a schema, so an unparseable pattern beneath it still fails.
 */
class WebValidationLiteralPatternTest {

    /** An unparseable pattern: an unclosed group. */
    private static final String UNPARSEABLE_PATTERN = "(";

    private final WebValidationStrategy strategy =
            new WebValidationStrategy(JaxRsConfig.builder().build());

    private static JaxRsOperationDescriptor bodyOperation() {
        return StubDescriptors.builder()
                .httpMethod("POST")
                .routeTemplate("/things")
                .parameters(List.of())
                .body(new BodyDescriptor(Object.class, null, List.of()))
                .build();
    }

    private void gateFor(JsonObject bodySchema) {
        strategy.gateFor(
                bodyOperation(),
                OperationSchemas.builder().bodySchema(bodySchema).build());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"const", "enum"})
    @DisplayName("A pattern inside literal data is not compiled and leaves the literal unchanged")
    void patternInsideLiteralDataIsNotCompiled(String keyword) {
        JsonObject data = new JsonObject().put("pattern", UNPARSEABLE_PATTERN).put("value", "ok");
        Object literal = keyword.equals("const") ? data : new JsonArray().add(data);
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject().put("thing", new JsonObject().put(keyword, literal)));
        Object original = keyword.equals("const") ? data.copy() : ((JsonArray) literal).copy();

        assertDoesNotThrow(
                () -> gateFor(bodySchema),
                "a pattern member inside a " + keyword + " literal is data, not a regular expression");
        assertEquals(
                original,
                bodySchema.getJsonObject("properties").getJsonObject("thing").getValue(keyword),
                "the literal must survive unchanged; schema: " + bodySchema);
    }

    @Test
    @DisplayName("An unparseable pattern beneath a property named like a literal keyword still fails")
    void patternBeneathAPropertyNamedLikeALiteralKeywordStillFails() {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "const",
                                        new JsonObject().put("type", "string").put("pattern", UNPARSEABLE_PATTERN)));

        RestConfigurationException failure = assertThrows(RestConfigurationException.class, () -> gateFor(bodySchema));
        assertTrue(
                failure.getMessage().contains("/properties/const/pattern"),
                "the failure must name the real pattern position; was: " + failure.getMessage());
    }
}
