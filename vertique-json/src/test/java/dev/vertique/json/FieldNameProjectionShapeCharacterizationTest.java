// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver.PromotedField;
import dev.vertique.json.BindingShapeFixtures.Address;
import dev.vertique.json.BindingShapeFixtures.Built;
import dev.vertique.json.BindingShapeFixtures.Custom;
import dev.vertique.json.BindingShapeFixtures.Customer;
import dev.vertique.json.BindingShapeFixtures.Generated;
import dev.vertique.json.BindingShapeFixtures.GetterField;
import dev.vertique.json.BindingShapeFixtures.GetterOnlyBean;
import dev.vertique.json.BindingShapeFixtures.IgnoredBean;
import dev.vertique.json.BindingShapeFixtures.IgnoredByClass;
import dev.vertique.json.BindingShapeFixtures.Item;
import dev.vertique.json.BindingShapeFixtures.Money;
import dev.vertique.json.BindingShapeFixtures.Open;
import dev.vertique.json.BindingShapeFixtures.Point;
import dev.vertique.json.BindingShapeFixtures.PropertyShapes;
import dev.vertique.json.BindingShapeFixtures.PublicFields;
import dev.vertique.json.BindingShapeFixtures.ReadOnlyBean;
import dev.vertique.json.BindingShapeFixtures.Resident;
import dev.vertique.json.BindingShapeFixtures.Review;
import dev.vertique.json.BindingShapeFixtures.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterizes what the Jackson-backed field-name projection reports for bean shapes whose binding
 * Jackson reshapes: the bound Java names of the owner, the logical name of a wire key and the promoted
 * members, together with how the mapper's deserialization introspection binds the named property.
 *
 * <p>These are the answers of the projection as shipped, pinned exactly. The JSON pointer resolver's
 * binding guards cover what these answers miss, and the bound Java names must stay exactly as pinned
 * here when the rule deciding whether a type binds through its properties is shared with the core
 * module.
 */
class FieldNameProjectionShapeCharacterizationTest {

    /** The properties of {@link PropertyShapes}; the projection lists every one of them. */
    private static final Set<String> PROPERTY_SHAPES =
            Set.of("partial", "pair", "viaUsing", "viaContentUsing", "converted", "refined", "refinedItems", "byKey");

    /** Marks an owner whose bindings the projection reports it cannot enumerate. */
    private static final Set<String> NOT_ENUMERABLE = null;

