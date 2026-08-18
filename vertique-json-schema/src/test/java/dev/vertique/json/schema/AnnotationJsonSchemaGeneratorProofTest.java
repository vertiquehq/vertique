// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static dev.vertique.json.schema.SchemaAssertions.collectMemberTexts;
import static dev.vertique.json.schema.SchemaAssertions.conjunctiveClosure;
import static dev.vertique.json.schema.SchemaAssertions.golden;
import static dev.vertique.json.schema.SchemaAssertions.keywordValues;
import static dev.vertique.json.schema.SchemaAssertions.propertyClosure;
import static dev.vertique.json.schema.SchemaAssertions.text;
import static dev.vertique.json.schema.SchemaAssertions.textValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import dev.vertique.core.json.JsonMapperProfile;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Thin end-to-end proof of {@link AnnotationJsonSchemaGenerator}'s three construction modes: the
 * REST-compatible default mode, and the profile-aware input and output modes applying the real
 * built-in {@code vertique-strict} profile's {@code BigDecimal} schema override.
 *
 * <p>Composition is asserted <em>semantically</em>, never by literal shape: Victools' retained
 * {@code ALLOF_CLEANUP_AT_THE_END} normalization may legally flatten an {@code allOf} wrapper, so the
 * probe below walks every conjunctive location that applies to a property — the node itself, its
 * direct {@code allOf} branches, and locally resolved {@code $ref} targets — and asserts that both
 * the profile's and the property's contributions remain discoverable. The exact post-cleanup normal
 * form is additionally pinned by committed golden bytes under {@code src/test/resources/golden/}.
 */
class AnnotationJsonSchemaGeneratorProofTest {

    /** The swagger-2 sentinel emitted for an unset {@code @Schema} default value. */
    private static final String DEFAULT_SENTINEL = "##default";

    /** The anchored plain-decimal pattern the {@code vertique-strict} profile fragment declares. */
    private static final String PROFILE_PATTERN = "^-?[0-9]+(\\.[0-9]+)?$";

    /** The narrower two-fraction-digit pattern {@code RefinedAmountDto} declares on its property. */
    private static final String PROPERTY_PATTERN = "^-?[0-9]+\\.[0-9]{2}$";

    // --- Tests ---

    @Test
    @DisplayName("Default mode reproduces the REST Victools configuration and emits compact, key-sorted JSON")
    void defaultModeMatchesRestConfigurationStructurally() {
        // Given: a POJO with a @NotNull field and a @Size-constrained field.
        // When: the default-mode generator produces its canonical document.
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(ProofFixtures.ConstrainedDto.class);

        // Then: the document is valid, compact, and recursively key-sorted JSON.
        JsonNode document = assertCanonicalForm(canonical);

        // Then: the required-field contribution survived.
        List<JsonNode> required = keywordValues(conjunctiveClosure(document, document), "required");
        assertTrue(
                required.stream().anyMatch(node -> containsText(node, "name")),
                "required must contain 'name' somewhere in the root's conjunctive closure");

        // Then: it is structurally equal (order-independent) to the schema the REST Victools
        // configuration produces for the same type, modulo the sentinel strip.
        JsonNode restEquivalent = restConfigured(ProofFixtures.ConstrainedDto.class);
        assertEquals(restEquivalent, document, "default mode must match the REST Victools configuration structurally");
    }

