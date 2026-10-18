// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import dev.vertique.json.BindingShapeFixtures.Address;
import dev.vertique.json.BindingShapeFixtures.Built;
import dev.vertique.json.BindingShapeFixtures.Child;
import dev.vertique.json.BindingShapeFixtures.ClassShapes;
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
import dev.vertique.json.BindingShapeFixtures.Pair;
import dev.vertique.json.BindingShapeFixtures.PairHolder;
import dev.vertique.json.BindingShapeFixtures.Point;
import dev.vertique.json.BindingShapeFixtures.PropertyShapes;
import dev.vertique.json.BindingShapeFixtures.PublicFields;
import dev.vertique.json.BindingShapeFixtures.ReadOnlyBean;
import dev.vertique.json.BindingShapeFixtures.Resident;
import dev.vertique.json.BindingShapeFixtures.Review;
import dev.vertique.json.BindingShapeFixtures.SpecialChild;
import dev.vertique.json.BindingShapeFixtures.Tag;
import io.vertx.core.json.Json;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that the JSON pointer resolver stops at every binding the route's field-name projection
 * cannot name, in projection mode with the request document: members the mapper does not write,
 * beans and properties whose binding Jackson reshapes, any-setter and unwrapped members, and
 * collections whose element positions differ between the document and the bound value.
 *
 * <p>Each row's document is shaped as the row's mapper reads it, which the row also checks. Sibling
 * members that bind normally are resolved in the same rows, so a guard that truncates too much fails
 * as well as one that truncates too little. The mapper-only rows of the same shapes live in the core
 * module's binding-guard test.
 */
class JakartaPathPointersProjectionGuardsTest {

    // --- Mappers ---

    /** The process mapper, which most routes bind with. */
    private static final ObjectMapper PROCESS = VertiqueJson.mapper();

