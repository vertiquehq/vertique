// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit assertions against the compile-time generated {@code /openapi.json} spec (no server
 * boot), proving the exact schema shape swagger-maven-plugin emits for the {@code JsonProfilesDemoResource}
 * DTOs (json-004, slice S6).
 *
 * <p>The spec is regenerated at {@code compile} time by {@code swagger-maven-plugin-jakarta} into
 * {@code target/classes/openapi.json}; this test reads it straight off the test classpath.
 *
 * <p><strong>These assertions are the RED-phase proof for two independent, currently-partial
 * framework behaviors:</strong>
 *
 * <ul>
 *   <li><strong>JDK8 {@code Optional} unwrapping</strong> — swagger-core's Jackson-backed model
 *       resolver treats {@code java.util.Optional<T>} as a native Jackson {@code ReferenceType} and
 *       unwraps it to {@code T}'s schema directly (no {@code present}/{@code empty} wrapper object,
 *       no {@code required} entry). This holds for {@code Optional<String>} and
 *       {@code Optional<List<String>>} (asserted in {@link #tags_isUnwrappedArrayOfString()}), but
 *       {@code NOT} for {@code java.util.OptionalInt} — a non-generic class with no
 *       {@code ReferenceType} registration, so it resolves as its own JavaBean schema
 *       ({@code empty}/{@code present}/{@code asInt} properties) rather than a scalar. See
 *       {@link #rank_isNotScalarUnwrapped_currentGap()} for the as-observed (non-conforming) shape.
 *   <li><strong>{@code BigDecimal} string wire form</strong> — {@code pom.xml}'s
 *       {@code modelConverterClasses} does not yet register {@code BigDecimalModelConverter}
 *       alongside {@code FutureModelConverter} (that registration is the green-phase change for this
 *       slice), so {@code PriceQuote.amount}/{@code discount} still resolve to the Jackson-default
 *       JSON {@code number} schema instead of the {@code vertique-strict} decimal-string schema.
 * </ul>
 */
class OpenApiSchemaAssertionsTest {

    private static JsonNode schemas;

    /**
     * Loads {@code /openapi.json} from the test classpath once for all assertions.
     *
     * @throws IOException if the spec cannot be read or parsed
     */
    @BeforeAll
    static void loadSpec() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = OpenApiSchemaAssertionsTest.class.getResourceAsStream("/openapi.json")) {
            assertNotNull(in, "openapi.json must be on the test classpath (generated at compile time)");
            JsonNode root = mapper.readTree(in);
            schemas = root.path("components").path("schemas");
            assertTrue(schemas.isObject(), "components/schemas must be present in the generated spec");
        }
    }

    // --- a. OptionalGreeting.nickname: Optional<String> unwraps to a plain string schema ---

    @Test
    @DisplayName(
            "OptionalGreeting.nickname is a plain string schema, not an object with present/empty, and is not required")
    void nickname_isUnwrappedPlainString_notRequired() {
        JsonNode optionalGreeting = schema("OptionalGreeting");
        JsonNode nickname = property(optionalGreeting, "nickname");

        assertEquals(
                "string",
                nickname.path("type").asText(),
                "nickname must resolve to a plain string schema (Optional<String> unwrapped), not an object schema"
                        + " wrapping present/empty");
        assertFalse(nickname.has("present"), "nickname schema must not carry a present property");
        assertFalse(nickname.has("empty"), "nickname schema must not carry an empty property");
        assertFalse(
                isRequired(optionalGreeting, "nickname"),
                "nickname must not appear in OptionalGreeting's required array");
    }

    // --- b. OptionalGreeting.tags: Optional<List<String>> unwraps to an array-of-string schema ---

    @Test
    @DisplayName("OptionalGreeting.tags is an array-of-string schema (Optional<List<String>> unwrapped)")
    void tags_isUnwrappedArrayOfString() {
        JsonNode optionalGreeting = schema("OptionalGreeting");
        JsonNode tags = property(optionalGreeting, "tags");

        assertEquals("array", tags.path("type").asText(), "tags must resolve to an array schema");
        assertEquals(
                "string",
                tags.path("items").path("type").asText(),
                "tags array elements must resolve to a plain string schema");
        assertFalse(isRequired(optionalGreeting, "tags"), "tags must not appear in OptionalGreeting's required array");
    }

    // --- c. OptionalGreeting.rank: OptionalInt scalar-unwrap (current framework gap) ---

    @Test
    @DisplayName("OptionalGreeting.rank is a scalar integer/number schema (OptionalInt unwrapped) — currently a gap")
    void rank_isNotScalarUnwrapped_currentGap() {
        JsonNode optionalGreeting = schema("OptionalGreeting");
        JsonNode rank = property(optionalGreeting, "rank");

        String type = rank.path("type").asText();
        assertTrue(
                "integer".equals(type) || "number".equals(type),
                "rank should resolve to a scalar integer/number schema for a properly unwrapped OptionalInt, but was: "
                        + rank);
    }

    // --- d. PriceQuote.amount: BigDecimal string wire form (red until BigDecimalModelConverter is registered) ---

    @Test
    @DisplayName(
            "PriceQuote.amount is the vertique-strict decimal-string schema — red until BigDecimalModelConverter is registered")
    void amount_isDecimalStringSchema() {
        JsonNode priceQuote = schema("PriceQuote");
        JsonNode amount = property(priceQuote, "amount");

        assertEquals("string", amount.path("type").asText(), "amount must resolve to a string schema");
        assertEquals("decimal", amount.path("format").asText(), "amount must carry the decimal format");
        assertEquals(
                "-?[0-9]+(\\.[0-9]+)?", amount.path("pattern").asText(), "amount must carry the plain decimal pattern");
        assertEquals(100, amount.path("maxLength").asInt(), "amount must carry the 100-character max length");
    }

    // --- e. PriceQuote.discount: Optional<BigDecimal> unwrap + decimal string form (red until green phase) ---

    @Test
    @DisplayName(
            "PriceQuote.discount is the vertique-strict decimal-string schema (Optional<BigDecimal> unwrapped) — red until BigDecimalModelConverter is registered")
    void discount_isUnwrappedDecimalStringSchema() {
        JsonNode priceQuote = schema("PriceQuote");
        JsonNode discount = property(priceQuote, "discount");

        assertEquals("string", discount.path("type").asText(), "discount must resolve to a string schema");
        assertEquals("decimal", discount.path("format").asText(), "discount must carry the decimal format");
        assertEquals(
                "-?[0-9]+(\\.[0-9]+)?",
                discount.path("pattern").asText(),
                "discount must carry the plain decimal pattern");
        assertEquals(100, discount.path("maxLength").asInt(), "discount must carry the 100-character max length");
        assertFalse(isRequired(priceQuote, "discount"), "discount must not appear in PriceQuote's required array");
    }

    // --- f. Guard: OptionalGreeting carries no BigDecimal (decimal-format) property ---

    @Test
    @DisplayName("OptionalGreeting has no string/format=decimal property (no BigDecimal in the vertique-profile DTO)")
    void optionalGreeting_hasNoDecimalFormatProperty() {
        JsonNode optionalGreeting = schema("OptionalGreeting");
        JsonNode properties = optionalGreeting.path("properties");

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode propertySchema = entry.getValue();
            boolean isDecimalFormatted =
                    "string".equals(propertySchema.path("type").asText())
                            && "decimal".equals(propertySchema.path("format").asText());
            assertFalse(
                    isDecimalFormatted,
                    "OptionalGreeting property '" + entry.getKey() + "' must not be a decimal-format string schema");
        }
    }

    // --- Helpers ---

    /**
     * Looks up a named schema under {@code components/schemas}, failing the test with a clear
     * message if it is absent.
     *
     * @param name the schema name
     * @return the schema node
     */
    private static JsonNode schema(String name) {
        JsonNode node = schemas.path(name);
        assertTrue(node.isObject(), "components/schemas/" + name + " must be present in the generated spec");
        return node;
    }

    /**
     * Looks up a named property under a schema's {@code properties} object, failing the test with a
     * clear message if it is absent.
     *
     * @param schemaNode   the owning schema node
     * @param propertyName the property name
     * @return the property's schema node
     */
    private static JsonNode property(JsonNode schemaNode, String propertyName) {
        JsonNode node = schemaNode.path("properties").path(propertyName);
        assertTrue(node.isObject(), "property '" + propertyName + "' must be present on schema: " + schemaNode);
        return node;
    }

    /**
     * Checks whether {@code propertyName} appears in {@code schemaNode}'s {@code required} array.
     * Absence of the {@code required} array entirely counts as not required.
     *
     * @param schemaNode   the owning schema node
     * @param propertyName the property name to look for
     * @return {@code true} if {@code propertyName} is listed in {@code required}
     */
    private static boolean isRequired(JsonNode schemaNode, String propertyName) {
        JsonNode required = schemaNode.path("required");
        if (!required.isArray()) {
            return false;
        }
        for (JsonNode entry : required) {
            if (propertyName.equals(entry.asText())) {
                return true;
            }
        }
        return false;
    }
}