    @Test
    @DisplayName("Input mode applies the vertique-strict BigDecimal fragment to the property")
    void inputProfileAppliesStrictBigDecimalFragment() {
        // Given: the real built-in vertique-strict profile.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();

        // When: an input-mode generator produces the document for a BigDecimal-bearing POJO.
        String canonical =
                AnnotationJsonSchemaGenerator.forInputProfile(strict).generateCanonical(ProofFixtures.AmountDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        // Then: the property's effective schema carries the whole strict fragment...
        List<JsonNode> applicable = propertyClosure(document, "amount");
        assertTextValues(applicable, "type", List.of("string"));
        assertTextValues(applicable, "pattern", List.of(PROFILE_PATTERN));
        assertTextValues(applicable, "format", List.of("decimal"));
        assertIntValues(applicable, "maxLength", List.of(100));

        // ...and never the mapper-derived numeric wire type.
        assertFalse(
                textValues(applicable, "type").contains("number"),
                "the overridden BigDecimal property must not carry type 'number'");

        // Then: the exact post-cleanup normal form is pinned.
        assertEquals(golden("strict-input-amount.json"), canonical, "input-mode golden bytes");
    }

    @Test
    @DisplayName("Output mode applies the same symmetric BOTH override byte-for-byte")
    void outputProfileAppliesStrictBigDecimalFragment() {
        // Given: the real built-in vertique-strict profile, whose only override is BOTH-directional.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();

        // When: both directions generate the same type.
        String input =
                AnnotationJsonSchemaGenerator.forInputProfile(strict).generateCanonical(ProofFixtures.AmountDto.class);
        String output =
                AnnotationJsonSchemaGenerator.forOutputProfile(strict).generateCanonical(ProofFixtures.AmountDto.class);

        // Then: the documents are byte-identical, and match the committed golden bytes.
        assertEquals(input, output, "a symmetric BOTH override must produce identical input and output documents");
        assertEquals(golden("strict-input-amount.json"), output, "output-mode golden bytes");
    }

    @Test
    @DisplayName("A property refinement conjoins with the profile fragment without overwriting it")
    void propertyRefinementConjoinsWithoutOverwrite() {
        // Given: the strict profile and a property additionally narrowed by Swagger metadata.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();

        // When: the input-mode generator produces the document.
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(strict)
                .generateCanonical(ProofFixtures.RefinedAmountDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        // Then: BOTH patterns remain discoverable through conjunctive paths.
        List<JsonNode> applicable = propertyClosure(document, "amount");
        List<String> patterns = textValues(applicable, "pattern");
        assertTrue(
                patterns.contains(PROFILE_PATTERN),
                "the profile fragment's pattern must survive the property refinement; found " + patterns);
        assertTrue(
                patterns.contains(PROPERTY_PATTERN),
                "the property's refining pattern must survive the profile fragment; found " + patterns);

        // Then: BOTH maximum lengths remain discoverable — the property's narrower bound never
        // silently replaces the profile's, and the profile's never suppresses the property's.
        List<Integer> maxLengths = intValues(applicable, "maxLength");
        assertTrue(maxLengths.contains(100), "the profile fragment's maxLength must survive; found " + maxLengths);
        assertTrue(maxLengths.contains(20), "the property's refining maxLength must survive; found " + maxLengths);

        // Then: the exact post-cleanup normal form is pinned.
        assertEquals(golden("strict-refined-amount.json"), canonical, "refined-property golden bytes");
    }

    @Test
    @DisplayName("A resolved List<BigDecimal> element receives the profile fragment")
    void listOfBigDecimalElementReceivesFragment() {
        // Given: the strict profile and a resolved List<BigDecimal>.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();

        // When: the input-mode generator produces the document.
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(strict)
                .generateCanonical(ProofFixtures.LIST_OF_BIG_DECIMAL);
        JsonNode document = assertCanonicalForm(canonical);

        // Then: the array's element schema carries the fragment.
        List<JsonNode> roots = conjunctiveClosure(document, document);
        assertTrue(
                roots.stream().anyMatch(node -> "array".equals(text(node.get("type")))),
                "a resolved List<BigDecimal> must generate an array schema");

        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode root : roots) {
            JsonNode items = root.get("items");
            if (items != null) {
                elements.addAll(conjunctiveClosure(document, items));
            }
        }
        assertFalse(elements.isEmpty(), "the array schema must declare an element schema");
        assertTextValues(elements, "type", List.of("string"));
        assertTextValues(elements, "pattern", List.of(PROFILE_PATTERN));
        assertTextValues(elements, "format", List.of("decimal"));
        assertIntValues(elements, "maxLength", List.of(100));
    }

    @Test
    @DisplayName("Independent instances and repeated calls produce byte-identical documents")
    void determinismAcrossIndependentInstances() {
        // Given: two independent generators per mode, built from equal inputs.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();
        AnnotationJsonSchemaGenerator defaultOne = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        AnnotationJsonSchemaGenerator defaultTwo = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        AnnotationJsonSchemaGenerator inputOne = AnnotationJsonSchemaGenerator.forInputProfile(strict);
        AnnotationJsonSchemaGenerator inputTwo = AnnotationJsonSchemaGenerator.forInputProfile(strict);

        // When/Then: independent instances agree byte-for-byte.
        assertEquals(
                defaultOne.generateCanonical(ProofFixtures.ConstrainedDto.class),
                defaultTwo.generateCanonical(ProofFixtures.ConstrainedDto.class),
                "independent default-mode instances must agree");
        assertEquals(
                inputOne.generateCanonical(ProofFixtures.RefinedAmountDto.class),
                inputTwo.generateCanonical(ProofFixtures.RefinedAmountDto.class),
                "independent input-mode instances must agree");

        // When/Then: repeated calls on one instance agree byte-for-byte.
        assertEquals(
                inputOne.generateCanonical(ProofFixtures.RefinedAmountDto.class),
                inputOne.generateCanonical(ProofFixtures.RefinedAmountDto.class),
                "repeated calls on one instance must agree");
    }

    @Test
    @DisplayName("The ##default sentinel is stripped everywhere while a legitimate default survives")
    void defaultSentinelStrippedEverywhere() {
        // Given/When: a type whose swagger metadata emits both the sentinel and a real default.
        String canonical =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(ProofFixtures.SentinelDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        // Then: no member named "default" carries the sentinel anywhere in the document.
        List<String> defaults = new ArrayList<>();
        collectMemberTexts(document, "default", defaults);
        assertFalse(defaults.contains(DEFAULT_SENTINEL), "the ##default sentinel must not survive anywhere");

        // Then: the unrelated legitimate default value is preserved.
        assertTrue(defaults.contains("eur"), "a legitimate default value must be preserved; found " + defaults);
    }

    @Test
    @DisplayName("Representative REST body types generate the same document modulo key order")
    void restDifferentialFixture() {
        // Given: three representative body types mirroring the existing REST fixtures.
        Map<String, Type> fixtures = new LinkedHashMap<>();
        fixtures.put("nested object", ProofFixtures.WithNested.class);
        fixtures.put("generic collection", ProofFixtures.LIST_OF_ITEM_DTO);
        fixtures.put("closed polymorphism", ProofFixtures.Animal.class);

        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        for (Map.Entry<String, Type> fixture : fixtures.entrySet()) {
            // When: the shared generator and the REST Victools configuration each produce a schema.
            JsonNode generated = assertCanonicalForm(generator.generateCanonical(fixture.getValue()));
            JsonNode restEquivalent = restConfigured(fixture.getValue());

            // Then: the documents are structurally equal modulo key order and the sentinel strip.
            assertEquals(restEquivalent, generated, "REST differential mismatch for the " + fixture.getKey() + " body");
        }
    }

    // --- REST-configuration differential helper ---

    /**
     * Generates a schema through a Victools configuration built inline exactly as
     * {@code AnnotationSchemaSource} builds it today, then strips the swagger sentinel — proving
     * configuration parity without depending on the REST module.
     *
     * @param type the body type to generate a schema for
     * @return the REST-equivalent schema node
     */
    private static JsonNode restConfigured(Type type) {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .with(new Swagger2Module());
        ObjectNode schema = new SchemaGenerator(builder.build()).generateSchema(type);
        stripSentinel(schema);
        return schema;
    }

    /**
     * Recursively removes every object member whose key is {@code default} and whose value is exactly
     * the string {@code ##default}.
     *
     * @param node the node to strip in place
     */
    private static void stripSentinel(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode value = object.get("default");
            if (value != null && value.isTextual() && DEFAULT_SENTINEL.equals(value.textValue())) {
                object.remove("default");
            }
            object.properties().forEach(entry -> stripSentinel(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(AnnotationJsonSchemaGeneratorProofTest::stripSentinel);
        }
    }

    // --- Local assertion helpers (not shared: no other test class needs them) ---

    /**
     * Asserts that each expected textual value is present among a keyword's conjunctive occurrences.
     *
     * @param nodes    the conjunctive locations
     * @param keyword  the keyword
     * @param expected the values that must be present
     */
    private static void assertTextValues(List<JsonNode> nodes, String keyword, List<String> expected) {
        List<String> actual = textValues(nodes, keyword);
        for (String value : expected) {
            assertTrue(actual.contains(value), "expected " + keyword + "='" + value + "'; found " + actual);
        }
    }

    /**
     * Collects the integral values a keyword takes across a set of conjunctive locations.
     *
     * @param nodes   the conjunctive locations
     * @param keyword the keyword to collect
     * @return every integral value found
     */
    private static List<Integer> intValues(List<JsonNode> nodes, String keyword) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            JsonNode value = node.get(keyword);
            if (value != null && value.isNumber()) {
                values.add(value.intValue());
            }
        }
        return values;
    }

    /**
     * Asserts that each expected integral value is present among a keyword's conjunctive occurrences.
     *
     * @param nodes    the conjunctive locations
     * @param keyword  the keyword
     * @param expected the values that must be present
     */
    private static void assertIntValues(List<JsonNode> nodes, String keyword, List<Integer> expected) {
        List<Integer> actual = intValues(nodes, keyword);
        for (Integer value : expected) {
            assertTrue(actual.contains(value), "expected " + keyword + "=" + value + "; found " + actual);
        }
    }

    /**
     * Returns whether an array node (or textual node) contains the given text.
     *
     * @param node the node to inspect
     * @param text the text to look for
     * @return {@code true} when the node carries that text
     */
    private static boolean containsText(JsonNode node, String text) {
        if (node.isTextual()) {
            return text.equals(node.textValue());
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual() && text.equals(element.textValue())) {
                    return true;
                }
            }
        }
        return false;
    }
}