    static Stream<Arguments> shapes() {
        return Stream.of(
                // --- members the mapper does not write ---
                arguments(
                        "read-only property",
                        ReadOnlyBean.class,
                        "id",
                        "id",
                        "no mutator",
                        Set.of("id", "name"),
                        "id",
                        Map.of()),
                arguments(
                        "computed getter-only property",
                        GetterOnlyBean.class,
                        "label",
                        "label",
                        "no mutator",
                        Set.of("label", "name", "tags"),
                        "label",
                        Map.of()),
                arguments(
                        "fieldless getter-only collection",
                        GetterOnlyBean.class,
                        "tags",
                        "tags",
                        "no mutator",
                        Set.of("label", "name", "tags"),
                        "tags",
                        Map.of()),
                arguments(
                        "ignored property",
                        IgnoredBean.class,
                        "secret",
                        "secret",
                        "not introspected",
                        Set.of("name"),
                        "secret",
                        Map.of()),
                arguments(
                        "name ignored by the class",
                        IgnoredByClass.class,
                        "secret",
                        "secret",
                        "field+setter",
                        Set.of("name", "secret"),
                        "secret",
                        Map.of()),
                arguments(
                        "names ignored by the property",
                        PropertyShapes.class,
                        "partial",
                        "partial",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "partial",
                        Map.of()),
                // --- reshaped beans ---
                arguments("array-shaped class", Point.class, "x", "x", "field+setter", Set.of("x", "y"), "x", Map.of()),
                arguments(
                        "array-shaped property",
                        PropertyShapes.class,
                        "pair",
                        "pair",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "pair",
                        Map.of()),
                arguments(
                        "value-written bean with a delegating creator",
                        Money.class,
                        "currency",
                        "currency",
                        "field+setter",
                        NOT_ENUMERABLE,
                        "currency",
                        Map.of()),
                arguments(
                        "scalar creator beside a default constructor",
                        Tag.class,
                        "name",
                        "name",
                        "field+setter",
                        Set.of("name"),
                        "name",
                        Map.of()),
                arguments("builder", Built.class, "name", "name", "field", NOT_ENUMERABLE, "name", Map.of()),
                arguments(
                        "builder without method prefixes",
                        Generated.class,
                        "name",
                        "name",
                        "field",
                        NOT_ENUMERABLE,
                        "name",
                        Map.of()),
                arguments(
                        "class-level custom deserializer",
                        Custom.class,
                        "x",
                        "x",
                        "field+setter",
                        NOT_ENUMERABLE,
                        "x",
                        Map.of()),
                // --- property-level deserialization annotations ---
                arguments(
                        "property-level custom deserializer",
                        PropertyShapes.class,
                        "viaUsing",
                        "viaUsing",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "viaUsing",
                        Map.of()),
                arguments(
                        "property-level content deserializer",
                        PropertyShapes.class,
                        "viaContentUsing",
                        "viaContentUsing",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "viaContentUsing",
                        Map.of()),
                arguments(
                        "property-level converter",
                        PropertyShapes.class,
                        "converted",
                        "converted",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "converted",
                        Map.of()),
                arguments(
                        "property-level refined type",
                        PropertyShapes.class,
                        "refined",
                        "refined",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "refined",
                        Map.of()),
                arguments(
                        "property-level refined content type",
                        PropertyShapes.class,
                        "refinedItems",
                        "refinedItems",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "refinedItems",
                        Map.of()),
                arguments(
                        "property-level key deserializer",
                        PropertyShapes.class,
                        "byKey",
                        "byKey",
                        "creator parameter",
                        PROPERTY_SHAPES,
                        "byKey",
                        Map.of()),
                // --- members the bean collects or promotes ---
                arguments(
                        "any-setter backing member",
                        Open.class,
                        "extras",
                        "extras",
                        "setter",
                        Set.of("extras", "name"),
                        "extras",
                        Map.of()),
                arguments(
                        "unwrapped member with a prefix",
                        Customer.class,
                        "address",
                        "addr_street",
                        "field+setter",
                        Set.of("address", "name"),
                        "addr_street",
                        Map.of(
                                "addr_street", new PromotedField(Address.class, "street", List.of("address")),
                                "addr_city", new PromotedField(Address.class, "city", List.of("address")))),
                arguments(
                        "unwrapped member without a prefix",
                        Resident.class,
                        "address",
                        "street",
                        "field+setter",
                        Set.of("address", "name"),
                        "street",
                        Map.of(
                                "street", new PromotedField(Address.class, "street", List.of("address")),
                                "city", new PromotedField(Address.class, "city", List.of("address")))),
                // --- binding kinds of plain members ---
                arguments(
                        "public field", PublicFields.class, "name", "name", "field", Set.of("name"), "name", Map.of()),
                arguments(
                        "private field with a getter and no setter",
                        GetterField.class,
                        "name",
                        "name",
                        "field",
                        Set.of("name"),
                        "name",
                        Map.of()),
                arguments(
                        "single-component record",
                        Item.class,
                        "name",
                        "name",
                        "creator parameter",
                        Set.of("name"),
                        "name",
                        Map.of()),
                arguments(
                        "renamed single-component record",
                        Review.class,
                        "name",
                        "display_name",
                        "creator parameter",
                        Set.of("name"),
                        "name",
                        Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    @DisplayName("the field-name projection answers exactly as characterized for each binding shape")
    void projectionAnswersPerShape(
            String shape,
            Class<?> owner,
            String javaName,
            String wireKey,
            String expectedBinding,
            Set<String> expectedBoundJavaNames,
            String expectedLogicalName,
            Map<String, PromotedField> expectedPromotedFields) {
        // Given: the process mapper's projection and a type with one binding shape (the row)
        ObjectMapper mapper = VertiqueJson.mapper();
        JacksonFieldNameResolver projection = JacksonFieldNameResolver.forMapper(mapper);

        // When: the projection and the mapper's introspection are asked about the type
        Set<String> boundJavaNames = projection.boundJavaNames(owner);
        String logicalName = projection.logicalName(owner, wireKey);
        Map<String, PromotedField> promotedFields = projection.promotedFields(owner);
        String binding = binding(mapper, owner, javaName);

        // Then: every answer is exactly the characterized one
        assertAll(
                () -> assertEquals(expectedBoundJavaNames, boundJavaNames, "boundJavaNames of " + shape),
                () -> assertEquals(expectedLogicalName, logicalName, "logicalName of '" + wireKey + "' for " + shape),
                () -> assertEquals(expectedPromotedFields, promotedFields, "promotedFields of " + shape),
                () -> assertEquals(expectedBinding, binding, "how the mapper binds '" + javaName + "' for " + shape));
    }

    /**
     * Describes how the mapper's deserialization introspection binds one property: the mutators it
     * reports joined by {@code +}, {@code no mutator} when it reports none, or {@code not introspected}
     * when the property is not reported at all.
     */
    private static String binding(ObjectMapper mapper, Class<?> owner, String javaName) {
        DeserializationConfig config = mapper.getDeserializationConfig();
        for (BeanPropertyDefinition property :
                config.introspect(mapper.constructType(owner)).findProperties()) {
            if (javaName.equals(property.getInternalName())) {
                List<String> kinds = new ArrayList<>();
                if (property.hasField()) {
                    kinds.add("field");
                }
                if (property.hasSetter()) {
                    kinds.add("setter");
                }
                if (property.hasConstructorParameter()) {
                    kinds.add("creator parameter");
                }
                return kinds.isEmpty() ? "no mutator" : String.join("+", kinds);
            }
        }
        return "not introspected";
    }
}
