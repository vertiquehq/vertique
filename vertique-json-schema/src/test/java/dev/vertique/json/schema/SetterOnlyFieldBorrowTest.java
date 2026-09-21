// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * W1 (spike/deserializer-driven-schema round 4 ruling): {@code InputPropertyDescriber#borrowFieldAttributes}
 * joins a setter-bound property to its backing field through
 * {@code BeanPropertyDefinition#getField()} (the a2538dc1 INFO-item tightening) rather than a raw
 * field-name scan. {@code getField()} is {@code null} when the only accessor Jackson associates with the
 * property is the setter — a private field with a setter and no getter, no public field either — so the
 * constraint the developer wrote on that field is no longer borrowed onto the published property. Main's
 * own field walk published it; the deserializer-driven floor, joined this way, currently does not.
 *
 * <p>The owner ruling records the fix direction (fall back to the field whose Java name equals the
 * property's implied name when {@code getField()} is {@code null} and the property has no getter) but
 * this class only authors the proof, never the production change.
 */
class SetterOnlyFieldBorrowTest {

    private static JsonMapperProfile profile() {
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            public ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return List.of();
            }
        };
    }

    private static JsonNode document(Type type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    /** W1: a private, constrained field bound only through a setter — no getter, no public field. */
    static final class SetterOnly {
        @Size(max = 3)
        private String level;

        public void setLevel(String level) {
            this.level = level;
        }
    }

    /** The control shape: the same constrained field, but also exposed through a getter. */
    static final class SetterAndGetter {
        @Size(max = 3)
        private String level;

        public void setLevel(String level) {
            this.level = level;
        }

        public String getLevel() {
            return level;
        }
    }

    @Test
    @DisplayName("W1: a write-only private field's constraint is borrowed onto the published property")
    void writeOnlyPrivateFieldConstraintIsBorrowed() {
        JsonNode document = document(SetterOnly.class);
        JsonNode level = document.path("properties").path("level");

        assertFalse(level.isMissingNode(), "level must still be published: the setter binds it; document: " + document);
        assertEquals(
                "string",
                level.path("type").asText(null),
                "sanity: the property must still be described by its Jackson-resolved type; document: " + document);
        assertEquals(
                3,
                level.path("maxLength").asInt(-1),
                "W1 DECISIVE: a private field bound only through a setter (no getter, no public field) must"
                        + " still have its own @Size(max = 3) borrowed onto the published property, exactly as"
                        + " main's field walk did — BeanPropertyDefinition#getField() is null for this shape,"
                        + " so the current wire-name join (candidate.getField()) finds nothing to borrow from;"
                        + " document: " + document);
    }

    @Test
    @DisplayName("W1 control: the same shape with a getter still borrows the constraint (unaffected)")
    void writeAndReadableFieldConstraintIsStillBorrowed() {
        JsonNode document = document(SetterAndGetter.class);
        JsonNode level = document.path("properties").path("level");

        assertFalse(
                level.isMissingNode(),
                "level must be published: the setter/getter pair binds it; document: " + document);
        assertEquals(
                3,
                level.path("maxLength").asInt(-1),
                "control: a field reachable through a getter is unaffected by W1 — getField() resolves"
                        + " normally, so the constraint must already be borrowed on unchanged production code;"
                        + " document: " + document);
    }
}
