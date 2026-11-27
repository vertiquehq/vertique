// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonProfileRegistry;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@code describeMapLike} publishes for a {@code Map} subclass carrying a type-use
 * constraint on its own supertype's type argument — the shape that exercises the renderer's overlay
 * source ({@code getAnnotatedSuperclass()}/{@code getAnnotatedInterfaces()}).
 *
 * <p>The renderer's type-use-overlay step reads this exact supertype {@code AnnotatedType} ({@code
 * ValuePositionRenderer.mapValueSlotOfClass}) and renders {@code maxLength} there, both generated as a
 * type and as a property, so {@code Tags}'s {@code @Size(max = 3)} constraint on its supertype's type
 * argument appears in the published {@code additionalProperties} rather than the open, unconstrained
 * {@code {"type": "string"}}.
 *
 * <p>Both of this package's own constraint-source modes are exercised and asserted identical, following
 * {@link MetadataConstraintSourceCoverageTest}'s walk-vs-metadata pattern: {@link
 * AnnotationJsonSchemaGenerator#forInputProfile(JsonProfileCodec)} (walk only, no {@link
 * jakarta.validation.Validator} supplement) and {@link
 * AnnotationJsonSchemaGenerator#forInputProfile(JsonProfileCodec, jakarta.validation.Validator)}
 * (walk plus a {@link MetadataConstraintSource} supplement). The two modes render identically here
 * because {@link MetadataConstraintSource} joins a constraint by its member's wire name, and a map
 * value's type-use constraint sits on a type argument, not a named member, so the supplement has
 * nothing to join by either — this is a distinct root cause from the type-use overlay itself, but it
 * produces the same constrained rendering in both modes.
 */
class MapSubclassTypeUseConstraintTest {

    /** The rendered, constrained published schema of {@code Tags}'s value type. */
    private static final String CONSTRAINED_STRING_VALUE = "{\"maxLength\":3,\"type\":\"string\"}";

    private static JsonProfileCodec profile() {
        return new DefaultJsonProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
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
    @DisplayName("A Map subclass's supertype type-use constraint is rendered, generated as a type")
    void mapSubclassTypeUseConstraintIsRendered_asType() {
        String[] rendered = additionalPropertiesBothModes(Tags.class);
        assertEquals(CONSTRAINED_STRING_VALUE, rendered[0], "walk-only mode");
        assertEquals(CONSTRAINED_STRING_VALUE, rendered[1], "validator-supplemented metadata mode");
    }

    @Test
    @DisplayName("A Map subclass's supertype type-use constraint is rendered, generated as a property")
    void mapSubclassTypeUseConstraintIsRendered_asProperty() {
        String[] rendered = additionalPropertiesBothModes(TagsHolder.class);
        assertEquals(CONSTRAINED_STRING_VALUE, rendered[0], "walk-only mode");
        assertEquals(CONSTRAINED_STRING_VALUE, rendered[1], "validator-supplemented metadata mode");
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
