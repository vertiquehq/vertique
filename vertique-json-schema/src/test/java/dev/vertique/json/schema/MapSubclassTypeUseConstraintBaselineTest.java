// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterizes, at rest-023 T001's own pre-extraction baseline (`562decd8`), what {@code
 * describeMapLike} publishes for a {@code Map} subclass carrying a type-use constraint on its own
 * supertype's type argument — the exact fixture shape security round-2 finding S4 names as the vehicle
 * for the renderer's overlay source ({@code getAnnotatedSuperclass()}/{@code getAnnotatedInterfaces()})
 * once T003 activates map-value rendering.
 *
 * <p>This class is a characterization pinned by rest-023 T001, not a behavior fix: at this baseline,
 * {@code describeMapLike} (`InputPropertyDescriber.java` `:745`) resolves only the value type's own
 * schema through {@code resolve(context, content)}; it applies no type-use overlay at all, so {@code
 * Tags}'s {@code @Size(max = 3)} constraint on its supertype's type argument renders nowhere in the
 * published {@code additionalProperties} — today it is the open, unconstrained {@code {"type":
 * "string"}}. T003 is the task that first wires the renderer's type-use-overlay step to read this
 * exact supertype {@code AnnotatedType} and render {@code maxLength} there; when it does, these two
 * assertions are the ones T003 flips.
 *
 * <p>Both of this package's own constraint-source modes are exercised and asserted identical at this
 * baseline, following {@link MetadataConstraintSourceCoverageTest}'s walk-vs-metadata pattern: {@link
 * AnnotationJsonSchemaGenerator#forInputProfile(JsonMapperProfile)} (walk only, no {@link
 * jakarta.validation.Validator} supplement) and {@link
 * AnnotationJsonSchemaGenerator#forInputProfile(JsonMapperProfile, jakarta.validation.Validator)}
 * (walk plus a {@link MetadataConstraintSource} supplement). The two modes render identically here
 * because {@link MetadataConstraintSource} joins a constraint by its member's wire name, and a map
 * value's type-use constraint sits on a type argument, not a named member, so the supplement has
 * nothing to join by either — this is a distinct root cause from the type-use overlay's own absence,
 * but it produces the same unconstrained baseline in both modes.
 */
class MapSubclassTypeUseConstraintBaselineTest {

    /** The baseline (unconstrained) published schema of {@code Tags}'s value type. */
    private static final String UNCONSTRAINED_STRING_VALUE = "{\"type\":\"string\"}";

    private static JsonMapperProfile profile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    /**
     * Generates {@code type}'s input document, in both constraint-source modes, and returns each
     * document's own published {@code additionalProperties} (following the {@code $ref} indirection
     * when present, as {@link AnySetterMapSubclassValueTypeTest#extras(Class)} does).
     *
     * @param type the type to generate
     * @return the published {@code additionalProperties} text under walk-only mode, then under the
     *     validator-supplemented metadata mode
     */
    private static String[] additionalPropertiesBothModes(Class<?> type) {
        JsonNode walkDocument = assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
        JsonNode metadataDocument = assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile(), MetadataTestValidators.plain())
                        .generateCanonical(type));
        return new String[] {additionalProperties(walkDocument), additionalProperties(metadataDocument)};
    }

    private static String additionalProperties(JsonNode document) {
        JsonNode holder = document.path("properties").path("tags");
        JsonNode target = holder.isMissingNode() ? document : holder;
        JsonNode extras = target.path("additionalProperties");
        JsonNode reference = extras.path("$ref");
        if (reference.isTextual() && reference.asText().startsWith("#/")) {
            extras = document.at(reference.asText().substring(1));
        }
        return extras.isMissingNode() ? "<absent> in " + document : extras.toString();
    }

    @Test
    @DisplayName("A Map subclass's supertype type-use constraint is not rendered at baseline, generated as a type")
    void mapSubclassTypeUseConstraintIsNotRenderedAtBaseline_asType() {
        String[] rendered = additionalPropertiesBothModes(Tags.class);
        assertEquals(UNCONSTRAINED_STRING_VALUE, rendered[0], "walk-only mode");
        assertEquals(UNCONSTRAINED_STRING_VALUE, rendered[1], "validator-supplemented metadata mode");
    }

    @Test
    @DisplayName("A Map subclass's supertype type-use constraint is not rendered at baseline, generated as a property")
    void mapSubclassTypeUseConstraintIsNotRenderedAtBaseline_asProperty() {
        String[] rendered = additionalPropertiesBothModes(TagsHolder.class);
        assertEquals(UNCONSTRAINED_STRING_VALUE, rendered[0], "walk-only mode");
        assertEquals(UNCONSTRAINED_STRING_VALUE, rendered[1], "validator-supplemented metadata mode");
    }

    // --- Fixtures ---

    /** A {@code Map} subclass carrying a type-use constraint on its own supertype's type argument. */
    static class Tags extends HashMap<String, @Size(max = 3) String> {}

    /** A body type describing {@link Tags} at a named property position. */
    static final class TagsHolder {

        /** The described property. */
        public Tags tags;
    }
}
