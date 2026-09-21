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
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
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

    private static JsonNode document(Type type, Validator validator) {
        return assertCanonicalForm(AnnotationJsonSchemaGenerator.forInputProfile(profile(), validator)
                .generateCanonical(type));
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

    // --- Reopened finding: a transient field with BOTH a getter and a setter, no validator ---

    /**
     * A private, {@code transient}, {@code @Max}-constrained numeric field bound through both a getter
     * and a setter. {@code BeanPropertyDefinition#getField()} is {@code null} for a transient field
     * (Jackson's property definition has no field member for it), so W1's own fallback in {@link
     * dev.vertique.json.schema.InputPropertyDescriber#borrowFieldAttributes} would be the only remaining
     * path to the constraint — but that fallback is bounded to {@code candidate.getGetter() == null},
     * which this shape does not satisfy (it has a getter), so neither path borrows the field's
     * {@code @Max(10)} onto the published property.
     */
    static final class TransientNumericField {
        @Max(10)
        private transient int level;

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }
    }

    /** The same shape for a {@code @Size(max = 3)} {@code String} field, rather than a numeric one. */
    static final class TransientStringField {
        @Size(max = 3)
        private transient String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    @Test
    @DisplayName(
            "REOPENED: a transient numeric field with a getter and a setter loses its @Max without a" + " validator")
    void transientNumericFieldWithGetterAndSetterLosesMaxWithoutAValidator() {
        JsonNode document = document(TransientNumericField.class);
        JsonNode level = document.path("properties").path("level");

        assertFalse(
                level.isMissingNode(),
                "level must still be published: the getter/setter pair binds it;" + " document: " + document);
        assertEquals(
                10,
                level.path("maximum").asInt(-1),
                "REOPENED DECISIVE: a transient field's own @Max(10) must still be borrowed onto the published"
                        + " property even though it also has a getter — getField() is null for a transient field"
                        + " (no field member in Jackson's property definition), and the write-only fallback added"
                        + " for W1 only fires when there is also no getter, so this shape currently falls through"
                        + " both paths unborrowed; document: " + document);
    }

    @Test
    @DisplayName(
            "REOPENED: a transient String field with a getter and a setter loses its @Size without a" + " validator")
    void transientStringFieldWithGetterAndSetterLosesSizeWithoutAValidator() {
        JsonNode document = document(TransientStringField.class);
        JsonNode token = document.path("properties").path("token");

        assertFalse(
                token.isMissingNode(),
                "token must still be published: the getter/setter pair binds it; document: " + document);
        assertEquals(
                3,
                token.path("maxLength").asInt(-1),
                "REOPENED DECISIVE: a transient field's own @Size(max = 3) must still be borrowed onto the"
                        + " published property even though it also has a getter, for the same reason as the"
                        + " numeric shape above; document: " + document);
    }

    @Test
    @DisplayName("REOPENED control: a validator-backed generator still publishes the transient numeric field's"
            + " @Max, unaffected by the no-validator gap above")
    void transientNumericFieldConstraintIsPublishedUnderAValidator() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = document(TransientNumericField.class, validator);
        JsonNode level = document.path("properties").path("level");

        assertFalse(level.isMissingNode(), "document: " + document);
        assertEquals(
                10,
                level.path("maximum").asInt(-1),
                "control: the Bean Validation metadata supplement reads the field directly (not through"
                        + " Jackson's getField() join), so a validator-backed generator must publish the"
                        + " constraint regardless of the no-validator gap this class otherwise proves; document: "
                        + document);
    }
}