    /** Reads {@link Pair} from a JSON array through a config override instead of an annotation. */
    private static final ObjectMapper PAIR_AS_ARRAY = JsonMapper.builder()
            .withConfigOverride(Pair.class, o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.ARRAY)))
            .build();

    /** Skips null list elements for every property. */
    private static final ObjectMapper SKIPS_NULL_CONTENT_BY_DEFAULT = JsonMapper.builder()
            .defaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.SKIP))
            .build();

    /** Skips null list elements through a config override on {@link List}. */
    private static final ObjectMapper SKIPS_NULL_CONTENT_BY_OVERRIDE = JsonMapper.builder()
            .withConfigOverride(List.class, o -> o.setSetterInfo(JsonSetter.Value.forContentNulls(Nulls.SKIP)))
            .build();

    /** Reads a single value where a list is declared. */
    private static final ObjectMapper ACCEPTS_SINGLE_VALUES = JsonMapper.builder()
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    // --- Collection fixtures ---

    /** A list of single-component records. */
    public record Bag(List<Item> items) {}

    /** A list whose null elements the property skips. */
    public record SkippingBag(
            @JsonSetter(contentNulls = Nulls.SKIP) List<Item> items) {}

    /** A list whose own null value the property skips; its elements keep their positions. */
    public record ValueSkippingBag(
            @JsonSetter(nulls = Nulls.SKIP) List<Item> items) {}

    /** A list the property reads from a single value as well. */
    public record SingleValueBag(
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<Item> items) {}

    // --- Paths ---

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static Property property(String name, Class<?> ownerType) {
        return new Property(name, ownerType);
    }

    private static Index index(int index) {
        return new Index(index);
    }

    private static Opaque opaque() {
        return new Opaque();
    }

    static Stream<Arguments> reshapingRows() {
        Class<ClassShapes> c = ClassShapes.class;
        Class<PropertyShapes> p = PropertyShapes.class;
        return Stream.of(
                // --- members the mapper does not write stop at their bean ---
                arguments(
                        "read-only property stops at its bean",
                        PROCESS,
                        c,
                        path(property("readOnly", c), property("id", ReadOnlyBean.class)),
                        "{\"readOnly\":{\"id\":\"x\"}}",
                        "/readOnly"),
                arguments(
                        "setter-bound sibling of a read-only property resolves",
                        PROCESS,
                        c,
                        path(property("readOnly", c), property("name", ReadOnlyBean.class)),
                        "{\"readOnly\":{\"name\":\"\"}}",
                        "/readOnly/name"),
                arguments(
                        "computed getter-only property stops at its bean",
                        PROCESS,
                        c,
                        path(property("getterOnly", c), property("label", GetterOnlyBean.class)),
                        "{\"getterOnly\":{\"name\":\"n\"}}",
                        "/getterOnly"),
                arguments(
                        "fieldless getter-only collection stops at its bean",
                        PROCESS,
                        c,
                        path(property("getterOnly", c), property("tags", GetterOnlyBean.class), index(0)),
                        "{\"getterOnly\":{\"tags\":[\"x\"]}}",
                        "/getterOnly"),
                arguments(
                        "ignored property stops at its bean",
                        PROCESS,
                        c,
                        path(property("ignored", c), property("secret", IgnoredBean.class)),
                        "{\"ignored\":{\"secret\":\"x\"}}",
                        "/ignored"),
                arguments(
                        "name ignored by its class stops at its bean although it has a setter",
                        PROCESS,
                        c,
                        path(property("ignoredByClass", c), property("secret", IgnoredByClass.class)),
                        "{\"ignoredByClass\":{\"secret\":\"x\"}}",
                        "/ignoredByClass"),
                arguments(
                        "sibling of a name ignored by its class resolves",
                        PROCESS,
                        c,
                        path(property("ignoredByClass", c), property("name", IgnoredByClass.class)),
                        "{\"ignoredByClass\":{\"name\":\"\"}}",
                        "/ignoredByClass/name"),
                arguments(
                        "name ignored by the holding property stops at that property",
                        PROCESS,
                        p,
                        path(property("partial", p), property("name", Child.class)),
                        "{\"partial\":{\"name\":\"x\"}}",
                        "/partial"),
                arguments(
                        "name the holding property does not ignore resolves",
                        PROCESS,
                        p,
                        path(property("partial", p), property("note", Child.class)),
                        "{\"partial\":{\"note\":\"\"}}",
                        "/partial/note"),
                // --- reshaped beans stop at the property holding them ---
                arguments(
                        "array-shaped class stops at the property holding it",
                        PROCESS,
                        c,
                        path(property("point", c), property("x", Point.class)),
                        "{\"point\":[\"\",\"y\"]}",
                        "/point"),
                arguments(
                        "array-shaped class at the root stops at the root",
                        PROCESS,
                        Point.class,
                        path(property("x", Point.class)),
                        "[\"\",\"y\"]",
                        ""),
                arguments(
                        "array-shaped property stops at the property",
                        PROCESS,
                        p,
                        path(property("pair", p), property("left", Pair.class)),
                        "{\"pair\":[\"\",\"r\"]}",
                        "/pair"),
                arguments(
                        "array shape from a config override stops at the property",
                        PAIR_AS_ARRAY,
                        PairHolder.class,
                        path(property("pair", PairHolder.class), property("left", Pair.class)),
                        "{\"pair\":[\"\",\"r\"]}",
                        "/pair"),
                arguments(
                        "delegating creator stops at the property holding the bean",
                        PROCESS,
                        c,
                        path(property("money", c), property("currency", Money.class)),
                        "{\"money\":\"10\"}",
                        "/money"),
                arguments(
                        "scalar creator with an object at the position continues",
                        PROCESS,
                        c,
                        path(property("tag", c), property("name", Tag.class)),
                        "{\"tag\":{\"name\":\"\"}}",
                        "/tag/name"),
                arguments(
                        "scalar creator with a scalar at the position stops at the property",
                        PROCESS,
                        c,
                        path(property("tag", c), property("name", Tag.class)),
                        "{\"tag\":\"\"}",
                        "/tag"),
                arguments(
                        "scalar creator without a document stops at the property",
                        PROCESS,
                        c,
                        path(property("tag", c), property("name", Tag.class)),
                        null,
                        "/tag"),
                arguments(
                        "builder-bound bean stops at the property holding it",
                        PROCESS,
                        c,
                        path(property("built", c), property("name", Built.class)),
                        "{\"built\":{\"name\":\"\"}}",
                        "/built"),
                arguments(
                        "builder-bound root stops at the root",
                        PROCESS,
                        Built.class,
                        path(property("name", Built.class)),
                        "{\"name\":\"\"}",
                        ""),
                arguments(
                        "bean bound through a builder without method prefixes stops at the property holding it",
                        PROCESS,
                        c,
                        path(property("generated", c), property("name", Generated.class)),
                        "{\"generated\":{\"name\":\"\"}}",
                        "/generated"),
                arguments(
                        "class-level custom deserializer stops at the property holding the bean",
                        PROCESS,
                        c,
                        path(property("custom", c), property("x", Custom.class)),
                        "{\"custom\":{\"x\":\"\"}}",
                        "/custom"),
                // --- property-level deserialization annotations stop at the property ---
                arguments(
                        "property-level custom deserializer stops at the property",
                        PROCESS,
                        p,
                        path(property("viaUsing", p), property("name", Child.class)),
                        "{\"viaUsing\":{\"name\":\"\"}}",
                        "/viaUsing"),
                arguments(
                        "content deserializer stops at the collection property",
                        PROCESS,
                        p,
                        path(property("viaContentUsing", p), index(0), property("name", Child.class)),
                        "{\"viaContentUsing\":[{\"name\":\"\"}]}",
                        "/viaContentUsing"),
                arguments(
                        "converter stops at the property",
                        PROCESS,
                        p,
                        path(property("converted", p), property("name", Child.class)),
                        "{\"converted\":{\"name\":\"\"}}",
                        "/converted"),
                arguments(
                        "refined bound type stops at the property",
                        PROCESS,
                        p,
                        path(property("refined", p), property("name", SpecialChild.class)),
                        "{\"refined\":{\"name\":\"\"}}",
                        "/refined"),
                arguments(
                        "refined content type stops at the collection property",
                        PROCESS,
                        p,
                        path(property("refinedItems", p), index(0), property("name", SpecialChild.class)),
                        "{\"refinedItems\":[{\"name\":\"\"}]}",
                        "/refinedItems"),
                arguments(
                        "key deserializer leaves the map boundary where it is",
                        PROCESS,
                        p,
                        path(property("byKey", p), opaque(), property("name", Child.class)),
                        "{\"byKey\":{\"ZZKEY\":{\"name\":\"\"}}}",
                        "/byKey"),
                // --- collected and promoted members stop at the enclosing bean ---
                arguments(
                        "any-setter backing member stops at the bean",
                        PROCESS,
                        c,
                        path(property("open", c), property("extras", Open.class)),
                        "{\"open\":{\"ZZKEY\":\"x\"}}",
                        "/open"),
                arguments(
                        "entry of an any-setter backing member stops at the bean",
                        PROCESS,
                        c,
                        path(property("open", c), property("extras", Open.class), opaque()),
                        "{\"open\":{\"ZZKEY\":\"x\"}}",
                        "/open"),
                arguments(
                        "setter-bound sibling of an any-setter resolves",
                        PROCESS,
                        c,
                        path(property("open", c), property("name", Open.class)),
                        "{\"open\":{\"name\":\"\"}}",
                        "/open/name"),
                arguments(
                        "member unwrapped with a prefix stops at the enclosing bean",
                        PROCESS,
                        c,
                        path(
                                property("customer", c),
                                property("address", Customer.class),
                                property("street", Address.class)),
                        "{\"customer\":{\"addr_street\":\"\"}}",
                        "/customer"),
                arguments(
                        "member unwrapped with a prefix into the root stops at the root",
                        PROCESS,
                        Customer.class,
                        path(property("address", Customer.class), property("street", Address.class)),
                        "{\"addr_street\":\"\"}",
                        ""),
                arguments(
                        "member unwrapped without a prefix stops at the enclosing bean",
                        PROCESS,
                        c,
                        path(
                                property("resident", c),
                                property("address", Resident.class),
                                property("street", Address.class)),
                        "{\"resident\":{\"street\":\"\"}}",
                        "/resident"),
                arguments(
                        "setter-bound sibling of an unwrapped member resolves",
                        PROCESS,
                        c,
                        path(property("customer", c), property("name", Customer.class)),
                        "{\"customer\":{\"name\":\"\"}}",
                        "/customer/name"),
                arguments(
                        "a JsonNode property is never descended",
                        PROCESS,
                        c,
                        path(property("node", c), property("nodeType", null)),
                        "{\"node\":{\"nodeType\":\"\"}}",
                        "/node"),
                // --- members the mapper binds resolve ---
                arguments(
                        "public field resolves with its key present",
                        PROCESS,
                        c,
                        path(property("publicFields", c), property("name", PublicFields.class)),
                        "{\"publicFields\":{\"name\":\"\"}}",
                        "/publicFields/name"),
                arguments(
                        "private field with a getter resolves with its key present",
                        PROCESS,
                        c,
                        path(property("getterField", c), property("name", GetterField.class)),
                        "{\"getterField\":{\"name\":\"\"}}",
                        "/getterField/name"),
                arguments(
                        "single-component record in a list resolves",
                        PROCESS,
                        c,
                        path(property("items", c), index(0), property("name", Item.class)),
                        "{\"items\":[{\"name\":\"\"}]}",
                        "/items/0/name"),
                arguments(
                        "renamed single-component record resolves",
                        PROCESS,
                        Review.class,
                        path(property("name", Review.class)),
                        "{\"display_name\":\"\"}",
                        "/display_name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reshapingRows")
    @DisplayName("bindings the projection cannot name truncate the pointer in projection mode")
    void reshapingBindingsTruncate(
            String shape, ObjectMapper mapper, Type rootType, PropertyPath path, String document, String expected) {
        // Given: a route mapper, its own name projection and a request document shaped as that mapper reads it
        JakartaPathPointers.Resolver session =
                JakartaPathPointers.resolver(mapper, JacksonFieldNameResolver.forMapper(mapper));
        Object parsed = document == null ? null : Json.decodeValue(document);

        // When: the structured path resolves against the document
        String pointer = session.toPointer(path, rootType, parsed);

        // Then: the pointer is exactly the expected verified prefix and holds no client-chosen key
        List<Executable> checks = new ArrayList<>();
        if (document != null) {
            checks.add(() -> assertDoesNotThrow(
                    () -> mapper.readValue(document, mapper.constructType(rootType)),
                    "the row's document must bind with the row's mapper"));
        }
        checks.add(() -> assertEquals(expected, pointer, "pointer for '" + shape + "'"));
        checks.add(() -> assertFalse(pointer.contains("ZZ"), "a client-chosen key reached the pointer"));
        assertAll(checks);
    }

    // --- Collections whose element positions the binding changes ---

    /** The violated element: the first element of the bound list, which has one element. */
    private static final String NULL_THEN_ELEMENT = "{\"items\":[null,{\"name\":\"\"}]}";

    /** The same element sent as a single value instead of a list. */
    private static final String SINGLE_ELEMENT = "{\"items\":{\"name\":\"\"}}";

    /** A one-element list. */
    private static final String ONE_ELEMENT = "{\"items\":[{\"name\":\"\"}]}";

    static Stream<Arguments> collectionRows() {
        return Stream.of(
                // --- the bound list's positions differ from the document's: stop at the collection ---
                arguments(
                        "null elements skipped by the mapper's default setter info",
                        SKIPS_NULL_CONTENT_BY_DEFAULT,
                        Bag.class,
                        itemsPath(Bag.class),
                        NULL_THEN_ELEMENT,
                        "/items"),
                arguments(
                        "null elements skipped by a config override",
                        SKIPS_NULL_CONTENT_BY_OVERRIDE,
                        Bag.class,
                        itemsPath(Bag.class),
                        NULL_THEN_ELEMENT,
                        "/items"),
                arguments(
                        "null elements skipped by the property's setter info",
                        PROCESS,
                        SkippingBag.class,
                        itemsPath(SkippingBag.class),
                        NULL_THEN_ELEMENT,
                        "/items"),
                arguments(
                        "single values accepted by the mapper",
                        ACCEPTS_SINGLE_VALUES,
                        Bag.class,
                        itemsPath(Bag.class),
                        NULL_THEN_ELEMENT,
                        "/items"),
                arguments(
                        "single values accepted by the mapper, sent as a single value",
                        ACCEPTS_SINGLE_VALUES,
                        Bag.class,
                        itemsPath(Bag.class),
                        SINGLE_ELEMENT,
                        "/items"),
                arguments(
                        "single values accepted by the property's format",
                        PROCESS,
                        SingleValueBag.class,
                        itemsPath(SingleValueBag.class),
                        NULL_THEN_ELEMENT,
                        "/items"),
                arguments(
                        "single values accepted by the property's format, sent as a single value",
                        PROCESS,
                        SingleValueBag.class,
                        itemsPath(SingleValueBag.class),
                        SINGLE_ELEMENT,
                        "/items"),
                // --- positions the binding keeps are emitted ---
                arguments(
                        "a plain list keeps its index",
                        PROCESS,
                        Bag.class,
                        itemsPath(Bag.class),
                        ONE_ELEMENT,
                        "/items/0/name"),
                arguments(
                        "skipping a null list value keeps the index of its elements",
                        PROCESS,
                        ValueSkippingBag.class,
                        itemsPath(ValueSkippingBag.class),
                        ONE_ELEMENT,
                        "/items/0/name"));
    }

    private static PropertyPath itemsPath(Class<?> owner) {
        return path(property("items", owner), index(0), property("name", Item.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collectionRows")
    @DisplayName("null-skipping and single-value collections get no index in either naming mode")
    void nullSkippingAndSingleValueCollectionsGetNoIndex(
            String shape, ObjectMapper mapper, Type rootType, PropertyPath path, String document, String expected) {
        // Given: a mapper or property that changes the bound list's positions, and a document it reads
        JakartaPathPointers.Resolver projection =
                JakartaPathPointers.resolver(mapper, JacksonFieldNameResolver.forMapper(mapper));
        JakartaPathPointers.Resolver mapperOnly = JakartaPathPointers.resolver(mapper, null);

        // When: the path to the first bound element's member resolves in both naming modes
        String withProjection = projection.toPointer(path, rootType, Json.decodeValue(document));
        String withoutProjection = mapperOnly.toPointer(path, rootType, null);

        // Then: both modes stop at the collection unless the binding keeps the document's positions
        assertAll(
                () -> assertDoesNotThrow(
                        () -> mapper.readValue(document, mapper.constructType(rootType)),
                        "the row's document must bind with the row's mapper"),
                () -> assertEquals(expected, withProjection, "projection mode for '" + shape + "'"),
                () -> assertEquals(expected, withoutProjection, "mapper-only mode for '" + shape + "'"));
    }
}
