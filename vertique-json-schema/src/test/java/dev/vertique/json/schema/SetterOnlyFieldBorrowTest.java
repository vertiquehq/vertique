// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.Jackson2JsonProfileCodec;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code InputPropertyDescriber#borrowFieldAttributes} joins a setter-bound property to its backing
 * field through {@code BeanPropertyDefinition#getField()} rather than a raw field-name scan.
 * {@code getField()} is {@code null} when the only accessor Jackson associates with the property is the
 * setter — a private field with a setter and no getter, no public field either — so the join falls
 * back to the field whose Java name equals the property's implied name, and the constraint the
 * developer wrote on that field is still borrowed onto the published property.
 */
class SetterOnlyFieldBorrowTest {

    private static JsonProfileCodec profile() {
        return new Jackson2JsonProfileCodec() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            protected ObjectMapper mapper() {
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

    /** A private, constrained field bound only through a setter — no getter, no public field. */
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
    @DisplayName("a write-only private field's constraint is borrowed onto the published property")
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
                "a private field bound only through a setter (no getter, no public field) must"
                        + " still have its own @Size(max = 3) borrowed onto the published property —"
                        + " BeanPropertyDefinition#getField() is null for this shape, so the join falls back"
                        + " to the field named by the setter's implied name; document: " + document);
    }

    @Test
    @DisplayName("control: the same shape with a getter still borrows the constraint")
    void writeAndReadableFieldConstraintIsStillBorrowed() {
        JsonNode document = document(SetterAndGetter.class);
        JsonNode level = document.path("properties").path("level");

        assertFalse(
                level.isMissingNode(),
                "level must be published: the setter/getter pair binds it; document: " + document);
        assertEquals(
                3,
                level.path("maxLength").asInt(-1),
                "control: a field reachable through a getter is borrowed — getField() resolves"
                        + " normally, so the constraint is borrowed through it; document: " + document);
    }

    // --- A transient field with BOTH a getter and a setter, no validator ---

    /**
     * A private, {@code transient}, {@code @Max}-constrained numeric field bound through both a getter
     * and a setter. {@code BeanPropertyDefinition#getField()} is {@code null} for a transient field
     * (Jackson's property definition has no field member for it), so the implied-name fallback in
     * {@link dev.vertique.json.schema.InputPropertyDescriber#borrowFieldAttributes} is the path to the
     * constraint — and it must fire whether or not a getter is also present, so the field's
     * {@code @Max(10)} is borrowed onto the published property.
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
    @DisplayName("a transient numeric field with a getter and a setter keeps its @Max without a validator")
    void transientNumericFieldWithGetterAndSetterKeepsMaxWithoutAValidator() {
        JsonNode document = document(TransientNumericField.class);
        JsonNode level = document.path("properties").path("level");

        assertFalse(
                level.isMissingNode(),
                "level must still be published: the getter/setter pair binds it;" + " document: " + document);
        assertEquals(
                10,
                level.path("maximum").asInt(-1),
                "a transient field's own @Max(10) must still be borrowed onto the published"
                        + " property even though it also has a getter — getField() is null for a transient field"
                        + " (no field member in Jackson's property definition), so the implied-name fallback"
                        + " must fire whether or not a getter exists; document: " + document);
    }

    @Test
    @DisplayName("a transient String field with a getter and a setter keeps its @Size without a validator")
    void transientStringFieldWithGetterAndSetterKeepsSizeWithoutAValidator() {
        JsonNode document = document(TransientStringField.class);
        JsonNode token = document.path("properties").path("token");

        assertFalse(
                token.isMissingNode(),
                "token must still be published: the getter/setter pair binds it; document: " + document);
        assertEquals(
                3,
                token.path("maxLength").asInt(-1),
                "a transient field's own @Size(max = 3) must still be borrowed onto the"
                        + " published property even though it also has a getter, for the same reason as the"
                        + " numeric shape above; document: " + document);
    }

    @Test
    @DisplayName("control: a validator-backed generator also publishes the transient numeric field's @Max")
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
                        + " constraint as well; document: "
                        + document);
    }
}
