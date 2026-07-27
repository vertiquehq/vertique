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
 * <p><strong>These assertions pin two independent schema behaviors:</strong>
 *
 * <ul>
 *   <li><strong>JDK8 {@code Optional} unwrapping</strong> — swagger-core's Jackson-backed model
 *       resolver treats {@code java.util.Optional<T>} as a native Jackson {@code ReferenceType} and
 *       unwraps it to {@code T}'s schema directly (no {@code present}/{@code empty} wrapper object,
 *       no {@code required} entry). This holds natively for {@code Optional<String>} and
 *       {@code Optional<List<String>>} (asserted in {@link #tags_isUnwrappedArrayOfString()}), but
 *       {@code NOT} for {@code java.util.OptionalInt} — a non-generic class with no
 *       {@code ReferenceType} registration, which without help resolves as its own JavaBean schema
 *       ({@code empty}/{@code present}/{@code asInt} properties). {@code pom.xml} registers
 *       {@code dev.vertique.openapi.ScalarOptionalModelConverter} to close that gap; see
 *       {@link #rank_isScalarIntegerSchema()}.
 *   <li><strong>{@code BigDecimal} string wire form</strong> — {@code pom.xml} registers
 *       {@code dev.vertique.openapi.BigDecimalModelConverter} alongside
 *       {@code FutureModelConverter}, so {@code PriceQuote.amount}/{@code discount} resolve to the
 *       {@code vertique-strict} decimal-string schema rather than the Jackson-default JSON
 *       {@code number} schema.
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
        // "omit, don't null": an empty Optional is omitted from the payload, never written as JSON
        // null, so the schema stays non-nullable and a spec-validating client rejects an explicit null.
        assertFalse(nickname.path("nullable").asBoolean(), "nickname schema must not be nullable");
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

    // --- c. OptionalGreeting.rank: OptionalInt scalar schema via ScalarOptionalModelConverter ---

    @Test
    @DisplayName("OptionalGreeting.rank is a scalar integer/int32 schema (OptionalInt), not a present/empty bean")
    void rank_isScalarIntegerSchema() {
        JsonNode optionalGreeting = schema("OptionalGreeting");
        JsonNode rank = property(optionalGreeting, "rank");

        String type = rank.path("type").asText();
        assertTrue(
                "integer".equals(type) || "number".equals(type),
                "rank must resolve to a scalar integer/number schema (ScalarOptionalModelConverter), but was: " + rank);
        assertFalse(rank.has("present"), "rank schema must not carry a present property");
        assertFalse(rank.has("empty"), "rank schema must not carry an empty property");
        assertFalse(isRequired(optionalGreeting, "rank"), "rank must not appear in OptionalGreeting's required array");
    }

    // --- d. PriceQuote.amount: BigDecimal string wire form via BigDecimalModelConverter ---

    @Test
    @DisplayName("PriceQuote.amount is the vertique-strict decimal-string schema")
    void amount_isDecimalStringSchema() {
        JsonNode priceQuote = schema("PriceQuote");
        JsonNode amount = property(priceQuote, "amount");

        assertDecimalStringSchema(amount, "amount");
    }

    // --- e. PriceQuote.discount: Optional<BigDecimal> unwrap + decimal string form ---

    @Test
    @DisplayName("PriceQuote.discount is the vertique-strict decimal-string schema (Optional<BigDecimal> unwrapped)")
    void discount_isUnwrappedDecimalStringSchema() {
        JsonNode priceQuote = schema("PriceQuote");
        JsonNode discount = property(priceQuote, "discount");

        assertDecimalStringSchema(discount, "discount");
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

    // --- g. PriceQuote.required: sku and amount are required (discount stays optional) ---

    @Test
    @DisplayName("PriceQuote's required array contains sku and amount")
    void priceQuote_requiredContainsSkuAndAmount() {
        JsonNode priceQuote = schema("PriceQuote");

        assertTrue(isRequired(priceQuote, "sku"), "sku must appear in PriceQuote's required array");
        assertTrue(isRequired(priceQuote, "amount"), "amount must appear in PriceQuote's required array");
    }

    // --- h. OptionalGreeting.required: only name is required (Optional-typed properties are
    // deliberately left unmarked, to demonstrate optionality) ---

    @Test
    @DisplayName("OptionalGreeting's required array contains only name")
    void optionalGreeting_requiredContainsOnlyName() {
        JsonNode optionalGreeting = schema("OptionalGreeting");

        assertTrue(isRequired(optionalGreeting, "name"), "name must appear in OptionalGreeting's required array");
        assertFalse(
                isRequired(optionalGreeting, "nickname"),
                "nickname must not appear in OptionalGreeting's required array");
        assertFalse(isRequired(optionalGreeting, "tags"), "tags must not appear in OptionalGreeting's required array");
        assertFalse(isRequired(optionalGreeting, "rank"), "rank must not appear in OptionalGreeting's required array");
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
     * Asserts that {@code propertyNode} is the {@code vertique-strict} decimal-string schema
     * emitted by {@code BigDecimalModelConverter}: a {@code string} type, {@code decimal} format,
     * the plain decimal pattern, and the 100-character max length.
     *
     * @param propertyNode the property schema node to assert against
     * @param label        a human-readable property name used in assertion failure messages
     */
    private static void assertDecimalStringSchema(JsonNode propertyNode, String label) {
        assertEquals("string", propertyNode.path("type").asText(), label + " must resolve to a string schema");
        assertEquals("decimal", propertyNode.path("format").asText(), label + " must carry the decimal format");
        assertEquals(
                "^-?[0-9]+(\\.[0-9]+)?$",
                propertyNode.path("pattern").asText(),
                label + " must carry the anchored plain decimal pattern");
        assertEquals(100, propertyNode.path("maxLength").asInt(), label + " must carry the 100-character max length");
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
