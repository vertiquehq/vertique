// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-015 (T007): how a type with a {@code @JsonAnySetter} describes its extra keys and the names it
 * reserves beside them.
 *
 * <p>Five proofs share one fixture family, each fixture named for the shape it stands for:
 *
 * <ul>
 *   <li><strong>TP-001</strong> — {@code additionalProperties} describes the any-setter's value type:
 *       the map value type of a field-level any-setter, the second parameter of a method-level one,
 *       and the profile's own rendering of that type, so a {@code LocalDate} keeps {@code format:
 *       date} and a {@code BigDecimal} under {@code vertique-strict} carries the strict decimal
 *       fragment.
 *   <li><strong>TP-002</strong> — an unconstrained value type ({@code Object}, {@code JsonNode}) is
 *       the empty schema; a class-level Swagger {@code additionalProperties = TRUE} does not suppress
 *       the typed description, while {@code FALSE}, declared or inherited, does.
 *   <li><strong>TP-003</strong> — the description travels with the type: it appears at a nested
 *       position and in the shared definition of a type used twice.
 *   <li><strong>TP-004</strong> — behavior preservation: a type Jackson binds as map-like is
 *       described exactly as the same class without an any-setter, because Jackson ignores the
 *       any-setter there (design proof v5/v6, K02).
 *   <li><strong>TP-005</strong> — {@code propertyNames} carries one reserved set, computed as a
 *       difference: every name Jackson binds on input, minus every name published under {@code
 *       properties}, minus every name whose Jackson property definition carries no member at all.
 *       Each fixture stands for one member of that set, and the creator-rename fixture stands for the
 *       memberless subtraction that fails open.
 * </ul>
 *
 * <p>Every document is generated under the registry's built-in {@code vertique} profile — the floor
 * an unannotated REST route resolves to — except where a proof names another built-in profile, so
 * the subject is the same generator configuration the REST gate uses, not a hand-built mapper.
 *
 * <p>Failure messages carry the whole document. A schema proof that reports only a missing keyword
 * forces the reader to regenerate the document to see what was published instead.
 */
class AnySetterDescriptionTest {

    // --- Generation helpers ---

