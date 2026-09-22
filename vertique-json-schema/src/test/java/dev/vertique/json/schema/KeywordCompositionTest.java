// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static dev.vertique.json.schema.SchemaAssertions.propertyClosure;
import static dev.vertique.json.schema.SchemaAssertions.textValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Red proofs for CO-001 (the rest-021 package's carried obligation) and two sibling defects found in
 * review, all in {@link InputPropertyDescriber}'s keyword-composition seam (FR-009: a constraint
 * keyword is never removed). As of the fix now in the working tree, Defect A and Defect B are green;
 * the two sibling defects (S1, S2) are not yet fixed and remain red.
 *
 * <p>Defect A ({@link #allOfCorrectionAppendsToExistingAllOf()}, {@link
 * #allOfCorrectionSkipsDuplicatePattern()}) — {@code putKeyword(schema, "allOf", patterns)} replaced
 * an {@code allOf} array the schema already carried ({@code schema.putArray("allOf")} was
 * unconditional) instead of appending to it and skipping duplicates. {@link
 * #patternCorrectionAppendsOntoExistingAllOf()} is the contrasting proof that {@link
 * InputPropertyDescriber#mergePatternAsAllOf} — the sibling method a {@code "pattern"} correction goes
 * through — already appends correctly; only {@code putKeyword}'s {@code "allOf"} branch had the
 * defect.
 *
 * <p>Defect B ({@link #swaggerPatternNeverDropsConstraintSourcePattern()} and friends) — {@link
 * InputPropertyDescriber#translateSwagger} wrote {@code pattern}, {@code minLength}/{@code maxLength},
 * and {@code minimum}/{@code exclusiveMinimum}/{@code maximum}/{@code exclusiveMaximum}
 * unconditionally over whatever a constraint source (Bean Validation annotation, read by the
 * always-active {@link WalkConstraintSource} floor) already wrote for an unscoped member (a creator
 * parameter here), loosening or dropping it instead of composing (pattern) or keeping the stricter
 * value (a bound). {@link #swaggerStricterMaxLengthStillWinsContrast()} is a contrasting control, like
 * A2: the pre-fix unconditional overwrite happened to coincide with the stricter value there, so it
 * was green even before the fix and stays green after — it never exercised the defect.
 *
 * <p>S1 ({@link #anySetterMaxPropertiesNeverLoosensStricterConstraintSourceBound()}) — the sibling
 * defect in {@code describeExtras}: an any-setter map's {@code @Schema(minProperties/maxProperties)}
 * is written with a plain {@code definition.put(...)} over a {@code @Size}-derived value, the same
 * unconditional-overwrite shape as Defect B, not yet fixed.
 *
 * <p>S2 ({@code applyEncodedKeywords}, ~1066-1097, the scoped-member counterpart of {@link
 * InputPropertyDescriber#applyCorrection}) — its {@code "allOf"} correction falls through to the
 * unconditional {@code schema.set(key, value)} at the end of the method, the scoped-member analogue of
 * Defect A, also not yet fixed. No test is added for it here: the method is {@code private}, not
 * package-private, so it is not directly callable from this test class, and the task's own fallback
 * for that case is to skip.
 */
class KeywordCompositionTest {

    // ============================================================ Defect A: putKeyword("allOf", ...)

    private static ObjectNode schemaWithAllOfPatterns(String... patterns) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "string");
        ArrayNode allOf = schema.putArray("allOf");
        for (String pattern : patterns) {
            allOf.addObject().put("pattern", pattern);
        }
        return schema;
    }

    private static List<String> allOfPatterns(ObjectNode schema) {
        List<String> patterns = new ArrayList<>();
        schema.path("allOf")
                .forEach(branch -> patterns.add(branch.path("pattern").asText()));
        return patterns;
    }

    @Test
    @DisplayName("CO-001 A1: an \"allOf\" correction must append its patterns to an existing allOf, not replace it")
    void allOfCorrectionAppendsToExistingAllOf() {
        ObjectNode schema = schemaWithAllOfPatterns("^a+$", "^b+$");

        InputPropertyDescriber.applyCorrection(schema, "allOf", List.of("^c+$"));

        assertEquals(
                List.of("^a+$", "^b+$", "^c+$"),
                allOfPatterns(schema),
                "putKeyword's \"allOf\" branch must append the new pattern subschemas onto the existing"
                        + " allOf array (FR-009: a constraint keyword is never removed), not replace it via an"
                        + " unconditional schema.putArray(\"allOf\"); schema: " + schema);
    }

    @Test
    @DisplayName("CO-001 A2 (contrast): mergePatternAsAllOf already appends onto an existing allOf, unlike putKeyword")
    void patternCorrectionAppendsOntoExistingAllOf() {
        // Same starting shape as A1 (a pre-existing allOf of two patterns), plus an independent plain
        // "pattern" keyword — the shape applyCorrection's "pattern" branch (mergePatternAsAllOf) sees.
        ObjectNode schema = schemaWithAllOfPatterns("^a+$", "^b+$");
        schema.put("pattern", "^z+$");

        InputPropertyDescriber.applyCorrection(schema, "pattern", "^d+$");

        assertEquals(
                List.of("^a+$", "^b+$", "^z+$", "^d+$"),
                allOfPatterns(schema),
                "mergePatternAsAllOf must append both the pre-existing plain \"pattern\" and the new"
                        + " correction onto the existing allOf, keeping every prior branch — the correct"
                        + " shape putKeyword's own \"allOf\" branch (A1) fails to reproduce; schema: " + schema);
        assertFalse(
                schema.has("pattern"),
                "the merged plain \"pattern\" keyword must be removed once folded into allOf; schema: " + schema);
    }

    @Test
    @DisplayName("CO-001 A3: an \"allOf\" correction must skip a pattern already present, not add a duplicate")
    void allOfCorrectionSkipsDuplicatePattern() {
        ObjectNode schema = schemaWithAllOfPatterns("^a+$", "^b+$");

        InputPropertyDescriber.applyCorrection(schema, "allOf", List.of("^a+$"));

        assertEquals(
                List.of("^a+$", "^b+$"),
                allOfPatterns(schema),
                "a pattern identical to one already in the allOf (\"^a+$\") must be skipped, never appended"
                        + " again; schema: " + schema);
    }

    // ============================================================ Defect B: translateSwagger

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    /** Generated through the floor alone (no Bean Validation supplement); {@link #translateSwagger} always runs. */
    private static JsonNode document(Class<?> type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile()).generateCanonical(type));
    }

    /**
     * A {@code @JsonCreator} constructor whose parameters each carry both a Bean Validation constraint
     * (read by the always-active {@link WalkConstraintSource} floor) and a differing/conflicting
     * {@code @Schema} metadata value, which {@link InputPropertyDescriber#translateSwagger} translates
     * unconditionally, after the floor.
     */
    static final class SwaggerNeverLoosensDto {
        private final String code;
        private final String name;
        private final String tag;
        private final int level;

        @JsonCreator
        SwaggerNeverLoosensDto(
                @JsonProperty("code") @Pattern(regexp = "^a+$") @Schema(pattern = "^b+$") String code,
                @JsonProperty("name") @Size(max = 3) @Schema(maxLength = 10) String name,
                @JsonProperty("tag") @Size(max = 10) @Schema(maxLength = 2) String tag,
                @JsonProperty("level") @Min(5) @Schema(minimum = "1") int level) {
            this.code = code;
            this.name = name;
            this.tag = tag;
            this.level = level;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getTag() {
            return tag;
        }

        public int getLevel() {
            return level;
        }
    }

    @Test
    @DisplayName("CO-001 B: a differing @Schema(pattern) must compose with, not drop, a creator parameter's @Pattern")
    void swaggerPatternNeverDropsConstraintSourcePattern() {
        JsonNode document = document(SwaggerNeverLoosensDto.class);
        List<JsonNode> closure = propertyClosure(document, "code");

        assertTrue(
                textValues(closure, "pattern").contains("^a+$"),
                "the constraint-source @Pattern(\"^a+$\") must still be enforced somewhere in the"
                        + " property's conjunctive closure after a differing @Schema(pattern = \"^b+$\") is"
                        + " translated (it must compose as an allOf, the same shape a two-source pattern"
                        + " correction already uses, never be dropped outright); document: " + document);
    }

    @Test
    @DisplayName("CO-001 B: a looser @Schema(maxLength) must not overwrite a stricter creator-parameter @Size bound")
    void swaggerMaxLengthNeverLoosensStricterConstraintSourceBound() {
        JsonNode document = document(SwaggerNeverLoosensDto.class);
        JsonNode name = document.at("/properties/name");

        assertEquals(
                3,
                name.at("/maxLength").asInt(),
                "the stricter constraint-source @Size(max = 3) must survive a looser @Schema(maxLength ="
                        + " 10); document: " + document);
    }

    @Test
    @DisplayName("CO-001 B (contrast): a stricter @Schema(maxLength) still wins over a looser creator-parameter"
            + " @Size bound")
    void swaggerStricterMaxLengthStillWinsContrast() {
        JsonNode document = document(SwaggerNeverLoosensDto.class);
        JsonNode tag = document.at("/properties/tag");

        assertEquals(
                2,
                tag.at("/maxLength").asInt(),
                "the stricter @Schema(maxLength = 2) must win over the looser constraint-source @Size(max"
                        + " = 10); document: " + document);
    }

    @Test
    @DisplayName("CO-001 B: a looser @Schema(minimum) must not overwrite a stricter creator-parameter @Min bound")
    void swaggerMinimumNeverLoosensStricterConstraintSourceBound() {
        JsonNode document = document(SwaggerNeverLoosensDto.class);
        JsonNode level = document.at("/properties/level");

        assertEquals(
                0,
                new BigDecimal(5).compareTo(level.at("/minimum").decimalValue()),
                "the stricter constraint-source @Min(5) must survive a looser @Schema(minimum = \"1\");" + " document: "
                        + document);
    }

    /**
     * A second {@code @JsonCreator} fixture covering {@link InputPropertyDescriber#translateSwagger}
     * branches {@link #SwaggerNeverLoosensDto} does not exercise: {@code minLength} kept against a
     * looser {@code @Schema(minLength)}, {@code maximum} kept against a looser
     * {@code @Schema(maximum)}, and the two "different keyword" coexistence shapes — a {@code
     * @Schema(exclusiveMaximum = true)}/{@code exclusiveMinimum = true} writes a *different* keyword
     * than the constraint source's own {@code maximum}/{@code minimum}, so both must be published
     * (applyCorrection's bound comparison only fires when the two sides share one keyword).
     */
    static final class SwaggerCoexistenceDto {
        private final String label;
        private final int score;
        private final int ceiling;
        private final int floorValue;

        @JsonCreator
        SwaggerCoexistenceDto(
                @JsonProperty("label") @Size(min = 3) @Schema(minLength = 1) String label,
                @JsonProperty("score") @Max(10) @Schema(maximum = "20") int score,
                @JsonProperty("ceiling") @Max(10) @Schema(maximum = "20", exclusiveMaximum = true) int ceiling,
                @JsonProperty("floorValue") @Min(5) @Schema(minimum = "1", exclusiveMinimum = true) int floorValue) {
            this.label = label;
            this.score = score;
            this.ceiling = ceiling;
            this.floorValue = floorValue;
        }

        public String getLabel() {
            return label;
        }

        public int getScore() {
            return score;
        }

        public int getCeiling() {
            return ceiling;
        }

        public int getFloorValue() {
            return floorValue;
        }
    }

    @Test
    @DisplayName("CO-001 B: a looser @Schema(minLength) must not overwrite a stricter creator-parameter @Size bound")
    void swaggerMinLengthNeverLoosensStricterConstraintSourceBound() {
        JsonNode document = document(SwaggerCoexistenceDto.class);
        JsonNode label = document.at("/properties/label");

        assertEquals(
                3,
                label.at("/minLength").asInt(),
                "the stricter constraint-source @Size(min = 3) must survive a looser @Schema(minLength ="
                        + " 1); document: " + document);
    }

    @Test
    @DisplayName("CO-001 B: a looser @Schema(maximum) must not overwrite a stricter creator-parameter @Max bound")
    void swaggerMaximumNeverLoosensStricterConstraintSourceBound() {
        JsonNode document = document(SwaggerCoexistenceDto.class);
        JsonNode score = document.at("/properties/score");

        assertEquals(
                0,
                new BigDecimal(10).compareTo(score.at("/maximum").decimalValue()),
                "the stricter constraint-source @Max(10) must survive a looser @Schema(maximum = \"20\");"
                        + " document: " + document);
    }

    @Test
    @DisplayName("CO-001 B: a @Schema(maximum, exclusiveMaximum = true) writes a different keyword and must coexist"
            + " with the constraint source's own maximum, not replace it")
    void swaggerExclusiveMaximumCoexistsWithConstraintSourceMaximum() {
        JsonNode document = document(SwaggerCoexistenceDto.class);
        JsonNode ceiling = document.at("/properties/ceiling");

        assertEquals(
                0,
                new BigDecimal(10).compareTo(ceiling.at("/maximum").decimalValue()),
                "the constraint-source @Max(10)'s \"maximum\" must survive: @Schema(exclusiveMaximum ="
                        + " true) writes the different keyword \"exclusiveMaximum\", never overwrites"
                        + " \"maximum\"; document: " + document);
        assertEquals(
                0,
                new BigDecimal(20).compareTo(ceiling.at("/exclusiveMaximum").decimalValue()),
                "@Schema(maximum = \"20\", exclusiveMaximum = true) must still publish its own"
                        + " \"exclusiveMaximum\" keyword, coexisting with the constraint source's"
                        + " \"maximum\"; document: " + document);
    }

    @Test
    @DisplayName("CO-001 B: a @Schema(minimum, exclusiveMinimum = true) writes a different keyword and must coexist"
            + " with the constraint source's own minimum, not replace it")
    void swaggerExclusiveMinimumCoexistsWithConstraintSourceMinimum() {
        JsonNode document = document(SwaggerCoexistenceDto.class);
        JsonNode floorValue = document.at("/properties/floorValue");

        assertEquals(
                0,
                new BigDecimal(5).compareTo(floorValue.at("/minimum").decimalValue()),
                "the constraint-source @Min(5)'s \"minimum\" must survive: @Schema(exclusiveMinimum ="
                        + " true) writes the different keyword \"exclusiveMinimum\", never overwrites"
                        + " \"minimum\"; document: " + document);
        assertEquals(
                0,
                new BigDecimal(1).compareTo(floorValue.at("/exclusiveMinimum").decimalValue()),
                "@Schema(minimum = \"1\", exclusiveMinimum = true) must still publish its own"
                        + " \"exclusiveMinimum\" keyword, coexisting with the constraint source's"
                        + " \"minimum\"; document: " + document);
    }

    // ============================================================ S1: describeExtras (any-setter map)

    /**
     * S1 (sibling defect, not yet fixed): {@code describeExtras} writes an any-setter map's {@code
     * @Schema(maxProperties)} with a plain {@code definition.put(...)} over the {@code @Size}-derived
     * {@code maxProperties} it already wrote for the same member — the same unconditional-overwrite
     * shape Defect B had for {@code translateSwagger}.
     */
    static final class AnySetterCardinalityDto {
        @Size(max = 3)
        @Schema(maxProperties = 10)
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("CO-001 S1: a looser @Schema(maxProperties) on an any-setter map must not overwrite a stricter"
            + " @Size bound (describeExtras)")
    void anySetterMaxPropertiesNeverLoosensStricterConstraintSourceBound() {
        JsonNode document = document(AnySetterCardinalityDto.class);

        assertEquals(
                3,
                document.at("/maxProperties").asInt(),
                "the stricter constraint-source @Size(max = 3) on the any-setter map must survive a"
                        + " looser @Schema(maxProperties = 10) in describeExtras; document: " + document);
    }
}
