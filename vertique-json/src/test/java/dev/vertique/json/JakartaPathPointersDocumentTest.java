// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import io.vertx.core.json.Json;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the document check of the JSON pointer resolver in projection mode: every intermediate
 * member and position must exist in the request document; an omitted final member is named only when
 * its parent object exists and the violated property is itself written through a setter, a creator
 * parameter or a record component; a present member is named by the one declared spelling the client
 * sent; and no key the client chose ever reaches the pointer.
 *
 * <p>The fixtures carry no constraint annotations: each row hands the resolver the structured path a
 * validator would report for the violated property. Each row's document is checked to bind with the
 * row's mapper. A pointer that does not name an omitted member must resolve in the document; a pointer
 * that names one must have a parent that resolves to an object without that member.
 */
class JakartaPathPointersDocumentTest {

    // --- Fixtures ---

    /** A single-component record. */
    public record Named(String name) {}

    /** A list of single-component records. */
    public record Bag(List<Named> items) {}

    /** A setter-bound bean. */
    public static class Owner {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Holds a setter-bound bean. */
    public record Household(Owner owner) {}

    /** Three nested records, for a member missing between the root and the leaf. */
    public record Shipment(Leg leg) {}

    /** The middle level of {@link Shipment}. */
    public record Leg(Named stop) {}

    /**
     * The constraint sits on field {@code zipCode}; the mapper writes it only through the renamed setter
     * {@code setZip}. The getter makes the field a property of its own, read through the field.
     */
    public static class RenamedSetter {
        private String zipCode;

        public String getZipCode() {
            return zipCode;
        }

        @JsonProperty("zip_code")
        public void setZip(String zip) {
            this.zipCode = zip;
        }
    }

    /**
     * The constraint sits on field {@code streetName}; the mapper writes it only through the renamed
     * creator parameter {@code street_name}.
     */
    public static class RenamedCreator {
        private final String streetName;

        @JsonCreator
        public RenamedCreator(@JsonProperty("street_name") String street) {
            this.streetName = street;
        }

        public String getStreetName() {
            return streetName;
        }
    }

    /** A public-field bean. */
    public static class PublicName {
        public String name;
    }

    /** A setter-bound property renamed by the route's naming strategy. */
    public static class SetterZip {
        private String zipCode;

        public String getZipCode() {
            return zipCode;
        }

        public void setZipCode(String zipCode) {
            this.zipCode = zipCode;
        }
    }

    /** A public-field property renamed by the route's naming strategy. */
    public static class FieldZip {
        public String zipCode;
    }

    /** A public-field property renamed by its annotation. */
    public static class PublicZip {
        @JsonProperty("zip_code")
        public String zipCode;
    }

    /**
     * A renamed setter-bound property with an alias. Setter-bound, because the mapper binds the last of
     * two keys for one setter but rejects a second key for one creator parameter.
     */
    public static class Aliased {
        private String zipCode;

        public String getZipCode() {
            return zipCode;
        }

        @JsonProperty("zip_code")
        @JsonAlias("zip")
        public void setZipCode(String zipCode) {
            this.zipCode = zipCode;
        }
    }

    /** A setter-bound property whose wire name is its Java name; setter-bound for the same reason. */
    public static class Zipped {
        private String zipCode;

        public String getZipCode() {
            return zipCode;
        }

        public void setZipCode(String zipCode) {
            this.zipCode = zipCode;
        }
    }

    /** A record component with a non-ASCII wire name. */
    public record Unicode(@JsonProperty("名前") String name) {}

    /** Members whose positions have no stable identity: a map entry and a set member. */
    public record Keyed(Map<String, Named> byKey, Set<String> tags) {}

    /** A bean that collects every undeclared member. */
    public static class Open {
        private String name;
        private final Map<String, Object> extras = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getExtras() {
            return extras;
        }

        @JsonAnySetter
        public void put(String key, Object value) {
            extras.put(key, value);
        }
    }

    // --- Mappers ---

