// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.json.schema.OutputUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the keyword vocabulary the schema walks and the error classification share: the literal, named-member
 * and structural keyword sets, and the keyword a validation error names.
 */
class SchemaKeywordsTest {

    @Test
    @DisplayName("the pattern keywords are the JSON Schema names")
    void patternKeywordsAreTheSchemaNames() {
        assertThat(SchemaKeywords.PATTERN).isEqualTo("pattern");
        assertThat(SchemaKeywords.PATTERN_PROPERTIES).isEqualTo("patternProperties");
    }

    @Test
    @DisplayName("the literal keywords are the five whose value is data rather than schema")
    void literalKeywordsAreTheFiveDataKeywords() {
        assertThat(SchemaKeywords.LITERAL_KEYWORDS)
                .containsExactlyInAnyOrder("const", "enum", "default", "examples", "example");
    }

    @Test
    @DisplayName("the named-member keywords are the four whose members are schemas whatever their name")
    void namedMemberKeywordsAreTheFourContainers() {
        assertThat(SchemaKeywords.NAMED_MEMBER_KEYWORDS)
                .containsExactlyInAnyOrder("properties", "patternProperties", "$defs", "dependentSchemas");
    }

    @Test
    @DisplayName("the structural keywords are the fourteen traversal wrappers, additionalProperties included")
    void structuralKeywordsAreTheFourteenWrappers() {
        assertThat(SchemaKeywords.STRUCTURAL_KEYWORDS)
                .containsExactlyInAnyOrder(
                        "properties",
                        "additionalProperties",
                        "items",
                        "prefixItems",
                        "allOf",
                        "anyOf",
                        "oneOf",
                        "if",
                        "then",
                        "else",
                        "not",
                        "$ref",
                        "unevaluatedProperties",
                        "unevaluatedItems");
    }

    @Test
    @DisplayName("the three keyword sets cannot be modified")
    void theKeywordSetsAreImmutable() {
        for (Set<String> keywords : List.of(
                SchemaKeywords.LITERAL_KEYWORDS,
                SchemaKeywords.NAMED_MEMBER_KEYWORDS,
                SchemaKeywords.STRUCTURAL_KEYWORDS)) {
            assertThatThrownBy(() -> keywords.add("extra")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            nullValues = "NULL",
            value = {
                "NULL, NULL",
                "'', NULL",
                "'#/', NULL",
                "'#/properties/name/minLength', minLength",
                "'minLength', minLength",
                "'#/properties', properties",
                "'#/$ref', $ref"
            })
    @DisplayName("the keyword is the last segment of a keyword location, and absent for a missing or empty one")
    void extractKeywordReturnsTheLastSegment(String keywordLocation, String expected) {
        assertThat(SchemaKeywords.extractKeyword(keywordLocation)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "#/properties",
                "#/properties/name/additionalProperties",
                "#/allOf",
                "#/properties/pet/$ref",
                "#/unevaluatedItems"
            })
    @DisplayName("a location ending in a structural keyword is structural, additionalProperties included")
    void structuralLocationsAreStructural(String keywordLocation) {
        assertThat(SchemaKeywords.isStructuralLocation(keywordLocation)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"#/properties/name/minLength", "#/required", "#/properties/name/type", "minimum", "#/"})
    @DisplayName("a location ending in a constraint keyword, or an empty segment, is not structural")
    void constraintLocationsAreNotStructural(String keywordLocation) {
        assertThat(SchemaKeywords.isStructuralLocation(keywordLocation)).isFalse();
    }

    @Test
    @DisplayName("a missing or empty location is not structural")
    void aMissingLocationIsNotStructural() {
        assertThat(SchemaKeywords.isStructuralLocation(null)).isFalse();
        assertThat(SchemaKeywords.isStructuralLocation("")).isFalse();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            nullValues = "NULL",
            value = {
                "'#/properties/name/maxLength', maxLength",
                "'#/required', required",
                "'#/properties', NULL",
                "'#/additionalProperties', NULL",
                "'', NULL",
                "NULL, NULL"
            })
    @DisplayName("the constraint keyword of an error is its last segment, and absent for a structural wrapper or"
            + " no location")
    void constraintKeywordOfAnError(String keywordLocation, String expected) {
        OutputUnit error = new OutputUnit().setValid(false).setKeywordLocation(keywordLocation);

        assertThat(SchemaKeywords.constraintKeyword(error)).isEqualTo(expected);
    }
}
