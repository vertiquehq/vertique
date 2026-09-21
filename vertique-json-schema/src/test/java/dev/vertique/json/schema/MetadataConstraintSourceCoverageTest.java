// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static dev.vertique.json.schema.SchemaAssertions.keywordValues;
import static dev.vertique.json.schema.SchemaAssertions.propertyClosure;
import static dev.vertique.json.schema.SchemaAssertions.textValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.Validator;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Coverage for {@link MetadataConstraintSource}: shapes the annotation walk cannot join by wire name
 * (a constructor-parameter constraint without {@code -parameters}, a container-element constraint, an
 * inherited or interface constraint, a composed constraint's leaves, an XML-mapped constraint), the
 * group filter, and the vertiquehq/vertique-dev#606 render gaps (victools' Jakarta module renders
 * {@code @Range} with its annotation defaults, drops {@code @Pattern} flags, and ignores
 * {@code @Length}/{@code @URL}).
 *
 * <p>Name-join coverage — a {@code @JsonProperty} rename on a constrained field, a constrained getter
 * under a rename, a record component, a Lombok builder type, and a creator parameter without
 * {@code -parameters} — shares this test class, since every case is proven the same way: generate
 * under a validator-backed generator and assert the constraint lands on the published (wire) property.
 */
class MetadataConstraintSourceCoverageTest {

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    private static JsonNode metadataDocument(Type type, Validator validator) {
        return assertCanonicalForm(AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), validator)
                .generateCanonical(type));
    }

    private static JsonNode walkDocument(Type type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile()).generateCanonical(type));
    }

    // --- Name-join ---

    @Test
    @DisplayName("a @JsonProperty rename on a constrained field joins by the field's Java name")
    void renamedFieldJoinsByJavaName() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.RenamedFieldDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "wire");
        assertEquals(
                List.of(2),
                keywordValues(closure, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(5),
                keywordValues(closure, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertTrue(
                document.at("/properties/field").isMissingNode(),
                "the constraint must land on the wire name \"wire\", never on the Java field name");
    }

    @Test
    @DisplayName("a constrained getter under a Jackson rename joins by the getter's implied Java name")
    void renamedGetterJoinsByJavaName() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.RenamedGetterDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "displayValue");
        assertEquals(
                List.of(1),
                keywordValues(closure, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(9),
                keywordValues(closure, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
    }

    @Test
    @DisplayName("a record component's constraint joins by constructor + index and lands on the component")
    void recordComponentJoins() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.RecordDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "code");
        assertEquals(
                List.of(2),
                keywordValues(closure, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(5),
                keywordValues(closure, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
    }

    @Test
    @DisplayName("a Lombok @Builder @Jacksonized @Getter type's builder method joins by the built field's name")
    void lombokBuilderJoins() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.LombokBuilderDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "name");
        assertEquals(
                List.of(2),
                keywordValues(closure, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(9),
                keywordValues(closure, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
    }

    @Test
    @DisplayName("owner ruling: a hand-written builder method's property does not join even under a validator —"
            + " the supplement must not silently reintroduce the constraint the floor stopped borrowing")
    void handWrittenBuilderDoesNotJoinEvenUnderAValidator() {
        // Without BuilderBorrowDetector gating this class's own join too, this class's direct
        // reflection over the built class (validator.getConstraintsForClass(TransformingBuilderDto
        // .class).getConstraintsForProperty("amount")) would find the field's real, class-level
        // @Max(10) — Bean Validation does not care how the value got there — and render it as an
        // addition, since the floor no longer sets "maximum" for this property. That would make
        // generation depend on whether a Validator happens to be supplied, silently reversing the
        // owner ruling for exactly the callers who supply one.
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(BuilderWireNameJoinTest.TransformingBuilderDto.class, validator);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must still be published: the builder method binds it");
        assertTrue(
                amount.at("/maximum").isMissingNode(),
                "the hand-written builder's property must carry no borrowed maximum even with a validator"
                        + " supplied; document: " + document);
    }

    @Test
    @DisplayName("owner ruling: a hand-written builder reproducing the Lombok shape also joins under a validator"
            + " — the supplement's own builder gate must resolve it the same way the floor does")
    void handWrittenLombokShapedBuilderAlsoJoinsUnderAValidator() {
        // The builder gate is checked on both borrow sites (3264cff9): InputPropertyDescriber (the
        // floor, pinned without a validator by BuilderWireNameJoinTest
        // .handWrittenLombokShapedBuilderAlsoBorrows) and this class (the Bean Validation supplement).
        // Both must agree a hand-written builder that reproduces @Jacksonized's exact shape by hand —
        // a static nested class named in the Lombok convention, the same @JsonDeserialize/
        // @JsonPOJOBuilder annotation values, a build() returning the built type, and a one-argument
        // setter whose name and parameter type both match the field exactly — may still resolve, since
        // BuilderBorrowDetector cannot tell it apart from a real Lombok builder.
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(BuilderWireNameJoinTest.HandWrittenLombokShapedDto.class, validator);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(),
                "MetadataConstraintSource's own builder gate must resolve this shape the same way the floor"
                        + " does under BuilderWireNameJoinTest.handWrittenLombokShapedBuilderAlsoBorrows: a"
                        + " hand-written builder that reproduces @Jacksonized's exact shape may still borrow"
                        + " the built field's constraint, even under a validator; document: " + document);
    }

    @Test
    @DisplayName("a creator parameter without -parameters joins by constructor + index, not by name")
    void creatorParameterWithoutParametersJoinsByIndex() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.CreatorParamDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "amount_cents");
        assertEquals(
                List.of(10),
                keywordValues(closure, "maximum").stream()
                        .map(v -> v.decimalValue().intValue())
                        .toList());
    }

    // --- Coverage: shapes the walk cannot express ---

    @Test
    @DisplayName("List<@Positive Integer> renders a container-element constraint on items")
    void listContainerElementConstraint() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.ContainerDto.class, validator);

        JsonNode items = document.at("/properties/scores/items");
        assertFalse(items.isMissingNode(), "the array property must have an items subschema");
        assertEquals(0, items.get("exclusiveMinimum").decimalValue().intValue());
    }

    @Test
    @DisplayName("an inherited field constraint through a superclass is required")
    void inheritedFieldConstraint() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.InheritedFieldDto.class, validator);

        assertTrue(
                textValues(List.of(document), "required").contains("baseField"),
                "baseField must be required through the inherited @NotNull");
    }

    @Test
    @DisplayName("an inherited getter constraint through an implemented interface is required")
    void inheritedInterfaceGetterConstraint() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.InheritedGetterDto.class, validator);

        assertTrue(
                textValues(List.of(document), "required").contains("name"),
                "name must be required through the interface's inherited @NotNull getter");
    }

    @Test
    @DisplayName("a composed constraint's leaves (@Size + @Pattern) both render")
    void composedConstraintLeaves() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.ComposedDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "code");
        assertEquals(
                List.of(2),
                keywordValues(closure, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(20),
                keywordValues(closure, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(List.of("[A-Za-z0-9]+"), textValues(closure, "pattern"));
    }

    @Test
    @DisplayName(
            "design correction: a non-default-group constraint the floor already renders stays required, like main")
    void nonDefaultGroupOnVisibleMemberStaysRequiredLikeMain() {
        // The always-active floor — the schema library's own Jakarta Validation module for this scoped
        // field — has no Bean Validation group concept at all: it renders NOT_NULLABLE_FIELD_IS_REQUIRED
        // from the raw @NotNull annotation's presence alone, exactly as `main` (no validator) does. The
        // metadata supplement never removes a keyword the floor already rendered, so the document stays
        // required here even though the constraint's own group is non-Default — see the design
        // correction: "never remove a keyword the module emitted, including constraints in non-default
        // groups, which stay rendered as on main."
        Validator validator = MetadataTestValidators.plain();
        JsonNode withValidator = metadataDocument(MetadataFixtures.NonDefaultGroupDto.class, validator);
        JsonNode withoutValidator = walkDocument(MetadataFixtures.NonDefaultGroupDto.class);

        assertTrue(
                textValues(List.of(withValidator), "required").contains("secret"),
                "the floor's own group-blind rendering must survive the metadata supplement unchanged");
        assertEquals(
                textValues(List.of(withoutValidator), "required"),
                textValues(List.of(withValidator), "required"),
                "a validator must not change required-ness the floor alone already determined");
    }

    @Test
    @DisplayName("the metadata supplement's own group filter still excludes what only it could ever add")
    void nonDefaultGroupExcludedFromMetadataOnlyAddition() {
        // "label" carries no reflective annotation at all — the floor structurally cannot see it — so
        // whether it renders required depends entirely on the metadata supplement's own group filter,
        // isolated from the floor's group-blindness proven above.
        String mapping = """
                <?xml version="1.0" encoding="UTF-8"?>
                <constraint-mappings
                        xmlns="https://jakarta.ee/xml/ns/validation/mapping"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="https://jakarta.ee/xml/ns/validation/mapping https://jakarta.ee/xml/ns/validation/mapping/validation-mapping-3.1.xsd"
                        version="3.1">
                    <bean class="dev.vertique.json.schema.MetadataFixtures$XmlMappedNonDefaultGroupDto" ignore-annotations="false">
                        <field name="label">
                            <constraint annotation="jakarta.validation.constraints.NotNull">
                                <groups>
                                    <value>dev.vertique.json.schema.MetadataFixtures$AdminGroup</value>
                                </groups>
                            </constraint>
                        </field>
                    </bean>
                </constraint-mappings>
                """;
        Validator validator = MetadataTestValidators.withXmlMapping(mapping);
        JsonNode document = metadataDocument(MetadataFixtures.XmlMappedNonDefaultGroupDto.class, validator);

        assertFalse(
                textValues(List.of(document), "required").contains("label"),
                "a constraint the metadata supplement is the sole source for must still respect its own group filter");
    }

    @Test
    @DisplayName("a constraint declared by XML mapping alone is rendered, invisible to any annotation walk")
    void xmlMappedConstraint() {
        String mapping = """
                <?xml version="1.0" encoding="UTF-8"?>
                <constraint-mappings
                        xmlns="https://jakarta.ee/xml/ns/validation/mapping"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="https://jakarta.ee/xml/ns/validation/mapping https://jakarta.ee/xml/ns/validation/mapping/validation-mapping-3.1.xsd"
                        version="3.1">
                    <bean class="dev.vertique.json.schema.MetadataFixtures$XmlMappedDto" ignore-annotations="false">
                        <field name="label">
                            <constraint annotation="jakarta.validation.constraints.NotBlank"/>
                            <constraint annotation="jakarta.validation.constraints.Size">
                                <element name="min">3</element>
                                <element name="max">10</element>
                            </constraint>
                        </field>
                    </bean>
                </constraint-mappings>
                """;
        Validator validator = MetadataTestValidators.withXmlMapping(mapping);
        JsonNode document = metadataDocument(MetadataFixtures.XmlMappedDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "label");
        // @Size(min=3) wins over @NotBlank's minLength=1 floor (Size always overwrites; @NotBlank only
        // fills a gap). @NotBlank does drive `required`, matching victools' own isNullable() check
        // (confirmed by disassembly: NOT_NULLABLE_FIELD_IS_REQUIRED treats @NotNull, @NotBlank, and
        // @NotEmpty identically).
        assertEquals(
                List.of(3),
                keywordValues(closure, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(10),
                keywordValues(closure, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertTrue(textValues(List.of(document), "required").contains("label"));
    }

    // --- #606: shapes victools' Jakarta module misrenders or ignores ---

    @Test
    @DisplayName("#606: @Range, @Length, @URL, and a flagged @Pattern all render, unlike victools' Jakarta module")
    void sharp606Shapes() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.Sharp606Dto.class, validator);

        List<JsonNode> range = propertyClosure(document, "range");
        assertEquals(
                List.of(10),
                keywordValues(range, "minimum").stream()
                        .map(v -> v.decimalValue().intValue())
                        .toList());
        assertEquals(
                List.of(20),
                keywordValues(range, "maximum").stream()
                        .map(v -> v.decimalValue().intValue())
                        .toList());

        List<JsonNode> length = propertyClosure(document, "length");
        assertEquals(
                List.of(2),
                keywordValues(length, "minLength").stream()
                        .map(JsonNode::intValue)
                        .toList());
        assertEquals(
                List.of(8),
                keywordValues(length, "maxLength").stream()
                        .map(JsonNode::intValue)
                        .toList());

        List<JsonNode> url = propertyClosure(document, "url");
        assertEquals(List.of("uri"), textValues(url, "format"));

        List<JsonNode> pattern = propertyClosure(document, "caseInsensitivePattern");
        assertEquals(List.of("(?i:^abc$)"), textValues(pattern, "pattern"));

        List<JsonNode> exclusiveMax = propertyClosure(document, "exclusiveMax");
        assertEquals(
                0,
                new java.math.BigDecimal("9.5")
                        .compareTo(keywordValues(exclusiveMax, "exclusiveMaximum")
                                .get(0)
                                .decimalValue()));
    }

    // --- F7 (security review round 1, LOW): a #606 correction must not loosen a stricter floor bound ---

    @Test
    @DisplayName(
            "F7: a @Range correction does not overwrite a stricter minimum the floor rendered from a"
                    + " separate @Min on the same field")
    void correctionDoesNotLoosenAStricterFloorBound() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.F7StricterCorrectionDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "rangeBesideStricterMin");
        assertEquals(
                List.of(15),
                keywordValues(closure, "minimum").stream()
                        .map(v -> v.decimalValue().intValue())
                        .toList(),
                "the @Range(min = 10) correction must not loosen the stricter minimum @Min(15) already"
                        + " rendered — Bean Validation enforces the conjunction of both, 15 being the"
                        + " tighter bound; document: " + document);
        assertEquals(
                List.of(20),
                keywordValues(closure, "maximum").stream()
                        .map(v -> v.decimalValue().intValue())
                        .toList(),
                "the @Range's own maximum, which no other annotation contests, must still apply; document: "
                        + document);
    }

    @Test
    @DisplayName(
            "F7: #606's flagged-@Pattern rendering still replaces the floor's own unflagged rendering of"
                    + " the *same* @Pattern outright (regression guard for sharp606Shapes)")
    void sameAnnotationFlaggedPatternStillReplacesOutright() {
        // The one shape already exercised end-to-end by sharp606Shapes: the floor (the schema library's
        // own Jakarta module) renders a plain @Pattern's regexp with no flag awareness, and this
        // source's own correction re-renders the *same* annotation with its flags embedded (#606). The
        // two are not different annotations in conflict — mergePatternAsAllOf must keep replacing the
        // floor's rendering outright, never combine them into a redundant allOf.
        com.fasterxml.jackson.databind.node.ObjectNode schema =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "string").put("pattern", "^abc$");

        InputPropertyDescriber.applyCorrection(schema, "pattern", "(?i:^abc$)");

        assertEquals(
                "(?i:^abc$)",
                schema.path("pattern").asText(null),
                "the flag-aware rendering must replace the floor's own outright; schema: " + schema);
        assertFalse(schema.has("allOf"), "the same-annotation case must not produce a redundant allOf; schema: " + schema);
    }

    @Test
    @DisplayName("F7: a #606 pattern correction genuinely disagreeing with the floor's pattern is combined as an allOf")
    void genuinelyDifferentPatternCorrectionIsCombinedAsAllOf() {
        // A correction whose value is not a flagged rendering of the floor's own pattern — a genuine
        // two-source disagreement, the shape the fix's "keep both, as an allOf" remedy targets.
        com.fasterxml.jackson.databind.node.ObjectNode schema =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "string").put("pattern", "^[A-Z]+$");

        InputPropertyDescriber.applyCorrection(schema, "pattern", "^[a-z]+$");

        assertFalse(schema.has("pattern"), "the plain pattern keyword must be replaced by the allOf; schema: " + schema);
        List<String> patterns = new java.util.ArrayList<>();
        schema.path("allOf").forEach(branch -> patterns.add(branch.path("pattern").asText()));
        assertEquals(
                List.of("^[A-Z]+$", "^[a-z]+$"),
                patterns,
                "both the floor's and the correction's pattern must be enforced, as an allOf; schema: " + schema);
    }

    @Test
    @DisplayName("F7: a stricter bound correction still wins outright — the merge only ever narrows, never widens")
    void strictestNumericBoundWinsRegardlessOfWhichSideIsStricter() {
        com.fasterxml.jackson.databind.node.ObjectNode schema =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "integer").put("minimum", 10L).put("maximum", 100L);

        // A correction stricter than the floor on both bounds must still win.
        InputPropertyDescriber.applyCorrection(schema, "minimum", 20L);
        InputPropertyDescriber.applyCorrection(schema, "maximum", 50L);

        assertEquals(20, schema.path("minimum").intValue());
        assertEquals(50, schema.path("maximum").intValue());
    }

    // --- W3: fully-qualified constraint-type matching ---

    @Test
    @DisplayName(
            "W3: an app-defined @Size in another package that composes @Pattern renders the pattern, not the Size case")
    void applicationDefinedSizeCollisionRendersOnlyItsComposedPattern() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.AppDefinedSizeCollisionDto.class, validator);

        List<JsonNode> closure = propertyClosure(document, "code");
        assertEquals(
                List.of("[A-Z]+"),
                textValues(closure, "pattern"),
                "the composed @Pattern leaf must render; document: " + document);
        assertTrue(
                keywordValues(closure, "minLength").isEmpty()
                        && keywordValues(closure, "maxLength").isEmpty(),
                "the app-defined @Size's own (String-typed) min/max must never be read as if they were"
                        + " jakarta.validation.constraints.Size's (int-typed) min/max — matching by simple"
                        + " name alone risked exactly that misread; document: " + document);
    }

    // --- S5: two @Pattern constraints in the default group on one member render as allOf ---

    @Test
    @DisplayName("S5: two @Pattern constraints in the default group render as an allOf of both patterns")
    void twoPatternsRenderAsAllOf() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.TwoPatternsDto.class, validator);

        JsonNode property = document.at("/properties/code");
        assertTrue(
                property.has("allOf"), "two default-group @Pattern constraints must render as allOf; was: " + property);
        List<String> patterns = new java.util.ArrayList<>();
        property.get("allOf")
                .forEach(branch -> patterns.add(branch.path("pattern").asText()));
        assertEquals(
                List.of(".*[0-9]$", "^[A-Z].*"),
                patterns.stream().sorted().toList(),
                "both patterns must render, each as its own allOf branch; document: " + document);
        assertFalse(
                property.has("pattern"), "a single top-level \"pattern\" keyword cannot hold two regular expressions");
    }

    @Test
    @DisplayName("S5 (honest parity check): without a validator, the walk silently renders neither @Pattern at all")
    void twoPatternsUnderTheWalkAloneRenderNeitherPattern() {
        // Documents the real, pre-existing gap S5 does not fix: WalkConstraintSource joins a single
        // @Pattern by jacksonMember.getAnnotation(Pattern.class), which returns null once two @Pattern
        // annotations collapse into one @Pattern.List container — the annotation actually present on
        // the member is the container, not a repeated Pattern. So without a validator, this shape's
        // patterns are dropped entirely, silently: this test proves that current, known behavior, so a
        // general with/without-validator parity assertion for other shapes is not mistakenly extended
        // to cover this one too.
        JsonNode document = walkDocument(MetadataFixtures.TwoPatternsDto.class);

        JsonNode property = document.at("/properties/code");
        assertFalse(property.has("pattern"), "the walk alone must not render either pattern; was: " + property);
        assertFalse(property.has("allOf"), "the walk alone has no allOf-composition mechanism; was: " + property);
    }

    // --- C3 ---

    @Test
    @DisplayName("C3: a static-factory @JsonCreator parameter's constraint still renders through the walk under a"
            + " validator, even though Bean Validation cannot see static factories")
    void staticFactoryCreatorParameterConstraintRendersUnderAValidator() {
        // MetadataConstraintSource#forParameter contributes nothing here: the parameter's owner is a
        // static Method, never a Constructor, and BeanDescriptor#getConstraintsForConstructor covers
        // constructors only — Bean Validation exposes no metadata for a static factory at all. The
        // constraint is not lost, though: WalkConstraintSource (the floor) reads
        // jacksonMember.getAnnotation(Size.class) directly from the parameter's own Jackson-merged
        // annotation map, and it always runs first regardless of whether a Validator is supplied.
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.StaticFactoryDto.class, validator);

        JsonNode code = document.at("/properties/code");
        assertFalse(code.isMissingNode(), "code must be published: the static factory parameter binds it");
        assertEquals(
                3,
                code.at("/maxLength").asInt(),
                "a static-factory parameter's own @Size(max = 3) must still render through the walk even with a"
                        + " validator supplied, since the metadata supplement cannot see it at all; document: "
                        + document);
    }

    // --- BG1 ---

    @Test
    @DisplayName("BG1: a Lombok builder's constrained private field with no getter is type-only without a"
            + " validator and maxLength with one")
    void lombokBuilderNoGetterPropertyIsTypeOnlyWithoutAValidatorAndConstrainedWithOne() {
        // Without a validator, the constraint is not enforced by the generated schema at all — this is
        // the package's per-mode behavior (floor alone / floor+supplement), not a bug: the floor's own
        // builder borrow (InputPropertyDescriber#borrowBuilderFieldAttributes) goes through Jackson's
        // own BeanDescription#findProperties() for the built class, which — with neither a public field
        // nor a getter for "name" — does not surface it as a property at all, so there is nothing to
        // borrow from. Bean Validation does not depend on Jackson's introspection: it reads the
        // constrained field directly by Java name, so the metadata supplement still finds and renders
        // the constraint once a validator is supplied.
        JsonNode withoutValidator = walkDocument(MetadataFixtures.Bg1Dto.class);
        JsonNode nameWithoutValidator = withoutValidator.at("/properties/name");

        assertFalse(
                nameWithoutValidator.isMissingNode(),
                "the property must still be published: the builder method binds it; document: " + withoutValidator);
        assertEquals(
                "string",
                nameWithoutValidator.at("/type").asText(),
                "sanity: the property must be described by its Jackson-resolved type; document: " + withoutValidator);
        assertTrue(
                nameWithoutValidator.at("/maxLength").isMissingNode(),
                "without a validator, the constraint on a getter-less Lombok builder field must not be"
                        + " enforced by the schema at all — the floor's builder borrow has no Jackson-visible"
                        + " property to borrow from; document: " + withoutValidator);

        Validator validator = MetadataTestValidators.plain();
        JsonNode withValidator = metadataDocument(MetadataFixtures.Bg1Dto.class, validator);
        JsonNode nameWithValidator = withValidator.at("/properties/name");

        assertEquals(
                5,
                nameWithValidator.at("/maxLength").asInt(),
                "with a validator supplied, the metadata supplement must still find and render the"
                        + " constraint: Validator#getConstraintsForClass reads the field directly by Java"
                        + " name, unaffected by Jackson's own introspection having nothing to offer the"
                        + " floor; document: " + withValidator);
    }

    // --- D4 ---

    @Test
    @DisplayName("D4: a generic holder's @Size member bound to Integer renders type:integer with no size keyword")
    void genericHolderBoundIntMemberRendersNoSizeKeyword() {
        // GenericHolderBase<T>.value's reflected java.lang.reflect.Field#getType() is the type
        // variable's erasure (Object, unbounded) regardless of what any holder binds T to; only the
        // Jackson-resolved JavaType for GenericHolderBoundIntDto.boxed's own parameterization says
        // Integer. ConstraintValueKind.fromJavaType must be given that resolved type, not the field's
        // raw reflected one, so the kind is NUMBER here — and @Size has no keyword family for a number,
        // so the correct render is nothing at all: no minLength/maxLength (the wrong, pre-fix kind's
        // fallback), and no minItems/maxItems/minProperties/maxProperties either.
        Validator validator = MetadataTestValidators.plain();
        JsonNode document = metadataDocument(MetadataFixtures.GenericHolderBoundIntDto.class, validator);

        List<JsonNode> boxedClosure = propertyClosure(document, "boxed");
        List<JsonNode> valueClosure = nestedPropertyClosure(document, boxedClosure, "value");

        assertEquals(
                List.of("integer"),
                textValues(valueClosure, "type"),
                "sanity: the schema library's own resolution must already describe \"value\" as an integer for"
                        + " GenericHolderBoundIntDto's own parameterization; document: " + document);
        for (String keyword :
                List.of("maxLength", "minLength", "maxItems", "minItems", "maxProperties", "minProperties")) {
            assertTrue(
                    keywordValues(valueClosure, keyword).isEmpty(),
                    "@Size has no \"" + keyword + "\" family for a number-kind member; document: " + document);
        }
    }

    /** Like {@link SchemaAssertions#propertyClosure}, but rooted at an already-resolved container's closure. */
    private static List<JsonNode> nestedPropertyClosure(
            JsonNode document, List<JsonNode> containerClosure, String property) {
        List<JsonNode> collected = new java.util.ArrayList<>();
        for (JsonNode root : containerClosure) {
            JsonNode properties = root.get("properties");
            if (properties == null || !properties.isObject()) {
                continue;
            }
            JsonNode declared = properties.get(property);
            if (declared != null) {
                collected.addAll(SchemaAssertions.conjunctiveClosure(document, declared));
            }
        }
        assertFalse(collected.isEmpty(), "no schema node was found for nested property '" + property + "'");
        return collected;
    }

    // --- Fallback ---

    @Test
    @DisplayName("without a validator, generation is unchanged from the annotation walk")
    void fallbackWithoutValidatorMatchesWalk() {
        String withNullValidator = AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), null)
                .generateCanonical(MetadataFixtures.RenamedFieldDto.class);
        String throughOverload = AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile())
                .generateCanonical(MetadataFixtures.RenamedFieldDto.class);

        assertEquals(throughOverload, withNullValidator);
    }

    /**
     * Every {@code MetadataFixtures} coverage fixture — the shapes this class and {@link
     * MetadataParityTest} generate under a real {@link Validator} — as its own root type, for the
     * broader fallback-identity proof below.
     */
    static Stream<Class<?>> coverageFixtures() {
        return Arrays.stream(MetadataFixtures.class.getDeclaredClasses());
    }

    @ParameterizedTest
    @MethodSource("coverageFixtures")
    @DisplayName("fallback identity: forInputProfile(profile) matches forInputProfile(profile, null) for every"
            + " coverage fixture, not just one type")
    void fallbackMatchesSingleArgumentFactoryForEveryCoverageFixture(Class<?> fixture) {
        // The single-argument factory (AnnotationJsonSchemaGenerator#forInputProfile(JsonMapperProfile))
        // and the two-argument overload called with an explicit null Validator must be indistinguishable
        // generators for every shape this module's own coverage exercises, not merely the one DTO
        // fallbackWithoutValidatorMatchesWalk above happens to use — a divergence limited to one
        // particular shape (a builder, a record, an inherited member, a composed constraint, ...) would
        // otherwise go unnoticed.
        String throughOverload =
                AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile()).generateCanonical(fixture);
        String withNullValidator = AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), null)
                .generateCanonical(fixture);

        assertEquals(
                throughOverload,
                withNullValidator,
                "the single-argument factory and the two-argument overload with a null Validator must be"
                        + " byte-identical for " + fixture.getSimpleName());
    }
}
