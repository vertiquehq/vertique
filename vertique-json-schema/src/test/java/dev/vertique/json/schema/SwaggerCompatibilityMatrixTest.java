// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins FR-JSON-084: the shared generator's Swagger compatibility surface is exact at the pinned
 * {@code swagger-annotations-jakarta 2.2.44} version. Three obligations:
 *
 * <ol>
 *   <li>{@link #swaggerAnnotationMembersArePartitionedExhaustively()} — every {@code @Schema} and
 *       {@code @ArraySchema} annotation member declared by the pinned library falls into exactly one
 *       of this test's own {@link #CONSUMED} or {@link #IGNORED} sets, built from the PRD §6.2 exact
 *       member table. A future dependency upgrade that adds, removes, or renames a member fails this
 *       test loudly instead of silently drifting from the documented compatibility promise.
 *   <li>the {@code effect*} test group proves every {@link #CONSUMED} member actually influences a
 *       generated document.
 *   <li>the {@code ignored*} test group proves a representative sample of {@link #IGNORED} members
 *       has <em>no</em> effect, including the two members the PRD calls out by name:
 *       {@code Schema.type} and the {@code implementation} × override interplay (the latter is S4's
 *       job, cross-referenced rather than duplicated below).
 * </ol>
 *
 * <p>The {@code implementation} × profile-override guard is proven by {@link
 * GeneratorCompositionTest#schemaImplementationOnOverriddenDeclaredTypeFailsGeneration()} and its
 * sibling in that class; this test proves only {@code implementation}'s ordinary redirect effect,
 * reusing {@link HardeningFixtures#ImplementationWithoutOverrideDto} under {@link
 * AnnotationJsonSchemaGenerator#withVictoolsDefaults()} to show the member has an effect independent
 * of the profile-override system entirely.
 */
class SwaggerCompatibilityMatrixTest {

    /** Neutral mapper used to read documents inside the assertions. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- PRD §6.2 exact member table, transcribed as the test's own frozen partition ---

    /**
     * Every {@code @Schema}/{@code @ArraySchema} member the pinned {@code Swagger2Module} consumes,
     * per PRD §6.2's exact table: the union of the "type" and "field or method/property" rows for
     * {@link Schema}, plus the {@code @ArraySchema} row's five members.
     */
    private static final Set<String> CONSUMED = Set.of(
            // --- @Schema: type ∪ property rows (union; type contributes subTypes/additionalProperties
            // beyond the property row, everything else already appears in the property row) ---
            "accessMode",
            "additionalProperties",
            "allOf",
            "allowableValues",
            "anyOf",
            "defaultValue",
            "description",
            "exclusiveMaximum",
            "exclusiveMinimum",
            "format",
            "hidden",
            "implementation",
            "maxLength",
            "maximum",
            "maxProperties",
            "minLength",
            "minProperties",
            "minimum",
            "multipleOf",
            "name",
            "not",
            "nullable",
            "oneOf",
            "pattern",
            "ref",
            "required",
            "requiredMode",
            "requiredProperties",
            "subTypes",
            "title",
            // --- @ArraySchema row ---
            "arraySchema",
            "maxItems",
            "minItems",
            "schema",
            "uniqueItems");

    /**
     * Every {@code @Schema}/{@code @ArraySchema} member declared at {@code swagger-annotations-jakarta
     * 2.2.44} that is NOT a JSON-005 compatibility promise: not listed in PRD §6.2's exact table, so
     * the pinned {@code Swagger2Module} either does not consult it or the generator does not claim its
     * effect. Any unlisted member, including {@code Schema.type}, has no JSON-005 effect (FR-JSON-084).
     *
     * <p>This is a flat set of member <em>names</em>, not (annotation, name) pairs: {@code contains},
     * {@code extensions}, {@code maxContains}, {@code minContains}, {@code prefixItems}, and {@code
     * unevaluatedItems} are each declared on both {@link Schema} and {@link ArraySchema} and therefore
     * appear only once below. {@code items} is {@link ArraySchema}-only.
     */
    private static final Set<String> IGNORED = Set.of(
            "$anchor",
            "$comment",
            "$dynamicAnchor",
            "$dynamicRef",
            "$id",
            "$schema",
            "$vocabulary",
            "_const",
            "_else",
            "_if",
            "additionalItems",
            "additionalPropertiesSchema",
            "contains",
            "contentEncoding",
            "contentMediaType",
            "contentSchema",
            "dependentRequiredMap",
            "dependentSchemas",
            "deprecated",
            "discriminatorMapping",
            "discriminatorProperty",
            "enumAsRef",
            "example",
            "exampleClasses",
            "examples",
            "exclusiveMaximumValue",
            "exclusiveMinimumValue",
            "externalDocs",
            "extensions",
            "items",
            "maxContains",
            "minContains",
            "patternProperties",
            "prefixItems",
            "properties",
            "propertyNames",
            "readOnly",
            "schemaResolution",
            "then",
            "type",
            "types",
            "unevaluatedItems",
            "unevaluatedProperties",
            "writeOnly");

    // --- Obligation 1: partition completeness (the version-upgrade tripwire) ---

    @Test
    @DisplayName(
            "Every @Schema and @ArraySchema member at the pinned version falls into exactly one of CONSUMED/IGNORED")
    void swaggerAnnotationMembersArePartitionedExhaustively() {
        Set<String> reflected = new HashSet<>();
        reflected.addAll(annotationMemberNames(Schema.class));
        reflected.addAll(annotationMemberNames(ArraySchema.class));

        Set<String> declared = new HashSet<>(CONSUMED);
        declared.addAll(IGNORED);

        Set<String> reflectedNotDeclared = new HashSet<>(reflected);
        reflectedNotDeclared.removeAll(declared);
        assertTrue(
                reflectedNotDeclared.isEmpty(),
                "the pinned library declares a member absent from both CONSUMED and IGNORED — this is the"
                        + " FR-JSON-084 version-upgrade tripwire; missing: " + reflectedNotDeclared);

        Set<String> declaredNotReflected = new HashSet<>(declared);
        declaredNotReflected.removeAll(reflected);
        assertTrue(
                declaredNotReflected.isEmpty(),
                "CONSUMED/IGNORED name a member the pinned library no longer declares — update the PRD §6.2"
                        + " table and this partition; stale: " + declaredNotReflected);

        Set<String> overlap = new HashSet<>(CONSUMED);
        overlap.retainAll(IGNORED);
        assertTrue(overlap.isEmpty(), "a member must not be both CONSUMED and IGNORED; overlap: " + overlap);
    }

    /**
     * Reflects an annotation interface's declared members (its abstract element methods; the
     * interface's own static constant fields, e.g. {@code Schema.DEFAULT_SENTINEL}, are not methods
     * and are never returned here).
     *
     * @param annotationType the annotation interface to reflect
     * @return the declared member names
     */
    private static Set<String> annotationMemberNames(Class<?> annotationType) {
        return Arrays.stream(annotationType.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && Modifier.isAbstract(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    // --- Obligation 2: listed-member effect proofs (property-level, grouped by keyword family) ---

    /** DTO exercising the string-bound property members: {@code minLength}/{@code maxLength}/{@code format}/{@code pattern}. */
    static final class StringBoundsDto {

        /** Carries all four string-bound members. */
        @Schema(minLength = 2, maxLength = 8, format = "custom-format", pattern = "^[A-Z]+$")
        public String code;
    }

    @Test
    @DisplayName("minLength/maxLength/format/pattern each have an effect")
    void stringBoundsHaveEffect() throws Exception {
        JsonNode code = property(StringBoundsDto.class, "code");
        assertEquals(2, code.get("minLength").intValue(), "minLength");
        assertEquals(8, code.get("maxLength").intValue(), "maxLength");
        assertEquals("custom-format", text(code.get("format")), "format");
        assertEquals("^[A-Z]+$", text(code.get("pattern")), "pattern");
    }

    /** DTO exercising the numeric-bound property members, split into inclusive and exclusive forms. */
    static final class NumericBoundsDto {

        /** Exclusive bounds: {@code exclusiveMinimum}/{@code exclusiveMaximum} replace {@code minimum}/{@code maximum}. */
        @Schema(multipleOf = 0.5, minimum = "0", exclusiveMinimum = true, maximum = "100", exclusiveMaximum = true)
        public double exclusiveAmount;

        /** Inclusive bounds: {@code minimum}/{@code maximum} are emitted directly. */
        @Schema(minimum = "1", maximum = "99")
        public double inclusiveAmount;
    }

    @Test
    @DisplayName("multipleOf/minimum/exclusiveMinimum/maximum/exclusiveMaximum each have an effect")
    void numericBoundsHaveEffect() throws Exception {
        JsonNode exclusive = property(NumericBoundsDto.class, "exclusiveAmount");
        assertEquals(0.5, exclusive.get("multipleOf").doubleValue(), "multipleOf");
        // Pinned Swagger2Module behavior: exclusiveMinimum=true/exclusiveMaximum=true replace the
        // minimum/maximum keys with exclusiveMinimum/exclusiveMaximum carrying the same numeric value.
        assertEquals(0, exclusive.get("exclusiveMinimum").intValue(), "exclusiveMinimum");
        assertEquals(100, exclusive.get("exclusiveMaximum").intValue(), "exclusiveMaximum");
        assertNoMember(exclusive, "minimum");
        assertNoMember(exclusive, "maximum");

        JsonNode inclusive = property(NumericBoundsDto.class, "inclusiveAmount");
        assertEquals(1, inclusive.get("minimum").intValue(), "minimum");
        assertEquals(99, inclusive.get("maximum").intValue(), "maximum");
    }

    /**
     * DTO exercising the composition property members. Every field is declared {@code Object}-typed
     * so the contributed {@code allOf}/{@code anyOf}/{@code oneOf}/{@code not} target (an
     * object-shaped POJO) never conjoins with a disjoint declared type — the point of this group is
     * proving each member's effect, not exercising {@code DisjointTypeDetector} (S4's job).
     */
    static final class CompositionDto {

        /** {@code allOf} with one branch: ALLOF_CLEANUP flattens a single branch to a bare {@code $ref}. */
        @Schema(allOf = {Extra.class})
        public Object allOfField;

        /** {@code anyOf} with two branches. */
        @Schema(anyOf = {Extra.class, Extra2.class})
        public Object anyOfField;

        /** {@code oneOf} with two branches. */
        @Schema(oneOf = {Extra.class, Extra2.class})
        public Object oneOfField;

        /** {@code not}. */
        @Schema(not = Extra.class)
        public Object notField;
    }

    /** Composition branch target. */
    static final class Extra {

        /** A marker property proving this exact definition was referenced. */
        public String extraField;
    }

    /** Second composition branch target. */
    static final class Extra2 {

        /** A marker property proving this exact definition was referenced. */
        public String extra2Field;
    }

    @Test
    @DisplayName("allOf/anyOf/oneOf/not each have an effect")
    void compositionHasEffect() throws Exception {
        JsonNode document = generate(CompositionDto.class);

        // allOf with a single branch is flattened by ALLOF_CLEANUP_AT_THE_END into a bare $ref —
        // still a distinguishable effect from an inline object schema.
        assertTrue(
                document.at("/properties/allOfField/$ref").isTextual(),
                "allOf must contribute a reference; document: " + document);

        JsonNode anyOf = document.at("/properties/anyOfField/anyOf");
        assertTrue(anyOf.isArray() && anyOf.size() == 2, "anyOf must contribute a two-branch alternation");

        JsonNode oneOf = document.at("/properties/oneOfField/oneOf");
        assertTrue(oneOf.isArray() && oneOf.size() == 2, "oneOf must contribute a two-branch alternation");

        assertTrue(document.at("/properties/notField/not/$ref").isTextual(), "not must contribute a negated reference");
    }

    /** DTO exercising {@code description}/{@code title} at property level. */
    static final class PresentationDto {

        /** Carries both members. */
        @Schema(description = "desc-x", title = "title-x")
        public String labelled;
    }

    @Test
    @DisplayName("description/title each have an effect at property level")
    void presentationHasEffect() throws Exception {
        JsonNode labelled = property(PresentationDto.class, "labelled");
        assertEquals("desc-x", text(labelled.get("description")), "description");
        assertEquals("title-x", text(labelled.get("title")), "title");
    }

    /** DTO exercising {@code required}/{@code requiredMode}/{@code requiredProperties}. */
    static final class RequirednessDto {

        /** Marks itself required via the boolean form. */
        @Schema(required = true)
        public String requiredField;

        /** Marks itself required via the enum form. */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        public String requiredModeField;

        /** Declares which of ITS OWN nested properties are required. */
        @Schema(requiredProperties = {"foo", "bar"})
        public NestedObj requiredPropsField;
    }

    /** Nested object target for {@code requiredProperties} and cardinality members. */
    static final class NestedObj {

        /** First nested property. */
        public String foo;

        /** Second nested property. */
        public String bar;
    }

    @Test
    @DisplayName("required/requiredMode/requiredProperties each have an effect")
    void requirednessHasEffect() throws Exception {
        JsonNode document = generate(RequirednessDto.class);
        List<String> rootRequired = textArray(document.get("required"));
        assertTrue(rootRequired.contains("requiredField"), "required=true must mark the property required");
        assertTrue(rootRequired.contains("requiredModeField"), "requiredMode=REQUIRED must mark the property required");

        List<String> nestedRequired = textArray(document.at("/properties/requiredPropsField/required"));
        assertEquals(List.of("foo", "bar"), nestedRequired, "requiredProperties must name the nested required members");
    }

    /** DTO exercising {@code minProperties}/{@code maxProperties} on a nested-object-typed property. */
    static final class CardinalityDto {

        /** Bounded nested object. */
        @Schema(minProperties = 1, maxProperties = 3)
        public NestedObj bounded;
    }

    @Test
    @DisplayName("minProperties/maxProperties each have an effect")
    void cardinalityHasEffect() throws Exception {
        JsonNode bounded = property(CardinalityDto.class, "bounded");
        assertEquals(1, bounded.get("minProperties").intValue(), "minProperties");
        assertEquals(3, bounded.get("maxProperties").intValue(), "maxProperties");
    }

    /** DTO exercising {@code nullable}/{@code allowableValues}/{@code defaultValue}/{@code hidden}/{@code name}. */
    static final class ValueMetadataDto {

        /** {@code nullable} widens the type array to include {@code "null"}. */
        @Schema(nullable = true)
        public String nullableField;

        /** {@code allowableValues} contributes an {@code enum} list. */
        @Schema(allowableValues = {"a", "b", "c"})
        public String allowableField;

        /** {@code defaultValue} contributes a {@code default} member. */
        @Schema(defaultValue = "eur")
        public String defaultField;

        /** {@code hidden} excludes the property entirely. */
        @Schema(hidden = true)
        public String hiddenField;

        /** {@code name} renames the property key in the generated document. */
        @Schema(name = "renamed")
        public String originalName;
    }

    @Test
    @DisplayName("nullable/allowableValues/defaultValue/hidden/name each have an effect")
    void valueMetadataHasEffect() throws Exception {
        JsonNode document = generate(ValueMetadataDto.class);

        List<String> nullableTypes = textArray(document.at("/properties/nullableField/type"));
        assertTrue(
                nullableTypes.contains("string") && nullableTypes.contains("null"),
                "nullable must widen the type array");

        List<String> allowable = textArray(document.at("/properties/allowableField/enum"));
        assertEquals(List.of("a", "b", "c"), allowable, "allowableValues must contribute the enum list");

        assertEquals("eur", text(document.at("/properties/defaultField/default")), "defaultValue");

        assertNoMember(document.get("properties"), "hiddenField");

        assertNoMember(document.get("properties"), "originalName");
        assertFalse(document.at("/properties/renamed").isMissingNode(), "name must rename the property key");
    }

    /** DTO exercising {@code accessMode}: READ_ONLY/WRITE_ONLY translate to {@code readOnly}/{@code writeOnly}. */
    static final class AccessModeDto {

        /** Read-only. */
        @Schema(accessMode = Schema.AccessMode.READ_ONLY)
        public String readOnlyField;

        /** Write-only. */
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        public String writeOnlyField;
    }

    @Test
    @DisplayName("accessMode has an effect (emits readOnly/writeOnly)")
    void accessModeHasEffect() throws Exception {
        JsonNode readOnly = property(AccessModeDto.class, "readOnlyField");
        assertTrue(readOnly.get("readOnly").booleanValue(), "accessMode=READ_ONLY must emit readOnly:true");

        JsonNode writeOnly = property(AccessModeDto.class, "writeOnlyField");
        assertTrue(writeOnly.get("writeOnly").booleanValue(), "accessMode=WRITE_ONLY must emit writeOnly:true");
    }

    /** DTO exercising {@code implementation}'s ordinary redirect, independent of the profile-override system. */
    @Test
    @DisplayName("implementation has an effect (redirects the property's schema to the replacement type)")
    void implementationHasEffect() {
        // Reuses HardeningFixtures.ImplementationWithoutOverrideDto (S4) under withVictoolsDefaults(),
        // showing the redirect fires with no profile-override machinery involved at all — the guard
        // interplay itself is S4's job (GeneratorCompositionTest), not duplicated here.
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                .generateCanonical(HardeningFixtures.ImplementationWithoutOverrideDto.class);
        assertTrue(
                canonical.contains("replacementMarker"),
                "implementation must redirect the property schema to the replacement type; was: " + canonical);
    }

    /** DTO exercising every {@code @ArraySchema} member. */
    static final class ArraySchemaDto {

        /** Carries all five {@code @ArraySchema} members. */
        @ArraySchema(
                schema = @Schema(format = "item-marker"),
                arraySchema = @Schema(description = "container-marker"),
                minItems = 2,
                maxItems = 5,
                uniqueItems = true)
        public List<String> tags;
    }

    @Test
    @DisplayName("@ArraySchema's schema/arraySchema/minItems/maxItems/uniqueItems each have an effect")
    void arraySchemaHasEffect() throws Exception {
        JsonNode tags = property(ArraySchemaDto.class, "tags");
        assertEquals("container-marker", text(tags.get("description")), "arraySchema (container-level)");
        assertEquals(2, tags.get("minItems").intValue(), "minItems");
        assertEquals(5, tags.get("maxItems").intValue(), "maxItems");
        assertTrue(tags.get("uniqueItems").booleanValue(), "uniqueItems");
        assertEquals("item-marker", text(tags.at("/items/format")), "schema (item-level)");
    }

    // --- Obligation 2 continued: type-level member effect proofs ---

    /** Type carrying {@code description}/{@code title} at class level. */
    @Schema(description = "type-desc", title = "type-title")
    static final class TypeDescriptionTitleDto {

        /** An ordinary property, unrelated to the type-level members under test. */
        public String field;
    }

    @Test
    @DisplayName("type-level description/title each have an effect")
    void typeLevelDescriptionTitleHasEffect() throws Exception {
        JsonNode document = generate(TypeDescriptionTitleDto.class);
        assertEquals("type-desc", text(document.get("description")), "type-level description");
        assertEquals("type-title", text(document.get("title")), "type-level title");
    }

    /** Type carrying {@code ref} at class level; referenced twice so the definition is not inlined. */
    @Schema(ref = "https://example.com/schemas/external.json")
    static final class TypeRefTarget {

        /** An ordinary property, never visible once {@code ref} redirects every reference. */
        public String field;
    }

    /** Wrapper referencing {@link TypeRefTarget} twice, forcing a shared definition. */
    static final class TypeRefWrapperDto {

        /** First reference. */
        public TypeRefTarget first;

        /** Second reference. */
        public TypeRefTarget second;
    }

    @Test
    @DisplayName("type-level ref has an effect (every reference resolves to the declared external ref)")
    void typeLevelRefHasEffect() throws Exception {
        JsonNode document = generate(TypeRefWrapperDto.class);
        String expected = "https://example.com/schemas/external.json";
        assertEquals(expected, text(document.at("/properties/first/$ref")), "first reference");
        assertEquals(expected, text(document.at("/properties/second/$ref")), "second reference");
    }

    /** Type carrying {@code additionalProperties} at class level. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    static final class TypeAdditionalPropertiesDto {

        /** An ordinary property. */
        public String field;
    }

    @Test
    @DisplayName("type-level additionalProperties has an effect")
    void typeLevelAdditionalPropertiesHasEffect() throws Exception {
        JsonNode document = generate(TypeAdditionalPropertiesDto.class);
        assertFalse(document.get("additionalProperties").booleanValue(), "additionalProperties=FALSE");
    }

    /** Type carrying {@code name} at class level; referenced twice so a named definition is produced. */
    @Schema(name = "RenamedType")
    static final class TypeNamedTarget {

        /** An ordinary property. */
        public String field;
    }

    /** Wrapper referencing {@link TypeNamedTarget} twice, forcing a shared, named definition. */
    static final class TypeNameWrapperDto {

        /** First reference. */
        public TypeNamedTarget first;

        /** Second reference. */
        public TypeNamedTarget second;
    }

    @Test
    @DisplayName("type-level name has an effect (renames the shared $defs entry)")
    void typeLevelNameHasEffect() throws Exception {
        JsonNode document = generate(TypeNameWrapperDto.class);
        assertFalse(document.at("/$defs/RenamedType").isMissingNode(), "the $defs entry must use the declared name");
        assertEquals(
                "#/$defs/RenamedType",
                text(document.at("/properties/first/$ref")),
                "first reference uses the named $ref");
        assertEquals(
                "#/$defs/RenamedType",
                text(document.at("/properties/second/$ref")),
                "second reference uses the named $ref");
    }

    /** Base type carrying {@code subTypes} at class level. */
    @Schema(subTypes = {TypeSubTypeDto.class})
    abstract static class TypeSubTypesBaseDto {

        /** The base's own property. */
        public String base;
    }

    /** The declared subtype. */
    static final class TypeSubTypeDto extends TypeSubTypesBaseDto {

        /** The subtype's own property. */
        public String extra;
    }

    /** Control fixture: structurally identical base type, but with no {@code subTypes} declaration. */
    abstract static class NoSubTypesBaseDto {

        /** The base's own property. */
        public String base;
    }

    @Test
    @DisplayName("type-level subTypes has an effect (redirects the base type's own definition)")
    void typeLevelSubTypesHasEffect() throws Exception {
        // Baseline: an ordinary abstract base with no subTypes declaration generates its own
        // properties directly.
        JsonNode baseline = generate(NoSubTypesBaseDto.class);
        assertFalse(baseline.at("/properties/base").isMissingNode(), "the baseline must carry its own 'base' property");

        // With subTypes declared: the pinned Swagger2Module/Victools combination redirects the base
        // type's own definition through the declared subtype instead of emitting its own properties
        // directly — a different, but real, effect from the baseline.
        JsonNode withSubTypes = generate(TypeSubTypesBaseDto.class);
        assertTrue(
                withSubTypes.at("/$ref").isTextual(),
                "subTypes must redirect the root definition through a $ref; document: " + withSubTypes);
        assertTrue(baseline.at("/properties").isObject(), "the baseline generates its own properties directly");
        assertFalse(
                withSubTypes.at("/properties").isObject(),
                "subTypes must change generation away from the baseline's direct-properties shape");
    }

    /** Type carrying {@code anyOf} at class level, replacing the type's own properties entirely. */
    @Schema(anyOf = {TypeAnyOfBranchA.class, TypeAnyOfBranchB.class})
    static final class TypeAnyOfDto {

        /** Declared, but superseded by the type-level anyOf alternation. */
        public String field;
    }

    /** First anyOf branch. */
    static final class TypeAnyOfBranchA {

        /** Branch-specific property. */
        public String a;
    }

    /** Second anyOf branch. */
    static final class TypeAnyOfBranchB {

        /** Branch-specific property. */
        public String b;
    }

    @Test
    @DisplayName("type-level anyOf has an effect (replaces the type's own properties with the alternation)")
    void typeLevelAnyOfHasEffect() throws Exception {
        JsonNode document = generate(TypeAnyOfDto.class);
        JsonNode anyOf = document.get("anyOf");
        assertTrue(
                anyOf != null && anyOf.isArray() && anyOf.size() == 2,
                "anyOf must contribute a two-branch alternation");
        assertTrue(
                document.get("properties") == null,
                "type-level anyOf must supersede the type's own declared 'field' property; document: " + document);
    }

    // --- Obligation 3: unlisted-member non-effect proofs ---

    /** DTO proving {@code Schema.type} has no effect (the PRD's explicitly named example). */
    static final class IgnoredTypeDto {

        /** Declares {@code type="string"} on an {@link Integer}-typed property. */
        @Schema(type = "string")
        public Integer number;
    }

    /** Structurally identical control DTO with no {@code @Schema} annotation at all. */
    static final class UnannotatedIntegerDto {

        /** Same field name and Java type as {@link IgnoredTypeDto#number}, unannotated. */
        public Integer number;
    }

    @Test
    @DisplayName("Schema.type has no effect: an Integer property keeps type 'integer' (PRD-named example)")
    void schemaTypeHasNoEffect() throws Exception {
        String annotated = AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(IgnoredTypeDto.class);
        String unannotated =
                AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(UnannotatedIntegerDto.class);

        // Byte-identical: neither fixture's class name appears anywhere in either document (a bare
        // Integer-typed property carries no $defs/$ref definition entry), so a direct byte compare
        // proves type="string" contributed nothing that survives to the canonical document.
        assertEquals(
                unannotated,
                annotated,
                "Schema.type must have no observable effect; the annotated and unannotated documents must be"
                        + " byte-identical");
        JsonNode document = MAPPER.readTree(annotated);
        assertEquals("integer", text(document.at("/properties/number/type")), "the declared Java type must survive");
    }

    /** DTO carrying the {@code readOnly}/{@code writeOnly} boolean members (distinct from {@code accessMode}). */
    static final class IgnoredReadWriteBooleansDto {

        /** {@code readOnly=true} without {@code accessMode}. */
        @Schema(readOnly = true)
        public String readOnlyBoolean;

        /** {@code writeOnly=true} without {@code accessMode}. */
        @Schema(writeOnly = true)
        public String writeOnlyBoolean;
    }

    @Test
    @DisplayName("Schema.readOnly()/writeOnly() booleans have no effect (only accessMode is consumed)")
    void readOnlyWriteOnlyBooleansHaveNoEffect() throws Exception {
        JsonNode document = generate(IgnoredReadWriteBooleansDto.class);
        assertNoMember(document.at("/properties/readOnlyBoolean"), "readOnly");
        assertNoMember(document.at("/properties/writeOnlyBoolean"), "writeOnly");
    }

    /** DTO carrying the {@code exclusiveMinimumValue}/{@code exclusiveMaximumValue} int overloads. */
    static final class IgnoredExclusiveValueIntsDto {

        /** Carries both int-overload members (distinct from the string {@code minimum}/{@code maximum} form). */
        @Schema(exclusiveMinimumValue = 5, exclusiveMaximumValue = 95)
        public int amount;
    }

    @Test
    @DisplayName("Schema.exclusiveMinimumValue()/exclusiveMaximumValue() int overloads have no effect")
    void exclusiveValueIntOverloadsHaveNoEffect() throws Exception {
        JsonNode amount = property(IgnoredExclusiveValueIntsDto.class, "amount");
        assertNoMember(amount, "exclusiveMinimum");
        assertNoMember(amount, "exclusiveMaximum");
        assertNoMember(amount, "minimum");
        assertNoMember(amount, "maximum");
        assertEquals("integer", text(amount.get("type")), "the plain declared type must be unaffected");
    }

    /** DTO carrying {@code example}/{@code deprecated}, plausible-effect-risk ignored members. */
    static final class IgnoredExampleDeprecatedDto {

        /** Carries both members. */
        @Schema(example = "example-value", deprecated = true)
        public String field;
    }

    @Test
    @DisplayName("Schema.example()/deprecated() have no effect")
    void exampleAndDeprecatedHaveNoEffect() throws Exception {
        JsonNode field = property(IgnoredExampleDeprecatedDto.class, "field");
        assertNoMember(field, "example");
        assertNoMember(field, "deprecated");
        assertEquals("string", text(field.get("type")), "the plain declared type must be unaffected");
    }

    // --- Generation + assertion helpers ---

    /**
     * Generates and parses a canonical document for the given fixture type using {@link
     * AnnotationJsonSchemaGenerator#withVictoolsDefaults()}.
     *
     * @param type the fixture type
     * @return the parsed document
     * @throws Exception if the canonical text is not valid JSON
     */
    private static JsonNode generate(Class<?> type) throws Exception {
        String canonical = AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(type);
        assertNotNull(canonical, "the canonical document must not be null");
        return MAPPER.readTree(canonical);
    }

    /**
     * Generates a document for the given fixture type and returns the named root-level property's
     * schema node.
     *
     * @param type     the fixture type
     * @param property the property name
     * @return the property's schema node
     * @throws Exception if the canonical text is not valid JSON
     */
    private static JsonNode property(Class<?> type, String property) throws Exception {
        JsonNode node = generate(type).at("/properties/" + property);
        assertFalse(node.isMissingNode(), "property '" + property + "' must be present");
        return node;
    }

    /**
     * Asserts that a node carries no member with the given key.
     *
     * @param node the node to check; may itself be a missing node, which trivially passes
     * @param key  the member key that must be absent
     */
    private static void assertNoMember(JsonNode node, String key) {
        assertTrue(
                node == null || node.isMissingNode() || node.get(key) == null,
                "member '" + key + "' must be absent; found on: " + node);
    }

    /**
     * Returns a node's textual value, or {@code null} when it is absent or not textual.
     *
     * @param node the node, possibly {@code null}
     * @return the textual value or {@code null}
     */
    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * Collects the textual values of an array node, preserving order.
     *
     * @param node the array node; a {@code null}/missing node yields an empty list
     * @return the textual values, in array order
     */
    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(element -> {
            if (element.isTextual()) {
                values.add(element.textValue());
            }
        });
        return values;
    }
}