    /** A route mapper that ignores undeclared members. */
    private static final ObjectMapper TOLERANT = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A SNAKE_CASE route mapper that ignores undeclared members. */
    private static final ObjectMapper SNAKE_CASE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A route mapper that matches member names case-insensitively and ignores undeclared members. */
    private static final ObjectMapper FOLDS_CASE = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

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

    /** The pointer names a value the document holds (or is the root). */
    private static final boolean PRESENT = false;

    /** The pointer names a member the document omits, inside an object the document holds. */
    private static final boolean MISSING_LEAF = true;

    /** A client key built from every character that matters to a pointer or a path. */
    private static final String HOSTILE_KEY = "ZZKEY~0/~1..[0]";

    static Stream<Arguments> documentRows() {
        return Stream.of(
                // --- an omitted member bound through a setter, a creator parameter or a record component ---
                arguments(
                        "omitted record component at the root",
                        TOLERANT,
                        Named.class,
                        path(property("name", Named.class)),
                        "{}",
                        "/name",
                        MISSING_LEAF),
                arguments(
                        "omitted record component in a list element",
                        TOLERANT,
                        Bag.class,
                        path(property("items", Bag.class), index(1), property("name", Named.class)),
                        "{\"items\":[{\"name\":\"a\"},{}]}",
                        "/items/1/name",
                        MISSING_LEAF),
                arguments(
                        "omitted setter-bound member in a nested object",
                        TOLERANT,
                        Household.class,
                        path(property("owner", Household.class), property("name", Owner.class)),
                        "{\"owner\":{}}",
                        "/owner/name",
                        MISSING_LEAF),
                arguments(
                        "omitted record component with a non-ASCII wire name",
                        TOLERANT,
                        Unicode.class,
                        path(property("name", Unicode.class)),
                        "{}",
                        "/名前",
                        MISSING_LEAF),
                // --- an omitted parent or intermediate: truncate before it ---
                arguments(
                        "omitted parent of the violated member",
                        TOLERANT,
                        Household.class,
                        path(property("owner", Household.class), property("name", Owner.class)),
                        "{}",
                        "",
                        PRESENT),
                arguments(
                        "intermediate member missing",
                        TOLERANT,
                        Shipment.class,
                        path(
                                property("leg", Shipment.class),
                                property("stop", Leg.class),
                                property("name", Named.class)),
                        "{\"leg\":{}}",
                        "/leg",
                        PRESENT),
                arguments(
                        "list position missing",
                        TOLERANT,
                        Bag.class,
                        path(property("items", Bag.class), index(3), property("name", Named.class)),
                        "{\"items\":[{\"name\":\"a\"}]}",
                        "/items",
                        PRESENT),
                arguments(
                        "list position holding null instead of an object",
                        TOLERANT,
                        Bag.class,
                        path(property("items", Bag.class), index(0), property("name", Named.class)),
                        "{\"items\":[null]}",
                        "/items/0",
                        PRESENT),
                arguments(
                        "member holding null instead of an object",
                        TOLERANT,
                        Household.class,
                        path(property("owner", Household.class), property("name", Owner.class)),
                        "{\"owner\":null}",
                        "/owner",
                        PRESENT),
                // --- an omitted member whose constraint sits on a property only a field binds: the parent ---
                arguments(
                        "omitted field behind a renamed setter",
                        TOLERANT,
                        RenamedSetter.class,
                        path(property("zipCode", RenamedSetter.class)),
                        "{}",
                        "",
                        PRESENT),
                arguments(
                        "field behind a renamed setter whose setter key is sent",
                        TOLERANT,
                        RenamedSetter.class,
                        path(property("zipCode", RenamedSetter.class)),
                        "{\"zip_code\":\"\"}",
                        "",
                        PRESENT),
                arguments(
                        "omitted field behind a renamed creator parameter",
                        TOLERANT,
                        RenamedCreator.class,
                        path(property("streetName", RenamedCreator.class)),
                        "{}",
                        "",
                        PRESENT),
                arguments(
                        "omitted public field",
                        TOLERANT,
                        PublicName.class,
                        path(property("name", PublicName.class)),
                        "{}",
                        "",
                        PRESENT),
                // --- a Java-name key on a renaming route is not a declared spelling ---
                arguments(
                        "Java-name key sent for a setter-bound member on a SNAKE_CASE route",
                        SNAKE_CASE,
                        SetterZip.class,
                        path(property("zipCode", SetterZip.class)),
                        "{\"zipCode\":\"\"}",
                        "/zip_code",
                        MISSING_LEAF),
                arguments(
                        "Java-name key sent for a field-bound member on a SNAKE_CASE route",
                        SNAKE_CASE,
                        FieldZip.class,
                        path(property("zipCode", FieldZip.class)),
                        "{\"zipCode\":\"\"}",
                        "",
                        PRESENT),
                arguments(
                        "declared key beside a stray Java-name key",
                        SNAKE_CASE,
                        SetterZip.class,
                        path(property("zipCode", SetterZip.class)),
                        "{\"zip_code\":\"\",\"zipCode\":\"x\"}",
                        "/zip_code",
                        PRESENT),
                // --- a field-bound member resolves only when its declared key is present ---
                arguments(
                        "public field with its declared key present",
                        TOLERANT,
                        PublicZip.class,
                        path(property("zipCode", PublicZip.class)),
                        "{\"zip_code\":\"\"}",
                        "/zip_code",
                        PRESENT),
                arguments(
                        "public field with its declared key omitted",
                        TOLERANT,
                        PublicZip.class,
                        path(property("zipCode", PublicZip.class)),
                        "{}",
                        "",
                        PRESENT),
                arguments(
                        "public field with only its Java-name key sent",
                        TOLERANT,
                        PublicZip.class,
                        path(property("zipCode", PublicZip.class)),
                        "{\"zipCode\":\"\"}",
                        "",
                        PRESENT),
                // --- the client's spelling when exactly one declared spelling is present ---
                arguments(
                        "alias sent",
                        TOLERANT,
                        Aliased.class,
                        path(property("zipCode", Aliased.class)),
                        "{\"zip\":\"\"}",
                        "/zip",
                        PRESENT),
                arguments(
                        "case variant sent to a case-insensitive route",
                        FOLDS_CASE,
                        Zipped.class,
                        path(property("zipCode", Zipped.class)),
                        "{\"ZipCode\":\"\"}",
                        "/ZipCode",
                        PRESENT),
                arguments(
                        "non-ASCII wire name sent",
                        TOLERANT,
                        Unicode.class,
                        path(property("name", Unicode.class)),
                        "{\"名前\":\"\"}",
                        "/名前",
                        PRESENT),
                arguments(
                        "case variants beside the declared key on a case-sensitive route",
                        TOLERANT,
                        Zipped.class,
                        path(property("zipCode", Zipped.class)),
                        "{\"ZIPCODE\":\"x\",\"zipCode\":\"\",\"ZipCode\":\"y\"}",
                        "/zipCode",
                        PRESENT),
                // --- two or more declared spellings present: the parent ---
                arguments(
                        "alias and primary name both sent",
                        TOLERANT,
                        Aliased.class,
                        path(property("zipCode", Aliased.class)),
                        "{\"zip\":\"\",\"zip_code\":\"\"}",
                        "",
                        PRESENT),
                arguments(
                        "two case variants sent to a case-insensitive route",
                        FOLDS_CASE,
                        Zipped.class,
                        path(property("zipCode", Zipped.class)),
                        "{\"ZipCode\":\"\",\"zipcode\":\"x\"}",
                        "",
                        PRESENT),
                // --- maps, sets and undeclared members: no client key reaches the pointer ---
                arguments(
                        "map entry under a hostile key",
                        TOLERANT,
                        Keyed.class,
                        path(property("byKey", Keyed.class), opaque(), property("name", Named.class)),
                        "{\"byKey\":{\"" + HOSTILE_KEY + "\":{\"name\":\"\"}}}",
                        "/byKey",
                        PRESENT),
                arguments(
                        "map entry under a numeric key read as a position",
                        TOLERANT,
                        Keyed.class,
                        path(property("byKey", Keyed.class), index(0), property("name", Named.class)),
                        "{\"byKey\":{\"0\":{\"name\":\"\"}}}",
                        "/byKey",
                        PRESENT),
                arguments(
                        "set member holding a hostile key",
                        TOLERANT,
                        Keyed.class,
                        path(property("tags", Keyed.class), opaque()),
                        "{\"tags\":[\"" + HOSTILE_KEY + "\"]}",
                        "/tags",
                        PRESENT),
                arguments(
                        "undeclared member named by a hostile key",
                        TOLERANT,
                        Named.class,
                        path(property(HOSTILE_KEY, Named.class)),
                        "{\"" + HOSTILE_KEY + "\":\"\",\"name\":\"\"}",
                        "",
                        PRESENT),
                arguments(
                        "undeclared prototype-style member",
                        TOLERANT,
                        Named.class,
                        path(property("__proto__", Named.class), property("name", Named.class)),
                        "{\"__proto__\":{\"name\":\"\"},\"name\":\"\"}",
                        "",
                        PRESENT),
                arguments(
                        "member collected by an any-setter under a hostile key",
                        TOLERANT,
                        Open.class,
                        path(property("extras", Open.class), opaque()),
                        "{\"" + HOSTILE_KEY + "\":\"x\",\"name\":\"\"}",
                        "",
                        PRESENT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentRows")
    @DisplayName("pointers are checked against the document, name omitted bound members and never echo client keys")
    void documentCheckMissingLeavesAndClientKeys(
            String shape,
            ObjectMapper mapper,
            Type rootType,
            PropertyPath path,
            String document,
            String expected,
            boolean missingLeaf) {
        // Given: a route mapper, its own name projection and a request document that mapper binds
        JakartaPathPointers.Resolver session =
                JakartaPathPointers.resolver(mapper, JacksonFieldNameResolver.forMapper(mapper));
        Object parsed = Json.decodeValue(document);

        // When: the validator's structured path resolves against the document
        String pointer = session.toPointer(path, rootType, parsed);

        // Then: the pointer is exactly the expected verified position, never a client key, and either names a
        // value the document holds or an omitted member of an object the document holds
        assertAll(
                () -> assertDoesNotThrow(
                        () -> mapper.readValue(document, mapper.constructType(rootType)),
                        "the row's document must bind with the row's mapper"),
                () -> assertEquals(expected, pointer, "pointer for '" + shape + "'"),
                () -> assertFalse(pointer.contains("ZZKEY"), "a client-chosen key reached the pointer"),
                () -> {
                    if (missingLeaf) {
                        assertOmittedMemberOfExistingObject(document, pointer);
                    } else {
                        assertResolves(document, pointer);
                    }
                });
    }

    private static JsonNode tree(String document) throws Exception {
        return TOLERANT.valueToTree(TOLERANT.readValue(document, Object.class));
    }

    /** Asserts that a non-root pointer names a value present in the document. */
    private static void assertResolves(String document, String pointer) throws Exception {
        if (pointer.isEmpty()) {
            return;
        }
        assertFalse(
                tree(document).at(JsonPointer.compile(pointer)).isMissingNode(),
                "pointer '" + pointer + "' must resolve in " + document);
    }

    /** Asserts that the pointer's parent is an object in the document and its last member is absent. */
    private static void assertOmittedMemberOfExistingObject(String document, String pointer) throws Exception {
        JsonPointer compiled = JsonPointer.compile(pointer);
        JsonNode root = tree(document);
        JsonNode parent = root.at(compiled.head());
        assertAll(
                () -> assertTrue(parent.isObject(), "the parent of '" + pointer + "' must be an object in " + document),
                () -> assertTrue(
                        root.at(compiled).isMissingNode(),
                        "'" + pointer + "' must name a member the document omits in " + document));
    }
}