    /**
     * Resolves a built-in profile from a registry holding no application profiles.
     *
     * @param profileId the built-in profile id
     * @return the resolved profile
     */
    private static JsonMapperProfile profile(String profileId) {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of(profileId));
    }

    /**
     * Generates a type's input-direction document under the {@code vertique} profile.
     *
     * @param type the body type
     * @return the parsed canonical document
     */
    private static JsonNode inputDocument(Type type) {
        return inputDocument(type, "vertique");
    }

    /**
     * Generates a type's input-direction document under a named built-in profile.
     *
     * @param type      the body type
     * @param profileId the built-in profile id
     * @return the parsed canonical document
     */
    private static JsonNode inputDocument(Type type, String profileId) {
        return assertCanonicalForm(inputText(type, profileId));
    }

    /**
     * Generates a type's input-direction canonical document text under a named built-in profile.
     *
     * @param type      the body type
     * @param profileId the built-in profile id
     * @return the canonical document text
     */
    private static String inputText(Type type, String profileId) {
        return AnnotationJsonSchemaGenerator.forInputProfile(profile(profileId)).generateCanonical(type);
    }

    // --- Document readers ---

    /**
     * Returns the {@code additionalProperties} member describing an any-setter's extra keys, failing
     * when the schema describes none — the open object this task closes.
     *
     * @param document the whole document, which owns the definitions and is printed on failure
     * @param schema   the schema of the any-setter type itself, which is the document at a root
     *                 position and a nested or referenced subschema otherwise
     * @param subject  the fixture name and position, for the failure message
     * @return the {@code additionalProperties} node, resolved through a {@code $ref} when the value
     *     type has its own definition
     */
    private static JsonNode extras(JsonNode document, JsonNode schema, String subject) {
        assertTrue(
                schema.has("additionalProperties"),
                subject + " must describe its any-setter's extra keys through additionalProperties: the"
                        + " document leaves the object open, so an extra key's value is never validated;"
                        + " document: " + document);
        return resolve(document, schema.get("additionalProperties"));
    }

    /**
     * Follows a local {@code $ref} into the document's own definitions, so a proof reads the same
     * schema whether the generator inlined the value type or shared it under {@code $defs}.
     *
     * @param document the whole document, which owns the definitions
     * @param node     the node that may be a reference
     * @return the referenced schema, or {@code node} when it is not a local reference
     */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return node;
        }
        return document.at(reference.asText().substring(1));
    }

    /**
     * Returns the document's {@code properties} member, or a missing node when it publishes none.
     *
     * @param document the parsed document
     * @return the {@code properties} node
     */
    private static JsonNode properties(JsonNode document) {
        return document.path("properties");
    }

    /**
     * Returns the reserved names the document publishes as {@code propertyNames: {"not": {"enum":
     * [...]}}}, failing when it publishes no reserved set at all.
     *
     * @param document the parsed document
     * @param subject  the fixture name, for the failure message
     * @return the reserved names, in document order
     */
    private static List<String> reservedNames(JsonNode document, String subject) {
        assertTrue(
                document.has("propertyNames"),
                subject + " must reserve, in propertyNames, every name Jackson binds on input that the"
                        + " document does not publish: without it a reserved name is accepted as an"
                        + " extra key and routed to the member it names; document: " + document);
        JsonNode exclusions = document.path("propertyNames").path("not").path("enum");
        assertTrue(
                exclusions.isArray(),
                subject + " must carry its reserved names as propertyNames: {\"not\": {\"enum\": [...]}};"
                        + " document: " + document);
        List<String> names = new ArrayList<>();
        exclusions.forEach(name -> names.add(name.asText()));
        return names;
    }

    /**
     * Asserts that a type reserves exactly the given names and publishes none of them.
     *
     * @param type     the fixture type
     * @param subject  the fixture name, for the failure messages
     * @param expected the expected reserved names, in the order the document carries them
     */
    private static void assertReserves(Type type, String subject, List<String> expected) {
        JsonNode document = inputDocument(type);

        assertEquals(
                expected,
                reservedNames(document, subject),
                subject + " must reserve exactly the names Jackson binds on input that it does not"
                        + " publish; document: " + document);
        for (String name : expected) {
            assertFalse(
                    properties(document).has(name),
                    "no reserved name may also be published as a property: " + subject + " publishes '" + name
                            + "'; document: " + document);
        }
    }

    // --- TP-001: typed extras are described ---

    @Test
    @DisplayName("A field-level any-setter describes its map's value type as the extras schema")
    void fieldAnySetterDescribesItsValueType() {
        JsonNode document = inputDocument(FieldAnySetterOverStrings.class);

        assertEquals(
                "string",
                extras(document, document, "FieldAnySetterOverStrings")
                        .path("type")
                        .asText(null),
                "the extras schema must be the field-level any-setter's map value type; document: " + document);
        assertEquals(
                "string",
                properties(document).path("name").path("type").asText(null),
                "the named property must stay described beside the extras schema; document: " + document);
        assertFalse(
                properties(document).has("extras"),
                "the field-level any-setter's storage must not be published as a property, and it reserves"
                        + " no name either: Jackson routes a key named after it into the map; document: " + document);
    }

    @Test
    @DisplayName("A method-level any-setter describes its second parameter as the extras schema")
    void methodAnySetterDescribesItsSecondParameter() {
        JsonNode document = inputDocument(MethodAnySetterOverStrings.class);

        assertEquals(
                "string",
                extras(document, document, "MethodAnySetterOverStrings")
                        .path("type")
                        .asText(null),
                "the extras schema must be the method any-setter's second parameter type; document: " + document);
        assertEquals(
                "string",
                properties(document).path("name").path("type").asText(null),
                "the named property must stay described beside the extras schema; document: " + document);
    }

    @Test
    @DisplayName("A type that is only an any-setter describes its value type")
    void anySetterOnlyTypeDescribesItsValueType() {
        JsonNode document = inputDocument(AnySetterOnlyOverIntegers.class);

        assertEquals(
                "integer",
                extras(document, document, "AnySetterOnlyOverIntegers")
                        .path("type")
                        .asText(null),
                "a type with no named property at all must still describe its extra keys: it is the shape"
                        + " the MCP hardener never closed, so an untyped extra reached the binder; document: "
                        + document);
    }

    @Test
    @DisplayName("A LocalDate extras value keeps the profile's date format")
    void localDateValueKeepsItsFormat() {
        JsonNode document = inputDocument(AnySetterOverLocalDates.class);

        JsonNode extras = extras(document, document, "AnySetterOverLocalDates");
        assertEquals(
                "string", extras.path("type").asText(null), "a LocalDate extras value's type; document: " + document);
        assertEquals(
                "date",
                extras.path("format").asText(null),
                "a LocalDate extras value must carry the same format an ordinary LocalDate property carries:"
                        + " the extras schema is the context's own definition of the value type, not a"
                        + " hand-built fragment; document: " + document);
    }

    @Test
    @DisplayName("A BigDecimal extras value carries the vertique-strict decimal fragment")
    void bigDecimalValueCarriesTheStrictFragment() {
        JsonNode document = inputDocument(AnySetterOverDecimals.class, "vertique-strict");

        JsonNode extras = extras(document, document, "AnySetterOverDecimals");
        assertEquals(
                "string",
                extras.path("type").asText(null),
                "under vertique-strict a BigDecimal extras value is a string, as the profile's override"
                        + " declares for every BigDecimal position; document: " + document);
        assertEquals(
                "decimal",
                extras.path("format").asText(null),
                "the strict decimal fragment's format must reach the extras position; document: " + document);
        assertTrue(
                extras.path("maxLength").isInt(),
                "the strict decimal fragment's maxLength must reach the extras position; document: " + document);
        assertTrue(
                extras.path("pattern").isTextual(),
                "the strict decimal fragment's anchored plain-decimal pattern must reach the extras position;"
                        + " document: " + document);
    }

    // --- TP-002: unconstrained values and the Swagger annotations ---

    @Test
    @DisplayName("An Object extras value is the empty schema")
    void objectValueIsTheEmptySchema() {
        JsonNode document = inputDocument(AnySetterOverObjects.class);

        assertEquals(
                "{}",
                extras(document, document, "AnySetterOverObjects").toString(),
                "an unconstrained extras value must be the empty schema, which accepts every JSON value:"
                        + " anything narrower would reject a legal extra; document: " + document);
    }

    @Test
    @DisplayName("A JsonNode extras value is the empty schema")
    void jsonNodeValueIsTheEmptySchema() {
        JsonNode document = inputDocument(AnySetterOverJsonNodes.class);

        assertEquals(
                "{}",
                extras(document, document, "AnySetterOverJsonNodes").toString(),
                "a JsonNode extras value is as unconstrained as Object and must be the empty schema;" + " document: "
                        + document);
    }

    @Test
    @DisplayName("A class-level Swagger additionalProperties = TRUE does not suppress the typed description")
    void swaggerTrueDoesNotSuppressTheDescription() {
        JsonNode document = inputDocument(SwaggerTrueAnySetter.class);

        assertEquals(
                "string",
                extras(document, document, "SwaggerTrueAnySetter").path("type").asText(null),
                "a Swagger additionalProperties = TRUE says extras are allowed, which the typed description"
                        + " already says more precisely: the description must win; document: " + document);
    }

    @Test
    @DisplayName("A class-level Swagger additionalProperties = FALSE suppresses the description, declared or inherited")
    void classLevelSwaggerFalseSuppressesTheDescription() {
        JsonNode declared = inputDocument(ClosedAnySetterType.class);
        JsonNode inherited = inputDocument(ClosedAnySetterSubclass.class);

        assertTrue(
                declared.path("additionalProperties").isBoolean()
                        && !declared.path("additionalProperties").asBoolean(),
                "an application that declared its object closed must keep it closed: the extras description"
                        + " must never override a class-level additionalProperties = FALSE; document: " + declared);
        assertTrue(
                inherited.path("additionalProperties").isBoolean()
                        && !inherited.path("additionalProperties").asBoolean(),
                "a subclass inheriting the class-level FALSE must stay closed too; document: " + inherited);
    }

    // --- TP-003: nested and shared positions ---

    @Test
    @DisplayName("An any-setter type held as a property is described at the nested position")
    void nestedAnySetterTypeIsDescribed() {
        JsonNode document = inputDocument(HoldsAnAnySetterType.class);

        JsonNode nested = resolve(document, properties(document).path("detail"));
        assertEquals(
                "string",
                extras(document, nested, "HoldsAnAnySetterType.detail")
                        .path("type")
                        .asText(null),
                "the nested position must carry the extras description: a resolver that answers only for a"
                        + " root type leaves every any-setter type used as a property open; document: " + document);
    }

    @Test
    @DisplayName("An any-setter type used twice keeps the description in its shared definition")
    void sharedAnySetterDefinitionKeepsItsDescription() {
        JsonNode document = inputDocument(HoldsAnAnySetterTypeTwice.class);

        // Measured at T007's parent: a type used twice is published once under $defs and referenced
        // from both positions, so the description has exactly one place to be.
        String reference = properties(document).path("first").path("$ref").asText(null);
        assertEquals(
                "#/$defs/FieldAnySetterOverStrings",
                reference,
                "the twice-used any-setter type must be published as one shared definition; document: " + document);
        assertEquals(
                reference,
                properties(document).path("second").path("$ref").asText(null),
                "both positions must reference that one definition; document: " + document);
        assertEquals(
                "string",
                extras(document, document.at("/$defs/FieldAnySetterOverStrings"), "the shared $defs entry")
                        .path("type")
                        .asText(null),
                "the shared definition must carry the extras description, so it applies at every use;" + " document: "
                        + document);
    }

    // --- TP-004: a map-like type is described as if it had no any-setter ---

    @Test
    @DisplayName("A map subclass with an any-setter is described exactly as the same class without one")
    void mapSubclassWithAnAnySetterMatchesTheSameClassWithout() {
        for (String profileId : List.of("system", "vertique", "vertique-strict")) {
            String withAnySetter = inputText(MapSubclassWithAnAnySetter.class, profileId);
            String withoutAnySetter = inputText(MapSubclassControl.class, profileId);

            assertEquals(
                    withoutAnySetter,
                    withAnySetter,
                    "Jackson binds a map-like type as a container and never routes a key to its any-setter,"
                            + " so the any-setter must contribute nothing under " + profileId
                            + ": describing it rejects the legal entry {\"empty\":\"x\"} (design proof v5, K02)");

            JsonNode document = assertCanonicalForm(withAnySetter);
            assertNotEquals(
                    "integer",
                    document.path("additionalProperties").path("type").asText(null),
                    "the ignored any-setter's Integer value type must not become the map's extras schema under "
                            + profileId + "; document: " + document);
            assertFalse(
                    document.has("propertyNames"),
                    "a map-like type reserves no name: reserving 'empty' from isEmpty() rejects a legal map"
                            + " entry (design proof v4) under " + profileId + "; document: " + document);
        }
    }

    // --- TP-005: reserved names beside described extras ---

    @Test
    @DisplayName("An ignored name and a read-only name are reserved beside the extras")
    void ignoredAndReadOnlyNamesAreReserved() {
        assertReserves(
                IgnoredAndReadOnlyBesideAnySetter.class, "IgnoredAndReadOnlyBesideAnySetter", List.of("id", "role"));
    }

    @Test
    @DisplayName("A method any-getter's storage field name is reserved")
    void methodAnyGetterStorageNameIsReserved() {
        assertReserves(MethodAnyGetterStorage.class, "MethodAnyGetterStorage", List.of("extras", "role"));
    }

    @Test
    @DisplayName("A class-level ignoral is reserved")
    void classLevelIgnoralIsReserved() {
        assertReserves(ClassLevelIgnoral.class, "ClassLevelIgnoral", List.of("level"));
    }

    @Test
    @DisplayName("A field that is both any-getter and any-setter reserves no storage name")
    void combinedAnyGetterAndAnySetterFieldReservesNoStorageName() {
        assertReserves(CombinedAnyGetterAndAnySetterField.class, "CombinedAnyGetterAndAnySetterField", List.of("role"));

        JsonNode document = inputDocument(CombinedAnyGetterAndAnySetterField.class);
        assertFalse(
                properties(document).has("extras"),
                "the combined any-accessor field is backing storage, not a property: Jackson stores a key"
                        + " named 'extras' as an ordinary entry of the map, so the name is neither published"
                        + " nor reserved (design proof v6); document: " + document);
    }

    @Test
    @DisplayName("A name bound only through a setter, an accessor pair, or a transient field's accessors is described")
    void namesBoundOnlyThroughAccessorsAreDescribed() {
        // Each of the three shapes is a distinct way for Jackson to bind a name no field carries; each
        // is read from the deserializer and described with the constraint its accessor carries, so a
        // key by that name is validated before it reaches the member instead of being refused outright.
        JsonNode setterOnly = inputDocument(SetterOnlyName.class);
        JsonNode accessorPair = inputDocument(AccessorPairOverADifferentField.class);
        JsonNode transientField = inputDocument(TransientFieldWithAccessors.class);
        assertAll(
                () -> assertEquals(
                        "{\"type\":\"boolean\"}",
                        properties(setterOnly).path("admin").toString(),
                        "a setter-only name is described with the setter's parameter type; document: " + setterOnly),
                () -> assertFalse(
                        setterOnly.has("propertyNames"), "nothing is left to reserve; document: " + setterOnly),
                () -> assertEquals(
                        "{\"maximum\":10,\"type\":\"integer\"}",
                        properties(accessorPair).path("level").toString(),
                        "an accessor pair over a differently named field is described with the getter's"
                                + " constraint; document: " + accessorPair),
                () -> assertFalse(
                        accessorPair.has("propertyNames"), "nothing is left to reserve; document: " + accessorPair),
                () -> assertEquals(
                        "{\"type\":\"string\"}",
                        properties(transientField).path("level").toString(),
                        "a transient field's accessors bind its name, so it is described; document: " + transientField),
                () -> assertFalse(
                        transientField.has("propertyNames"),
                        "nothing is left to reserve; document: " + transientField));
    }

    @Test
    @DisplayName("A hidden field Jackson still binds is reserved")
    void aHiddenFieldIsReserved() {
        assertReserves(SchemaHiddenField.class, "SchemaHiddenField", List.of("admin"));
    }

    @Test
    @DisplayName("A creator parameter renamed away from its field carries no member and is not reserved")
    void aCreatorParameterWithNoMemberIsNotReserved() {
        JsonNode document = inputDocument(CreatorParameterRenamedAwayFromItsField.class);

        assertFalse(
                document.has("propertyNames"),
                "a creator parameter renamed away from its field has no field, setter, getter, or record"
                        + " component, so its identity cannot be recovered and the reserved-name rule must"
                        + " fail open for it: reserving 'amount_cents' rejects the type's valid traffic at"
                        + " both boundaries (design proof v7 to v8, 10 verdicts); document: " + document);
    }

    // --- Fixtures: one per shape, named for it ---

    /** A named property beside a field-level any-setter over {@code String} values. */
    static final class FieldAnySetterOverStrings {

        /** An ordinary property, so the extras schema is proven beside a published property set. */
        public String name;

        /** The any-setter's backing storage: never a named property, and never a reserved name. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A named property beside a method-level any-setter whose second parameter is a {@code String}. */
    static final class MethodAnySetterOverStrings {

        /** An ordinary property. */
        public String name;

        /**
         * Collects every extra key.
         *
         * @param key   the extra key
         * @param value the extra value, whose declared type the extras schema must describe
         */
        @JsonAnySetter
        public void putExtra(String key, String value) {
            // The storage itself is irrelevant to the document under test.
        }
    }

    /**
     * A type that is only an any-setter. It publishes no named property, so the MCP hardener never
     * closed it and its extras were accepted untyped.
     */
    static final class AnySetterOnlyOverIntegers {

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, Integer> extras = new LinkedHashMap<>();
    }

    /** An any-setter whose value type is a {@code LocalDate}, so the profile's format must appear. */
    static final class AnySetterOverLocalDates {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, LocalDate> extras = new LinkedHashMap<>();
    }

    /** An any-setter whose value type is a {@code BigDecimal}, the strict profile's overridden type. */
    static final class AnySetterOverDecimals {

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, BigDecimal> extras = new LinkedHashMap<>();
    }

    /** An any-setter whose value type is unconstrained. */
    static final class AnySetterOverObjects {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** An any-setter whose value type is a Jackson tree node, which is as unconstrained as Object. */
    static final class AnySetterOverJsonNodes {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, JsonNode> extras = new LinkedHashMap<>();
    }

    /** An any-setter type whose class-level Swagger annotation only says extras are allowed. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    static final class SwaggerTrueAnySetter {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** An any-setter type the application declared closed. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    static class ClosedAnySetterType {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** The same restriction, inherited rather than declared. */
    static final class ClosedAnySetterSubclass extends ClosedAnySetterType {}

    /** A type holding an any-setter type as a property. */
    static final class HoldsAnAnySetterType {

        /** The nested any-setter type, whose extras must be described at this position. */
        public FieldAnySetterOverStrings detail;
    }

    /** A type using one any-setter type twice, so its definition is shared. */
    static final class HoldsAnAnySetterTypeTwice {

        /** The first use of the shared any-setter type. */
        public FieldAnySetterOverStrings first;

        /** The second use of the shared any-setter type. */
        public FieldAnySetterOverStrings second;
    }

    /**
     * A {@code HashMap} subclass that also declares an any-setter. Jackson binds the type as a map and
     * never routes a key to {@code put2}, so the any-setter must contribute nothing at all.
     */
    static class MapSubclassWithAnAnySetter extends HashMap<String, String> {

        /** Serialization parity with the control class, which extends the same map type. */
        private static final long serialVersionUID = 1L;

        /** A named field beside the map's own entries. */
        public String name;

        /**
         * The any-setter Jackson ignores on a map-like type.
         *
         * @param key   the extra key
         * @param value the extra value, of a type that must never reach the document
         */
        @JsonAnySetter
        public void put2(String key, Integer value) {
            // Never invoked: Jackson binds this type as a map.
        }
    }

    /** The same class without the any-setter: the control the map subclass must match byte for byte. */
    static class MapSubclassControl extends HashMap<String, String> {

        /** Serialization parity with the subject class. */
        private static final long serialVersionUID = 1L;

        /** A named field beside the map's own entries. */
        public String name;
    }

    /** An ignored name and a read-only name, neither published, both bound past the schema. */
    static final class IgnoredAndReadOnlyBesideAnySetter {

        /** An ordinary property. */
        public String name;

        /** Never bound by name, and never published: a client key named {@code id} lands in the map. */
        @JsonIgnore
        public String id;

        /** Server-assigned: never accepted on input, never published. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String role;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * A method {@code @JsonAnyGetter} over a private map field. Jackson fills that field through the
     * getter, so its name is bound on input and never published — the SG1 bypass.
     */
    static final class MethodAnyGetterStorage {

        /** An ordinary property. */
        public String name;

        /** Server-assigned: never accepted on input, never published. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String role;

        /** The storage the any-getter returns and Jackson fills through it. */
        private final Map<String, String> extras = new LinkedHashMap<>();

        /**
         * Collects every extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void putExtra(String key, String value) {
            extras.put(key, value);
        }

        /**
         * Returns the collected extras.
         *
         * @return the extras
         */
        @JsonAnyGetter
        public Map<String, String> getExtras() {
            return extras;
        }
    }

    /** A class-level ignoral: a name the type knows, never published, never bound to a member. */
    @JsonIgnoreProperties("level")
    static final class ClassLevelIgnoral {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * A field carrying both any-accessor annotations. Jackson stores a key named after it as an
     * ordinary entry of the map, so the storage name is neither published nor reserved.
     */
    static final class CombinedAnyGetterAndAnySetterField {

        /** An ordinary property. */
        public String name;

        /** Server-assigned: never accepted on input, never published. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String role;

        /** The storage both any-accessors share. */
        @JsonAnyGetter
        @JsonAnySetter
        private final Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A name bound only through a setter, with no field behind it (SO1). */
    static final class SetterOnlyName {

        /** An ordinary property. */
        public String name;

        /**
         * Binds {@code admin} with nothing to publish it from.
         *
         * @param admin the bound value
         */
        public void setAdmin(boolean admin) {
            // The value itself is irrelevant to the document under test.
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A name bound through an accessor pair over a differently named field (SO2). */
    static final class AccessorPairOverADifferentField {

        /** An ordinary property. */
        public String name;

        /** The storage behind the accessor pair, under a different name. */
        private int lvl;

        /**
         * Returns the level, carrying the constraint the bound name escapes.
         *
         * @return the level
         */
        @Max(10)
        public int getLevel() {
            return lvl;
        }

        /**
         * Binds the level.
         *
         * @param level the bound value
         */
        public void setLevel(int level) {
            this.lvl = level;
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A field Jackson binds that the Swagger annotation hides from the document (HID1). */
    static final class SchemaHiddenField {

        /** An ordinary property. */
        public String name;

        /** Hidden from the document, still bound by Jackson. */
        @Schema(hidden = true)
        public boolean admin;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A transient field with accessors: bound by Jackson, never published (TR1). */
    static final class TransientFieldWithAccessors {

        /** An ordinary property. */
        public String name;

        /** Transient storage the document never publishes. */
        private transient String level;

        /**
         * Returns the level.
         *
         * @return the level
         */
        public String getLevel() {
            return level;
        }

        /**
         * Binds the level.
         *
         * @param level the bound value
         */
        public void setLevel(String level) {
            this.level = level;
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * A creator parameter renamed away from the field it populates. Jackson reports two property
     * definitions — {@code amount_cents} with no member of any kind, and {@code amount} with the field
     * and getter — so the bound name's identity cannot be recovered and the rule must fail open.
     */
    static final class CreatorParameterRenamedAwayFromItsField {

        /** The field the renamed creator parameter populates. */
        private final int amount;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();

        /**
         * Binds the amount under a wire name that matches no member.
         *
         * @param amount the bound value
         */
        @JsonCreator
        CreatorParameterRenamedAwayFromItsField(@JsonProperty("amount_cents") int amount) {
            this.amount = amount;
        }

        /**
         * Returns the amount.
         *
         * @return the amount
         */
        public int getAmount() {
            return amount;
        }
    }
}
