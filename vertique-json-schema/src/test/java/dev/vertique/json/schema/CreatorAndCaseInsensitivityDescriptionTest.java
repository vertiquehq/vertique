// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Three bounded corrections to the deserializer-driven input description, each closing a loosening
 * against {@code main} (7c50dd21) the owner required before accepting the hybrid generator (design
 * proof, issue594-v4 shapes CR1/CR1c, DC1/DC1c, N6/CI2/CI3):
 *
 * <ul>
 *   <li><strong>Change 1</strong> — a creator parameter renamed away from every member, carrying no
 *       constraint of its own, is excluded entirely rather than published bare.
 *   <li><strong>Change 2</strong> — a delegating {@code @JsonCreator} is refused exactly like a
 *       type-level custom deserializer, remediable the same way (a profile override).
 *   <li><strong>Change 3</strong> — case-insensitive binding (mapper-wide, class-level, or
 *       member-level) is described with {@code patternProperties} instead of refused outright.
 * </ul>
 */
class CreatorAndCaseInsensitivityDescriptionTest {

    // --- Generation helpers ---

    private static JsonMapperProfile profile(ObjectMapper mapper) {
        return profile(mapper, List.of());
    }

    private static JsonMapperProfile profile(ObjectMapper mapper, List<JsonSchemaTypeOverride> overrides) {
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return overrides;
            }
        };
    }

    private static JsonNode inputDocument(Type type) {
        return inputDocument(type, new ObjectMapper());
    }

    private static JsonNode inputDocument(Type type, ObjectMapper mapper) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile(mapper)).generateCanonical(type));
    }

    private static JsonNode properties(JsonNode document) {
        return document.path("properties");
    }

    // ================================================================== Change 1: creator parameters

    @Test
    @DisplayName("A creator parameter renamed away from its constrained field is excluded, not published bare")
    void unjoinableConstraintFreeCreatorParameterIsExcludedOnAnAnySetterType() {
        JsonNode document = inputDocument(CR1AnySetter.class);

        assertEquals(
                "{\"maximum\":10,\"type\":\"integer\"}",
                properties(document).path("amount").toString(),
                "the field-bound getter property must still carry the field's own constraint; document: " + document);
        assertFalse(
                properties(document).has("amount_cents"),
                "the unjoinable creator parameter must not be published bare — that drops the @Max(10) the"
                        + " field carries and lets 999 through where main rejected it (design proof CR1c);"
                        + " document: " + document);
        JsonNode reservedNames = document.path("propertyNames").path("not").path("enum");
        if (reservedNames.isArray()) {
            for (JsonNode reserved : reservedNames) {
                assertFalse(
                        "amount_cents".equals(reserved.asText()),
                        "the unjoinable creator parameter must not be reserved either: main published no"
                                + " property by this name and reserved none, so a key by that name must still"
                                + " reach the extras schema unconstrained rather than being rejected outright;"
                                + " document: " + document);
            }
        }
    }

    @Test
    @DisplayName("On a closed type the same shape publishes only the field-bound property")
    void unjoinableConstraintFreeCreatorParameterIsExcludedOnAClosedType() {
        JsonNode document = inputDocument(CR1Closed.class);

        assertEquals(
                "{\"amount\":{\"maximum\":10,\"type\":\"integer\"}}",
                properties(document).toString(),
                "a closed type must publish exactly the field-bound property, matching main byte for byte:"
                        + " amount_cents is not published, so the MCP hardener's additionalProperties: false"
                        + " rejects it as an unrecognized key, exactly as main did; document: " + document);
        assertFalse(
                document.has("propertyNames"), "nothing is left to reserve on a closed type; document: " + document);
    }

    @Test
    @DisplayName("A creator parameter that carries its own constraint is still published under its wire name")
    void creatorParameterWithItsOwnConstraintIsStillPublished() {
        JsonNode document = inputDocument(CreatorParameterWithOwnConstraint.class);

        assertEquals(
                "{\"maximum\":50,\"type\":\"integer\"}",
                properties(document).path("amount_cents").toString(),
                "a creator parameter carrying its own constraint must still be published under its wire"
                        + " name with that constraint, whether or not it joins a field; document: " + document);
    }

    @Test
    @DisplayName("ADVERSARIAL: a creator parameter renamed to a name differing from a field only by case is excluded")
    void creatorParameterDifferingFromItsFieldOnlyByCaseIsExcluded() {
        // Under ordinary (case-sensitive) binding, "Amount" and "amount" are different property names
        // to Jackson too — it does not join them into one BeanPropertyDefinition — so the join this
        // generator attempts must fail exactly the way it fails for an unrelated name (CR1c), not
        // succeed by accident through a case-insensitive string comparison in backingField or
        // hasAccessorDerivedProperty.
        JsonNode document = inputDocument(CaseOnlyRenamedCreatorParameter.class);

        assertEquals(
                "{\"amount\":{\"maximum\":10,\"type\":\"integer\"}}",
                properties(document).toString(),
                "the wire name \"Amount\" must not be published bare: it must be excluded exactly like an"
                        + " unrelated wire name, not accidentally joined to the differently-cased field;"
                        + " document: " + document);
    }

    // ================================================================== Change 2: delegating creators

    @Test
    @DisplayName("A delegating creator is refused exactly like a type-level custom deserializer")
    void delegatingCreatorIsRefusedWithoutAnOverride() {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> inputDocument(DC1Delegating.class),
                "a delegating creator must be refused: its wire shape is whatever the delegate type"
                        + " accepts, which is not this type's own named properties");

        String message = failure.getMessage();
        assertNotNull(message, "the refusal must carry a message");
        assertTrue(
                message.contains(DC1Delegating.class.getSimpleName()),
                "the message must name the refused type; was: " + message);
        assertTrue(
                message.contains("JsonSchemaTypeOverride"),
                "the message must name the remedy — a profile override; was: " + message);
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
    }

    @Test
    @DisplayName("A profile override remedies the delegating-creator refusal")
    void delegatingCreatorGeneratesWithAnOverride() {
        String marker = "delegating-override-marker";
        JsonMapperProfile overridden = profile(
                new ObjectMapper(),
                List.of(JsonSchemaTypeOverride.input(DC1Delegating.class, HardeningFixtures.markerFragment(marker))));

        String document =
                AnnotationJsonSchemaGenerator.forInputProfile(overridden).generateCanonical(DC1Delegating.class);

        assertTrue(
                document.contains(marker),
                "an overridden delegating-creator type must use the profile's fragment, never reach"
                        + " InputPropertyDescriber's own refusal; document: " + document);
    }

    @Test
    @DisplayName("A delegating creator on a concrete polymorphic subtype is refused the same way")
    void delegatingCreatorOnPolymorphicSubtypeIsRefused() {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class, () -> inputDocument(PolymorphicDelegatingSubtype.class));

        assertTrue(
                failure.getMessage().contains(PolymorphicDelegatingSubtype.class.getSimpleName()),
                "the message must name the concrete subtype that actually refused; was: " + failure.getMessage());
    }

    // ================================================================== Change 3: case-insensitivity

    @Test
    @DisplayName("Mapper-wide case-insensitive binding is described with patternProperties")
    void mapperWideCaseInsensitiveTypePublishesPatternProperties() {
        ObjectMapper caseInsensitive =
                new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        JsonNode document = inputDocument(N6CaseInsensitiveByMapper.class, caseInsensitive);

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                properties(document).path("name").toString(),
                "the canonical name must still be published under properties; document: " + document);
        assertFoldedPattern(document, "name", "{\"maxLength\":3,\"type\":\"string\"}");
    }

    @Test
    @DisplayName("Class-level @JsonFormat case-insensitive binding is described with patternProperties")
    void classLevelCaseInsensitiveTypePublishesPatternProperties() {
        JsonNode document = inputDocument(CI2CaseInsensitiveByClass.class);

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                properties(document).path("name").toString(),
                "document: " + document);
        assertFoldedPattern(document, "name", "{\"maxLength\":3,\"type\":\"string\"}");
    }

    @Test
    @DisplayName("Member-level @JsonFormat case-insensitive binding is described inline at that position")
    void memberLevelCaseInsensitiveNestedTypeIsDescribedInline() {
        JsonNode document = inputDocument(CI3Holder.class);

        JsonNode child = properties(document).path("child");
        assertFalse(
                child.has("$ref"),
                "a member bound case-insensitively only through its own contextual"
                        + " annotation must not share the type's ordinary (case-sensitive) definition; document: "
                        + document);
        assertEquals("object", child.path("type").asText(null), "document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("name").toString(),
                "document: " + document);
        JsonNode childPatternProperties = child.path("patternProperties");
        assertTrue(
                childPatternProperties.isObject() && !childPatternProperties.isEmpty(),
                "the inline nested description must carry its own patternProperties; document: " + document);
        assertEquals(
                "string",
                properties(document).path("label").path("type").asText(null),
                "an ordinary sibling property must be unaffected; document: " + document);
    }

    // ================================================================== F4: member-level CI inline refusals

    @Test
    @DisplayName("F4: a delegating creator behind a member-level case-insensitive @JsonFormat is refused, not"
            + " described inline (the same refusal describe() runs for the type at any other position)")
    void memberLevelCaseInsensitiveDelegatingChildIsRefused() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(CI6Holder.class));

        assertTrue(
                failure.getMessage().contains(CI6DelegatingChild.class.getSimpleName()),
                "was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("JsonSchemaTypeOverride"),
                "the message must name the remedy — a profile override; was: " + failure.getMessage());
    }

    @Test
    @DisplayName("F4: a profile override declared for the case-insensitive child's type is honored at that"
            + " position, not silently ignored by the inline path")
    void memberLevelCaseInsensitiveOverrideIsHonored() {
        String marker = "ci-delegating-override-marker";
        JsonMapperProfile overridden = profile(
                new ObjectMapper(),
                List.of(JsonSchemaTypeOverride.input(
                        CI6DelegatingChild.class, HardeningFixtures.markerFragment(marker))));

        String document =
                AnnotationJsonSchemaGenerator.forInputProfile(overridden).generateCanonical(CI6Holder.class);

        assertTrue(
                document.contains(marker),
                "the override must apply at the case-insensitive member position exactly as it would at any"
                        + " other position, never be bypassed by the inline description; document: " + document);
    }

    // ============================================ S2: scalar creator on the member-level CI inline path

    /**
     * S2 (spike/deserializer-driven-schema round 4 ruling): {@code describe()}'s own root path checks
     * {@code scalarCreator(instantiator)} before ever building an object schema, so a from-string
     * scalar-creator type is described as {@code {"type":"string"}} wherever it is referenced by
     * {@code $ref}. The member-level case-insensitive inline path ({@code propertySchema}, F4) builds its
     * schema by hand instead of asking Victools for the member's type, and never runs that same
     * {@code scalarCreator} check — it calls {@code populateObjectSchema} unconditionally — so a
     * scalar-creator type reached only through this inline path is described as an object instead.
     *
     * <p>The owner ruling records the fix direction (apply the scalar-creator branch on the inline path
     * too) but this class only authors the proof, never the production change.
     */
    @Test
    @DisplayName("S2: a from-string scalar-creator type bound case-insensitively at member level is described"
            + " as a string, not an object")
    void memberLevelCaseInsensitiveScalarCreatorTypeIsDescribedAsAString() {
        JsonNode document = inputDocument(S2Holder.class);
        JsonNode child = properties(document).path("child");

        assertEquals(
                "string",
                child.path("type").asText(null),
                "S2 DECISIVE: a from-string scalar-creator type must be described as {\"type\":\"string\"}"
                        + " at the member-level case-insensitive inline position, exactly as describe()'s own"
                        + " root path already describes it wherever it is referenced by $ref (scalarCreator),"
                        + " rather than as an object; document: " + document);
        assertFalse(
                child.has("properties"),
                "a scalar-creator type carries no properties of its own to publish; document: " + document);
    }

    // ================================================================== W1: array-delegating creators

    @Test
    @DisplayName("W1: an array-delegating creator is refused exactly like an object-delegating one")
    void arrayDelegatingCreatorIsRefusedWithoutAnOverride() {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> inputDocument(W1ArrayDelegating.class),
                "an array-delegating creator must be refused: its wire shape is a JSON array, which a"
                        + " schema's properties cannot describe any more than an object delegate's shape could");

        String message = failure.getMessage();
        assertNotNull(message, "the refusal must carry a message");
        assertTrue(
                message.contains(W1ArrayDelegating.class.getSimpleName()),
                "the message must name the refused type; was: " + message);
        assertTrue(
                message.contains("JsonSchemaTypeOverride"),
                "the message must name the remedy — a profile override; was: " + message);
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
    }

    @Test
    @DisplayName("W1: a profile override remedies the array-delegating-creator refusal")
    void arrayDelegatingCreatorGeneratesWithAnOverride() {
        String marker = "array-delegating-override-marker";
        JsonMapperProfile overridden = profile(
                new ObjectMapper(),
                List.of(JsonSchemaTypeOverride.input(
                        W1ArrayDelegating.class, HardeningFixtures.markerFragment(marker))));

        String document =
                AnnotationJsonSchemaGenerator.forInputProfile(overridden).generateCanonical(W1ArrayDelegating.class);

        assertTrue(
                document.contains(marker),
                "an overridden array-delegating-creator type must use the profile's fragment, never reach"
                        + " InputPropertyDescriber's own refusal; document: " + document);
    }

    @Test
    @DisplayName("A reserved name on a case-insensitive type is excluded by a folded pattern, not an enum")
    void reservedNameOnCaseInsensitiveTypeUsesAFoldedPattern() {
        JsonNode document = inputDocument(CI2WithReservedName.class);

        assertFalse(
                document.path("propertyNames").path("not").has("enum"),
                "a case-insensitive type must reserve by pattern, not enum; document: " + document);
        String pattern =
                document.path("propertyNames").path("not").path("pattern").asText(null);
        assertNotNull(pattern, "document: " + document);
        assertTrue(
                Pattern.compile(pattern).matcher("SECRETKEY").matches(),
                "the folded pattern must reject every casing of the reserved name; pattern: " + pattern);
        assertTrue(Pattern.compile(pattern).matcher("secretkey").matches(), "pattern: " + pattern);
        assertFalse(
                Pattern.compile(pattern).matcher("name").matches(),
                "the folded pattern must not reject a published name; pattern: " + pattern);
    }

    @Test
    @DisplayName("required stays keyed by the canonical spelling only under case-insensitive binding")
    void requiredStaysByCanonicalSpellingUnderCaseInsensitiveBinding() {
        JsonNode document = inputDocument(CI2WithRequiredField.class);

        JsonNode required = document.path("required");
        assertTrue(required.isArray(), "document: " + document);
        List<String> names = new java.util.ArrayList<>();
        required.forEach(node -> names.add(node.asText()));
        assertEquals(
                List.of("name"),
                names,
                "required must carry only the canonical spelling: a document reader cannot express \"present"
                        + " under any casing\", so presence under another casing that Jackson still accepts is"
                        + " not enforced by the schema; document: " + document);
    }

    @Test
    @DisplayName("A non-ASCII property name on a case-insensitive type is refused with a bounded diagnostic")
    void nonAsciiPropertyNameOnCaseInsensitiveTypeIsRefused() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(CI4NonAsciiProperty.class));

        assertTrue(
                failure.getMessage().contains(CI4NonAsciiProperty.class.getSimpleName()),
                "was: " + failure.getMessage());
        assertTrue(failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH, "was: " + failure.getMessage());
    }

    @Test
    @DisplayName("An alias on a case-insensitive type is refused rather than folded")
    void aliasOnCaseInsensitiveTypeIsRefused() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(CI5WithAlias.class));

        assertTrue(failure.getMessage().contains(CI5WithAlias.class.getSimpleName()), "was: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("alias"), "was: " + failure.getMessage());
    }

    // --- Assertion helpers ---

    private static void assertFoldedPattern(JsonNode document, String canonicalName, String expectedSchemaText) {
        JsonNode patternProperties = document.path("patternProperties");
        assertTrue(
                patternProperties.isObject() && !patternProperties.isEmpty(),
                "a case-insensitively bound type must publish patternProperties; document: " + document);
        boolean found = false;
        var fields = patternProperties.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            Pattern pattern = Pattern.compile(entry.getKey());
            if (pattern.matcher(canonicalName.toUpperCase(java.util.Locale.ROOT))
                            .matches()
                    && pattern.matcher(canonicalName).matches()) {
                assertEquals(
                        expectedSchemaText,
                        entry.getValue().toString(),
                        "the folded entry must carry the same schema as the canonical property; document: " + document);
                found = true;
            }
        }
        assertTrue(
                found,
                "no patternProperties entry matched every casing of \"" + canonicalName + "\"; document: " + document);
    }

    // --- Fixtures: Change 1 ---

    /** CR1: a creator parameter renamed away from a @Max-constrained field, on an any-setter type. */
    static final class CR1AnySetter {
        @Max(10)
        private final int amount;

        @JsonAnySetter
        private final Map<String, Object> extras = new HashMap<>();

        @JsonCreator
        CR1AnySetter(@JsonProperty("amount_cents") int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }
    }

    /** CR1c: the same shape, closed (no any-setter). */
    static final class CR1Closed {
        @Max(10)
        private final int amount;

        @JsonCreator
        CR1Closed(@JsonProperty("amount_cents") int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }
    }

    /** A creator parameter renamed away from every member, but carrying its own constraint. */
    static final class CreatorParameterWithOwnConstraint {
        private final int amount;

        @JsonCreator
        CreatorParameterWithOwnConstraint(@JsonProperty("amount_cents") @Max(50) int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }
    }

    /** A creator parameter renamed to a wire name differing from its backing field only by case. */
    static final class CaseOnlyRenamedCreatorParameter {
        @Max(10)
        private final int amount;

        @JsonCreator
        CaseOnlyRenamedCreatorParameter(@JsonProperty("Amount") int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }
    }

    // --- Fixtures: Change 2 ---

    /** DC1: a delegating creator over a Map, on an any-setter type. */
    static final class DC1Delegating {
        @jakarta.validation.constraints.Size(max = 3)
        public String name;

        @JsonAnySetter
        private final Map<String, Object> extras = new HashMap<>();

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        DC1Delegating(Map<String, Object> body) {
            this.name = String.valueOf(body.get("nm"));
        }
    }

    @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
            property = "kind")
    @com.fasterxml.jackson.annotation.JsonSubTypes(
            @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
                    value = PolymorphicDelegatingSubtype.class,
                    name = "delegating"))
    abstract static class PolymorphicBase {
        public String label;
    }

    static final class PolymorphicDelegatingSubtype extends PolymorphicBase {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        PolymorphicDelegatingSubtype(Map<String, Object> body) {
            this.label = String.valueOf(body.get("label"));
        }
    }

    // --- Fixtures: Change 3 ---

    /** N6: case-insensitivity through the profile's mapper feature. */
    static final class N6CaseInsensitiveByMapper {
        @jakarta.validation.constraints.Size(max = 3)
        public String name;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** CI2: case-insensitivity through a class-level @JsonFormat. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CI2CaseInsensitiveByClass {
        @jakarta.validation.constraints.Size(max = 3)
        public String name;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** CI2 plus a name never published: proves the reserved-name pattern form. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CI2WithReservedName {
        @jakarta.validation.constraints.Size(max = 3)
        public String name;

        @JsonIgnore
        public String secretKey;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** CI2 plus a required field: proves required stays canonical-only. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CI2WithRequiredField {
        @NotNull
        public String name;
    }

    /** CI3: case-insensitivity through a member-level @JsonFormat. */
    static final class CI3Child {
        @jakarta.validation.constraints.Size(max = 3)
        public String name;
    }

    static final class CI3Holder {
        public String label;

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public CI3Child child;
    }

    /** A case-insensitive type whose property name carries a non-ASCII letter. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CI4NonAsciiProperty {
        @JsonProperty("café")
        public String value;
    }

    /** A case-insensitive type declaring an alias spelling. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CI5WithAlias {
        @JsonAlias("nm")
        public String name;
    }

    /** F4: a delegating creator, the same shape as {@link DC1Delegating} minus the any-setter. */
    static final class CI6DelegatingChild {
        @jakarta.validation.constraints.Size(max = 3)
        public String name;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        CI6DelegatingChild(Map<String, Object> body) {
            this.name = String.valueOf(body.get("nm"));
        }
    }

    /** F4: the delegating child is bound case-insensitively only through this member's own @JsonFormat. */
    static final class CI6Holder {
        public String label;

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public CI6DelegatingChild child;
    }

    // --- Fixtures: S2 ---

    /** S2: a from-string scalar-creator type — no properties, just a delegating creator over String. */
    static final class S2ScalarCreatorChild {
        final String value;

        @JsonCreator
        S2ScalarCreatorChild(String value) {
            this.value = value;
        }
    }

    /** S2: the scalar-creator child is bound case-insensitively only through this member's own @JsonFormat. */
    static final class S2Holder {
        public String label;

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public S2ScalarCreatorChild child;
    }

    // --- Fixtures: W1 ---

    /** W1: a single-argument delegating creator whose parameter type is array-like (a List). */
    static final class W1ArrayDelegating {
        private final List<String> items;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        W1ArrayDelegating(List<String> items) {
            this.items = items;
        }

        public List<String> getItems() {
            return items;
        }
    }
}
