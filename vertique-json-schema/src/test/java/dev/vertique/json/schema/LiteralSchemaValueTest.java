// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.Validator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The alias-expansion walks never treat a literal schema value as schema.
 *
 * <p>The values of {@code const}, {@code enum}, {@code default}, {@code examples} and {@code example}
 * are JSON data. A data object carrying the generator's private alias-plan keyword is an ordinary
 * member of that data: expansion must neither strip it nor execute it, and the marker refusal must
 * not read it as a published property. A schema position carrying the keyword, which only a profile
 * override fragment can write, is refused instead of silently stripped (vertiquehq/vertique-dev#599).
 * A property whose own name is one of those keywords is still a schema, so a plan beneath it is still
 * expanded.
 */
class LiteralSchemaValueTest {

    /** The generator's private alias-plan keyword. */
    private static final String MARKER = AnnotationJsonSchemaGenerator.AliasExpansion.MARKER;

    /** A data object carrying the keyword as an ordinary member. */
    private static final String DATA_WITH_MARKER = "{\"" + MARKER + "\":\"mandatory\",\"value\":\"ok\"}";

    /** The same data object without the keyword, which the literal must reject. */
    private static final String DATA_WITHOUT_MARKER = "{\"value\":\"ok\"}";

    /** A body type whose only property is republished through the profile's fragment. */
    static final class AmountHolder {

        /** The property the override fragment describes. */
        public BigDecimal amount;
    }

    /** A body type whose aliased property is published under the name of a literal keyword. */
    static final class HoldsAnAliasedTypeUnderALiteralKeyword {

        /** An aliased type, published under the property name {@code const}. */
        @JsonProperty("const")
        public AliasDescriptionTest.OptionalAliasedQuantity quantity;
    }

    private static AnnotationJsonSchemaGenerator generatorFor(String fragment) {
        JsonMapperProfile profile = HardeningFixtures.profile(
                "literal-fixture",
                List.of(JsonSchemaTypeOverride.both(BigDecimal.class, JsonSchemaFragment.parse(fragment))));
        return AnnotationJsonSchemaGenerator.forInputProfile(profile);
    }

    private static boolean valid(String document, String amount) {
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/");
        Validator validator = Validator.create(JsonSchema.of(new JsonObject(document)), options);
        return validator.validate(new JsonObject("{\"amount\":" + amount + "}")).getValid();
    }

    /**
     * Reads literal data as a tree, compared by value so the canonical key order does not matter.
     *
     * @param json the literal's JSON text
     * @return the parsed tree
     */
    private static JsonNode data(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (JsonProcessingException malformed) {
            throw new AssertionError(malformed);
        }
    }

    /**
     * Returns the published schema of {@code amount}, through a local reference when the fragment was
     * shared as a definition.
     *
     * @param document the generated document
     * @return the schema of {@code amount}
     */
    private static JsonNode amountSchema(JsonNode document) {
        JsonNode amount = document.path("properties").path("amount");
        JsonNode reference = amount.path("$ref");
        return reference.isTextual() ? document.at(reference.asText().substring(1)) : amount;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"const", "enum"})
    @DisplayName("A literal carrying the alias-plan keyword survives expansion and still validates as written")
    void literalCarryingTheKeywordSurvivesExpansion(String keyword) {
        String literal = keyword.equals("const") ? DATA_WITH_MARKER : "[" + DATA_WITH_MARKER + "]";
        String document = generatorFor("{\"" + keyword + "\":" + literal + "}").generateCanonical(AmountHolder.class);
        JsonNode parsed = assertCanonicalForm(document);

        assertAll(
                () -> assertEquals(
                        data(literal),
                        amountSchema(parsed).path(keyword),
                        "the " + keyword + " literal must be published exactly as written; document: " + document),
                () -> assertTrue(
                        valid(document, DATA_WITH_MARKER),
                        "the object the literal names must still be accepted; document: " + document),
                () -> assertFalse(
                        valid(document, DATA_WITHOUT_MARKER),
                        "an object missing the literal's member must still be rejected; document: " + document));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"const", "enum"})
    @DisplayName("A literal shaped like a published keyword property or a plan is not refused")
    void literalShapedLikeACollisionIsNotRefused(String keyword) {
        String property = "{\"properties\":{\"" + MARKER + "\":{\"type\":\"string\"}}}";
        String plan = "{\"" + MARKER + "\":{\"aliases\":{\"a\":[\"" + MARKER + "\"]},\"strict\":false}}";
        for (String data : List.of(property, plan)) {
            String literal = keyword.equals("const") ? data : "[" + data + "]";
            String document =
                    generatorFor("{\"" + keyword + "\":" + literal + "}").generateCanonical(AmountHolder.class);

            assertEquals(
                    data(literal),
                    amountSchema(assertCanonicalForm(document)).path(keyword),
                    "the " + keyword + " literal is data, not a published property or a plan; document: " + document);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{\"type\":\"string\",\"" + MARKER + "\":\"mandatory\"}",
                "{\"anyOf\":[{\"type\":\"string\",\"" + MARKER + "\":{\"aliases\":{},\"strict\":false}}]}",
                "{\"properties\":{\"inner\":{\"" + MARKER + "\":{\"aliases\":{},\"strict\":true}}},\"type\":\"object\"}"
            })
    @DisplayName("A fragment carrying the alias-plan keyword at a schema position is refused")
    void fragmentCarryingTheKeywordAtASchemaPositionIsRefused(String fragment) {
        JsonSchemaGenerationException refused = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generatorFor(fragment).generateCanonical(AmountHolder.class),
                "a schema-position keyword would be stripped, or executed as a plan, without trace");
        assertTrue(
                refused.getMessage().contains(MARKER),
                "the refusal must name the reserved keyword; was: " + refused.getMessage());
    }

    @Test
    @DisplayName("A plan beneath a property named like a literal keyword is still expanded")
    void planBeneathAPropertyNamedLikeALiteralKeywordIsExpanded() {
        String document = AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.profile("plain", List.of()))
                .generateCanonical(HoldsAnAliasedTypeUnderALiteralKeyword.class);
        JsonNode parsed = assertCanonicalForm(document);
        JsonNode nested = parsed.path("properties").path("const");
        JsonNode reference = nested.path("$ref");
        if (reference.isTextual()) {
            nested = parsed.at(reference.asText().substring(1));
        }

        assertFalse(document.contains(MARKER), "no published document carries the keyword; document: " + document);
        assertTrue(
                nested.path("properties").has("qty"),
                "the aliased type under the property 'const' must list its alias; document: " + document);
    }
}
