// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-013 (T004): which walked properties the input direction describes.
 *
 * <p>Three proofs share one fixture family, each fixture named for the shape it stands for:
 *
 * <ul>
 *   <li><strong>TP-001</strong> — the shapes the profiled input direction stopped describing: a
 *       private field behind a getter, a Lombok {@code @Builder @Jacksonized @Getter} type, a
 *       field-backed getter-only {@code List<String>} and {@code Map<String, String>}, and a type
 *       holding the first as a property. {@code @Getter} on the builder fixture is load-bearing: the
 *       same class without it stays {@code {"type":"object"}} even after the change, the gap
 *       {@code spec.md} § Known description gaps records as BG1, so a builder fixture without a
 *       getter would prove nothing about this rule.
 *   <li><strong>TP-002</strong> — an any-accessor's backing storage is excluded by member and only by
 *       member: a record component carrying {@code @JsonAnySetter} is not a named property, while a
 *       real property whose name a method any-setter merely implies stays described with its
 *       constraint.
 *   <li><strong>TP-003</strong> — behavior preservation: {@code @JsonIgnore} and read-only stay
 *       absent, write-only stays described with {@code writeOnly: true}, and every output-direction
 *       document is byte-identical to the one the generator produces at T004's parent commit.
 * </ul>
 *
 * <p>Every document is generated under the registry's built-in {@code vertique} profile — the floor
 * an unannotated REST route resolves to — so the subject is the same generator configuration the gate
 * uses, not a hand-built mapper.
 */
class ProfilePropertyDiscoveryTest {

