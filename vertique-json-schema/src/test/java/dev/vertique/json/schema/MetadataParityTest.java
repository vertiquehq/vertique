// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static dev.vertique.json.schema.SchemaAssertions.propertyClosure;
import static dev.vertique.json.schema.SchemaAssertions.textValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.Validator;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C4: per-shape parity proof that the design's floor-plus-supplement architecture holds — generating
 * with a {@link Validator} supplied produces byte-identical output to generating without one, for five
 * shapes chosen to each exercise a different corner: a scoped boolean the schema library's own module
 * alone renders, a container-element (type-argument) constraint, a renamed setter whose method name
 * matches neither the field nor a name-derived convention, a custom-prefixed builder method, and a
 * non-{@code Default}-group constraint. The one shape that is <em>not</em> asserted here as a parity
 * case (two {@code @Pattern} constraints, S5) is intentionally documented as a real divergence in
 * {@link MetadataConstraintSourceCoverageTest#twoPatternsUnderTheWalkAloneRenderNeitherPattern()}.
 */
class MetadataParityTest {

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    private static String withValidator(Type type, Validator validator) {
        return AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), validator)
                .generateCanonical(type);
    }

    private static String withoutValidator(Type type) {
        return AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile()).generateCanonical(type);
    }

    private static void assertParity(Type type, Validator validator) {
        String with = withValidator(type, validator);
        String without = withoutValidator(type);
        assertEquals(
                without,
                with,
                "generating with a validator supplied must be byte-identical to generating without one for " + type);
    }

    @Test
    @DisplayName("C4: @AssertTrue boolean renders const:true identically with and without a validator")
    void assertTrueBooleanRendersConstTrueEitherWay() {
        Validator validator = MetadataTestValidators.plain();
        assertParity(MetadataFixtures.AssertTrueDto.class, validator);

        JsonNode document = assertCanonicalForm(withoutValidator(MetadataFixtures.AssertTrueDto.class));
        assertEquals(
                "true",
                document.at("/properties/flag/const").toString(),
                "sanity: the floor alone must already render const:true; document: " + document);
    }

    @Test
    @DisplayName("C4: Optional<@Size(max = 3) String> renders identically with and without a validator")
    void optionalContainerElementSizeRendersIdenticallyEitherWay() {
        Validator validator = MetadataTestValidators.plain();
        assertParity(MetadataFixtures.OptionalSizeDto.class, validator);
    }

    @Test
    @DisplayName("C4: a @JsonSetter(\"level\") renamed setter's field constraint renders identically either way")
    void renamedSetterFieldConstraintRendersIdenticallyEitherWay() {
        Validator validator = MetadataTestValidators.plain();
        assertParity(MetadataFixtures.ConfigureSetterDto.class, validator);

        JsonNode document = assertCanonicalForm(withoutValidator(MetadataFixtures.ConfigureSetterDto.class));
        List<JsonNode> closure = propertyClosure(document, "level");
        assertEquals(
                List.of(1),
                SchemaAssertions.keywordValues(closure, "minimum").stream()
                        .map(JsonNode::intValue)
                        .toList(),
                "sanity: the field's @Min(1) must still land on the wire name \"level\"; document: " + document);
    }

    @Test
    @DisplayName(
            "C4: a @JsonPOJOBuilder(withPrefix = \"put\") builder's built-field constraint renders identically either way")
    void putPrefixedBuilderFieldConstraintRendersIdenticallyEitherWay() {
        Validator validator = MetadataTestValidators.plain();
        assertParity(MetadataFixtures.PutPrefixBuiltDto.class, validator);
    }

    @Test
    @DisplayName(
            "C4: a non-Default-group constraint stays rendered as on main (stricter than actual enforcement), either way")
    void nonDefaultGroupConstraintRendersIdenticallyEitherWay() {
        // Documented divergence from actual Bean Validation enforcement: @NotNull(groups =
        // AdminGroup.class) is only ever *enforced* when a caller validates against AdminGroup
        // explicitly, but this schema unconditionally marks the property required — exactly matching
        // main (no validator, no group concept at all), which the metadata supplement's own group
        // filter deliberately never narrows away from (see MetadataConstraintSourceCoverageTest
        // .nonDefaultGroupOnVisibleMemberStaysRequiredLikeMain). The generated schema is therefore
        // stricter than actual runtime enforcement for this shape, by design.
        Validator validator = MetadataTestValidators.plain();
        assertParity(MetadataFixtures.NonDefaultGroupDto.class, validator);

        JsonNode document = assertCanonicalForm(withoutValidator(MetadataFixtures.NonDefaultGroupDto.class));
        assertTrue(
                textValues(List.of(document), "required").contains("secret"),
                "the schema must render this non-Default-group constraint as required, stricter than actual"
                        + " enforcement; document: " + document);
    }
}
