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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