    /**
     * The built-in {@code vertique} profile, resolved from a registry holding no application
     * profiles.
     *
     * @return the {@code vertique} profile
     */
    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    /**
     * Generates a type's input-direction document under the {@code vertique} profile.
     *
     * @param type the body type
     * @return the parsed canonical document
     */
    private static JsonNode inputDocument(Type type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile()).generateCanonical(type));
    }

    /**
     * Generates a type's output-direction document under the {@code vertique} profile.
     *
     * @param type the body type
     * @return the canonical document text
     */
    private static String outputDocument(Type type) {
        return AnnotationJsonSchemaGenerator.forOutputProfile(vertiqueProfile()).generateCanonical(type);
    }

    /**
     * Returns the document's {@code properties} member, failing when the document describes none at
     * all — the bare object shape this task corrects.
     *
     * @param document the parsed document
     * @param subject  the fixture name, for the failure message
     * @return the {@code properties} node
     */
    private static JsonNode properties(JsonNode document, String subject) {
        assertFalse(
                document.path("properties").isMissingNode(),
                subject + " must describe properties: the document is the bare object "
                        + "{\"type\":\"object\"} the input-direction discovery condition produced");
        return document.get("properties");
    }

    // --- TP-001: the restored shapes are described ---

    @Test
    @DisplayName("A private field behind a getter is described with its type and format")
    void privateFieldBehindAGetterIsDescribed() {
        JsonNode document = inputDocument(PrivateFieldBehindAGetter.class);

        JsonNode due = properties(document, "PrivateFieldBehindAGetter").path("due");
        assertEquals("string", due.path("type").asText(null), "the private LocalDate field's type must be described");
        assertEquals("date", due.path("format").asText(null), "the private LocalDate field's format must be described");
    }

    @Test
    @DisplayName("A Lombok @Builder @Jacksonized @Getter type describes both of its properties")
    void lombokBuilderTypeIsDescribed() {
        JsonNode document = inputDocument(LombokBuilderType.class);

        JsonNode members = properties(document, "LombokBuilderType");
        assertEquals("string", members.path("name").path("type").asText(null), "the builder's String property's type");
        assertEquals(
                "integer", members.path("quantity").path("type").asText(null), "the builder's Integer property's type");
    }

    @Test
    @DisplayName("A field-backed getter-only List<String> is described with its item constraint")
    void fieldBackedGetterOnlyListIsDescribed() {
        JsonNode document = inputDocument(FieldBackedGetterOnlyList.class);

        JsonNode tags = properties(document, "FieldBackedGetterOnlyList").path("tags");
        assertEquals("array", tags.path("type").asText(null), "the getter-only list's type must be described");
        assertEquals(
                "string",
                tags.path("items").path("type").asText(null),
                "the getter-only list's item constraint must be described");
    }

    @Test
    @DisplayName("A field-backed getter-only Map<String, String> is described as an object")
    void fieldBackedGetterOnlyMapIsDescribed() {
        JsonNode document = inputDocument(FieldBackedGetterOnlyMap.class);

        JsonNode labels = properties(document, "FieldBackedGetterOnlyMap").path("labels");
        assertEquals("object", labels.path("type").asText(null), "the getter-only map's type must be described");
    }

    @Test
    @DisplayName("A restored shape held as a property is described at the nested position")
    void nestedRestoredShapeIsDescribed() {
        JsonNode document = inputDocument(NestedRestoredShape.class);

        JsonNode detail = properties(document, "NestedRestoredShape").path("detail");
        assertEquals("object", detail.path("type").asText(null), "the nested property's type must be described");
        JsonNode due = detail.path("properties").path("due");
        assertEquals("string", due.path("type").asText(null), "the nested private LocalDate field's type");
        assertEquals("date", due.path("format").asText(null), "the nested private LocalDate field's format");
    }

    // --- TP-002: backing storage is excluded by member, and only by member ---

    @Test
    @DisplayName("A record component carrying @JsonAnySetter is not described as a named property")
    void recordAnySetterComponentIsNotANamedProperty() {
        JsonNode document = inputDocument(AnySetterRecord.class);

        JsonNode members = properties(document, "AnySetterRecord");
        assertFalse(
                members.has("extras"),
                "the any-setter record component must not be described as a named property: it is the "
                        + "any-setter's backing storage, not a property a client sends");
        assertEquals("string", members.path("name").path("type").asText(null), "the record's real property's type");
    }

    @Test
    @DisplayName("A real property whose name a method any-setter implies stays described with its constraint")
    void propertyNamedAfterAnAnySetterMethodIsDescribed() {
        JsonNode document = inputDocument(MethodAnySetterBesideARealProperty.class);

        JsonNode members = properties(document, "MethodAnySetterBesideARealProperty");
        assertFalse(
                members.has("extras"),
                "no backing storage may be described as a named property on the method any-setter fixture");
        assertEquals(
                3,
                members.path("attribute").path("maxLength").asInt(-1),
                "the real 'attribute' property must stay described with its maxLength: a name a "
                        + "setAttribute(String, Object) any-setter merely implies must hide nothing");
    }

    // --- TP-003: access exclusions and the output direction are unchanged ---

    @Test
    @DisplayName("An @JsonIgnore property and a read-only property stay absent from the input document")
    void ignoredAndReadOnlyStayAbsent() {
        JsonNode ignored = properties(inputDocument(IgnoredProperty.class), "IgnoredProperty");
        assertTrue(ignored.has("kept"), "the ignored fixture's ordinary property must still be described");
        assertFalse(ignored.has("secret"), "an @JsonIgnore property must stay absent from the input document");

        JsonNode readOnly = properties(inputDocument(ReadOnlyProperty.class), "ReadOnlyProperty");
        assertTrue(readOnly.has("kept"), "the read-only fixture's ordinary property must still be described");
        assertFalse(readOnly.has("issuedAt"), "a read-only property must stay absent from the input document");
    }

    @Test
    @DisplayName("A write-only property stays described with writeOnly: true")
    void writeOnlyStaysDescribed() {
        JsonNode members = properties(inputDocument(WriteOnlyProperty.class), "WriteOnlyProperty");

        assertTrue(
                members.path("password").path("writeOnly").asBoolean(false),
                "a write-only property must stay " + "described and must carry writeOnly: true");
        assertEquals("string", members.path("password").path("type").asText(null), "the write-only property's type");
    }

    /**
     * The output direction is byte-identical to T004's parent commit ({@code 3e50529d}). Each
     * expected document below was generated by that commit's generator for the same fixture and the
     * same profile, so applying the input-direction condition to the output direction — or changing
     * the output direction at all — fails here.
     */
    @Test
    @DisplayName("Every restored shape's output document is byte-identical to the parent commit's")
    void outputDirectionIsUnchanged() {
        assertEquals(
                PARENT_OUTPUT_PRIVATE_FIELD,
                outputDocument(PrivateFieldBehindAGetter.class),
                "the private-field shape's output document must be unchanged");
        assertEquals(
                PARENT_OUTPUT_LOMBOK_BUILDER,
                outputDocument(LombokBuilderType.class),
                "the Lombok builder's output document must be unchanged");
        assertEquals(
                PARENT_OUTPUT_GETTER_ONLY_LIST,
                outputDocument(FieldBackedGetterOnlyList.class),
                "the getter-only list's output document must be unchanged");
        assertEquals(
                PARENT_OUTPUT_GETTER_ONLY_MAP,
                outputDocument(FieldBackedGetterOnlyMap.class),
                "the getter-only map's output document must be unchanged");
        assertEquals(
                PARENT_OUTPUT_NESTED,
                outputDocument(NestedRestoredShape.class),
                "the nested shape's output document must be unchanged");
    }

    // --- Parent-commit output documents (TP-003) ---

    /** {@link PrivateFieldBehindAGetter}'s output document at T004's parent. */
    private static final String PARENT_OUTPUT_PRIVATE_FIELD =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"properties\":{\"due\":{\"format\":\"date\",\"type\":\"string\"}},\"type\":\"object\"}";

    /** {@link LombokBuilderType}'s output document at T004's parent. */
    private static final String PARENT_OUTPUT_LOMBOK_BUILDER =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"properties\":{\"name\":{\"type\":\"string\"},\"quantity\":{\"type\":\"integer\"}},\"type\":\"object\"}";

    /** {@link FieldBackedGetterOnlyList}'s output document at T004's parent. */
    private static final String PARENT_OUTPUT_GETTER_ONLY_LIST =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"properties\":{\"tags\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"}},\"type\":\"object\"}";

    /** {@link FieldBackedGetterOnlyMap}'s output document at T004's parent. */
    private static final String PARENT_OUTPUT_GETTER_ONLY_MAP =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"properties\":{\"labels\":{\"type\":\"object\"}},\"type\":\"object\"}";

    /** {@link NestedRestoredShape}'s output document at T004's parent. */
    private static final String PARENT_OUTPUT_NESTED =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"properties\":{\"detail\":{\"properties\":{\"due\":{\"format\":\"date\",\"type\":\"string\"}},\"type\":\"object\"}},\"type\":\"object\"}";

    // --- Fixtures: one per shape, named for it ---

    /** The commonest DTO shape: a private field Jackson fills through reflection, behind a getter. */
    static final class PrivateFieldBehindAGetter {

        private LocalDate due;

        /**
         * Returns the due date.
         *
         * @return the due date
         */
        public LocalDate getDue() {
            return due;
        }
    }

    /**
     * A builder type filled through its builder, whose getters the real Lombok annotation processor
     * generates. Without {@code @Getter} nothing is visible to introspection and the document stays
     * the bare object (BG1).
     */
    @Builder
    @Jacksonized
    @Getter
    static final class LombokBuilderType {

        private String name;

        private Integer quantity;
    }

    /** A getter-only collection that has a backing field, so Jackson fills it. */
    static final class FieldBackedGetterOnlyList {

        private List<String> tags;

        /**
         * Returns the tags.
         *
         * @return the tags
         */
        public List<String> getTags() {
            return tags;
        }
    }

    /** A getter-only map that has a backing field, so Jackson fills it. */
    static final class FieldBackedGetterOnlyMap {

        private Map<String, String> labels;

        /**
         * Returns the labels.
         *
         * @return the labels
         */
        public Map<String, String> getLabels() {
            return labels;
        }
    }

    /** A restored shape held as a property, so the nested position is proven too. */
    static final class NestedRestoredShape {

        private PrivateFieldBehindAGetter detail;

        /**
         * Returns the nested detail.
         *
         * @return the nested detail
         */
        public PrivateFieldBehindAGetter getDetail() {
            return detail;
        }
    }

    /**
     * A record whose component is the any-setter's backing storage. The storage is named
     * {@code extras} in every fixture, so its absence reads directly.
     *
     * @param name   an ordinary property
     * @param extras the any-setter's backing storage
     */
    record AnySetterRecord(String name, @JsonAnySetter Map<String, String> extras) {}

    /**
     * A method any-setter beside a real, constrained property whose name the setter's own name
     * implies. Excluding storage by implied name rather than by member hides {@code attribute}.
     */
    static final class MethodAnySetterBesideARealProperty {

        @Size(max = 3)
        private String attribute;

        /**
         * Returns the real, constrained property.
         *
         * @return the attribute
         */
        public String getAttribute() {
            return attribute;
        }

        /**
         * Collects every extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void setAttribute(String key, Object value) {
            // The storage itself is irrelevant to the document under test.
        }
    }

    /** A property the mapper never binds, beside one it does. */
    static final class IgnoredProperty {

        /** An ordinary property, so the ignored one's absence is not the absence of everything. */
        public String kept;

        /** Never bound, never described. */
        @JsonIgnore
        public String secret;
    }

    /** A read-only property, beside an ordinary one. */
    static final class ReadOnlyProperty {

        /** An ordinary property. */
        public String kept;

        /** Server-assigned: never accepted on input. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String issuedAt;
    }

    /** A write-only property, beside an ordinary one. */
    static final class WriteOnlyProperty {

        /** An ordinary property. */
        public String kept;

        /** Accepted on input, never emitted. */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String password;
    }
}
