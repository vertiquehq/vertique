// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link JakartaPathPointers}: the conversion of a Jakarta property-path string into
 * an RFC 6901 pointer over the wire names of the supplied mapper.
 *
 * <p>The input grammar is the one the violation detail mapper produces from a property path:
 * nodes joined by {@code .}, each node a property name with an optional {@code <...>} marker and an
 * optional {@code [index]} or {@code [key]} suffix. A map entry ends the pointer at the map
 * property, so the caller's key never reaches the output. A container-element node contributes its
 * index and no name. A segment that cannot be resolved ends the pointer at the deepest verified
 * ancestor instead of keeping its Java name.
 *
 * <p>The same rules apply to a structured {@link PropertyPath} resolved through a mapper-only session
 * without a document, and the same grammar renders a violation path for a log line with every key,
 * control character and line separator neutralized.
 *
 * <p>No validator or Vert.x context is required.
 */
class JakartaPathPointersTest {

    /** A bean element with one renamed property. */
    record Item(String name, @JsonProperty("item_note") String note) {}

    /** A non-record bean whose field carries the wire name. */
    public static class LegacyBean {

        @JsonProperty("first_name")
        private String firstName;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }
    }

    /** Names the property {@code empty} with the empty string and leaves every other name unchanged. */
    public static class EmptyNaming extends PropertyNamingStrategies.NamingBase {
        @Override
        public String translate(String propertyName) {
            return "empty".equals(propertyName) ? "" : propertyName;
        }
    }

    /**
     * A bean with a member whose wire name is the empty string. An empty {@code @JsonProperty} value
     * means "use the default name" to Jackson, so a naming strategy is the way to declare one.
     */
    @JsonNaming(EmptyNaming.class)
    record Blank(String empty, String other) {}

    /** The type tree most rows convert against. */
    record Root(
            String name,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("a/b") String slash,
            @JsonProperty("t~x") String tilde,
            @JsonProperty("~/") String both,
            @JsonProperty("") String empty,
            Item owner,
            LegacyBean legacy,
            List<Item> items,
            List<String> tags,
            List<List<String>> lists,
            Map<String, Item> byKey,
            @JsonProperty("lookup_table") Map<String, Item> table,
            Optional<Item> maybe,
            AtomicReference<Item> ref,
            Blank blank) {}

    /** An unrelated type that declares a property of the same Java name as {@link Item} under another wire name. */
    public static class Decoy {
        @JsonProperty("decoy_note")
        public String note;
    }

    /** A segment kind the resolver does not know; it must be read like an opaque marker. */
    record UnknownSegment() implements PropertyPath.Segment {}

    /** A root whose {@code tags} is a set, so its element nodes carry empty brackets. */
    record SetRoot(Set<String> tags) {}

    /**
     * A bean whose collection properties the process mapper cannot resolve: one is ignored, the other
     * has neither accessor nor mutator.
     */
    public static class Hiding {

        @JsonIgnore
        public List<Item> hidden;

        @SuppressWarnings("unused")
        private List<Item> internal;

        public String visible;
    }

    /** A root whose properties are not (all) known non-map containers, plus typed array and nested list. */
    record Shapes(Object blob, String[] codes, String plain, List<List<String>> grid, Hiding hiding) {}

    static Stream<Arguments> rows() {
        return Stream.of(
                // --- root property and wire names ---
                arguments("root property", Root.class, "name", "/name"),
                arguments("renamed root property", Root.class, "displayName", "/display_name"),
                arguments("slash", Root.class, "slash", "/a~1b"),
                arguments("tilde", Root.class, "tilde", "/t~0x"),
                arguments("tilde and slash in escaping order", Root.class, "both", "/~0~1"),
                // --- nesting ---
                arguments("nested bean", Root.class, "owner.name", "/owner/name"),
                arguments("nested bean renamed property", Root.class, "owner.note", "/owner/item_note"),
                arguments("nested non-record bean renamed field", Root.class, "legacy.firstName", "/legacy/first_name"),
                arguments("list index", Root.class, "items[0]", "/items/0"),
                arguments("list index then property", Root.class, "items[0].name", "/items/0/name"),
                arguments("list index then renamed property", Root.class, "items[12].note", "/items/12/item_note"),
                // --- map entries end the pointer at the map property ---
                arguments("map entry", Root.class, "byKey[k1].name", "/byKey"),
                arguments("map entry on a renamed map property", Root.class, "table[k1].name", "/lookup_table"),
                arguments("map entry with the sentinel key", Root.class, "byKey[ZZKEY].name", "/byKey"),
                arguments("map entry key holding a dot", Root.class, "byKey[ZZ.KEY].name", "/byKey"),
                arguments("map entry key holding a closing bracket", Root.class, "byKey[ZZ]KEY].name", "/byKey"),
                arguments("map entry with empty brackets", Root.class, "byKey[].name", "/byKey"),
                // --- container-element nodes contribute their index and no name ---
                arguments("list element", Root.class, "tags[0].<list element>", "/tags/0"),
                arguments("map value", Root.class, "byKey[k1].<map value>", "/byKey"),
                arguments("map key", Root.class, "byKey<K>[k1].<map key>", "/byKey"),
                arguments("iterable element of a set", SetRoot.class, "tags[].<iterable element>", "/tags"),
                arguments("iterable element marker", Root.class, "tags[].<iterable element>", "/tags"),
                arguments(
                        "nested list elements keep the index of every level",
                        Root.class,
                        "lists[0].<list element>[1].<list element>",
                        "/lists/0/1"),
                // --- an unresolved segment truncates the pointer at the deepest verified ancestor ---
                arguments("unknown root segment", Root.class, "unknown.deep", ""),
                arguments("unknown segment under a known element", Root.class, "items[0].ghost", "/items/0"),
                arguments("unknown segment under a known bean", Root.class, "owner.ghost.deeper", "/owner"),
                // --- an integer-looking bracket is an index only after a resolved non-map container ---
                arguments("unknown indexed segment", Root.class, "ghost[3].x", ""),
                arguments("unresolved property with a numeric key", Root.class, "ghost[123]", ""),
                arguments("unresolved property with a numeric key then property", Root.class, "ghost[1].secret", ""),
                arguments("unresolved property with a negative index", Root.class, "ghost[-1]", ""),
                arguments(
                        "index that overflows an int ends the pointer at the list",
                        Root.class,
                        "items[99999999999].name",
                        "/items"),
                arguments("Object-typed property with an index", Shapes.class, "blob[7].x", "/blob"),
                arguments("non-container property with an index", Shapes.class, "plain[2]", "/plain"),
                arguments(
                        "ignored collection property with an index", Shapes.class, "hiding.hidden[0].name", "/hiding"),
                arguments(
                        "accessorless collection property with an index",
                        Shapes.class,
                        "hiding.internal[2]",
                        "/hiding"),
                arguments(
                        "element of a list that is not a container takes no second index",
                        Root.class,
                        "items[0][1]",
                        "/items/0"),
                arguments("array index", Shapes.class, "codes[2]", "/codes/2"),
                arguments("nested list indexes", Shapes.class, "grid[0][1]", "/grid/0/1"),
                // --- no path ---
                arguments("empty path", Root.class, "", ""));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("rows")
    @DisplayName("Jakarta property paths convert to wire-name pointers")
    void convertsPropertyPathsToWireNamePointers(String row, Class<?> rootType, String path, String expected) {
        // Given: a type tree and a Jakarta property path (the row)

        // When: the path is converted against the root type
        String pointer = JakartaPathPointers.toPointer(path, rootType, VertiqueJson.mapper());

        // Then: the pointer is exactly the expected wire-name pointer, and no sentinel key leaked
        assertEquals(expected, pointer, "pointer for row '" + row + "'");
        assertFalse(pointer.contains("ZZ"), "a map key must never appear in the pointer");
    }

    @ParameterizedTest(name = "key [{0}]")
    @ValueSource(strings = {"ZZKEY", "ZZ.KEY", "ZZ]KEY", "ZZ[KEY", "ZZ KEY", "ZZ~/KEY", "ZZ<KEY>", "ZZ.KEY].x.y"})
    @DisplayName("a map key of any shape never appears in the pointer")
    void mapKeysNeverAppearInThePointer(String key) {
        // Given: a violation path inside a map property under a caller-chosen key
        String path = "byKey[" + key + "].name";

        // When: the path is converted
        String pointer = JakartaPathPointers.toPointer(path, Root.class, VertiqueJson.mapper());

        // Then: the pointer ends at the map property and holds no part of the key
        assertEquals("/byKey", pointer);
    }

    // --- Structured paths, mapper-only resolution ---

    private static final Type ITEM_LIST = new TypeReference<List<Item>>() {}.getType();

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static Property property(String name) {
        return new Property(name, null);
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

    static Stream<Arguments> structuredRows() {
        return Stream.of(
                // --- renamed and plain properties ---
                arguments("root property", Root.class, path(property("name", Root.class)), "/name"),
                arguments(
                        "renamed root property",
                        Root.class,
                        path(property("displayName", Root.class)),
                        "/display_name"),
                arguments(
                        "renamed property of a nested bean",
                        Root.class,
                        path(property("owner", Root.class), property("note", Item.class)),
                        "/owner/item_note"),
                arguments(
                        "renamed setter-bound property of a nested bean",
                        Root.class,
                        path(property("legacy"), property("firstName")),
                        "/legacy/first_name"),
                // --- list and array indexes ---
                arguments(
                        "renamed property of a list element",
                        Root.class,
                        path(property("items", Root.class), index(12), property("note", Item.class)),
                        "/items/12/item_note"),
                arguments("nested list indexes", Root.class, path(property("lists"), index(0), index(1)), "/lists/0/1"),
                arguments("array index", Shapes.class, path(property("codes"), index(2)), "/codes/2"),
                arguments("root list body element", ITEM_LIST, path(index(2), property("name")), "/2/name"),
                arguments("root array body element", Item[].class, path(index(2), property("name")), "/2/name"),
                // --- class-level constraints ---
                arguments("class-level constraint on the root", Root.class, PropertyPath.root(), ""),
                arguments(
                        "class-level constraint on a list element",
                        Root.class,
                        path(property("items"), index(0)),
                        "/items/0"),
                // --- map entries and set members stop at the container ---
                arguments("map entry", Root.class, path(property("byKey"), opaque(), property("name")), "/byKey"),
                arguments(
                        "map entry on a renamed map",
                        Root.class,
                        path(property("table"), opaque(), property("name")),
                        "/lookup_table"),
                arguments(
                        "numeric position on a map stops at the map",
                        Root.class,
                        path(property("byKey"), index(0), property("name")),
                        "/byKey"),
                arguments("set member", SetRoot.class, path(property("tags"), opaque()), "/tags"),
                // --- escaping ---
                arguments("slash", Root.class, path(property("slash")), "/a~1b"),
                arguments("tilde", Root.class, path(property("tilde")), "/t~0x"),
                arguments("tilde and slash in escaping order", Root.class, path(property("both")), "/~0~1"),
                arguments("empty wire name", Blank.class, path(property("empty")), "/"),
                arguments(
                        "empty wire name under a nested bean",
                        Root.class,
                        path(property("blank"), property("empty")),
                        "/blank/"),
                arguments(
                        "an empty @JsonProperty value keeps the default name",
                        Root.class,
                        path(property("empty")),
                        "/empty"),
                // --- unresolved segments truncate at the deepest verified ancestor ---
                arguments("unknown root segment", Root.class, path(property("ghost")), ""),
                arguments(
                        "unknown segment under a known bean",
                        Root.class,
                        path(property("owner"), property("ghost")),
                        "/owner"),
                arguments(
                        "unknown segment under a list element",
                        Root.class,
                        path(property("items"), index(0), property("ghost"), property("deeper")),
                        "/items/0"),
                arguments(
                        "property under an Object-typed property",
                        Shapes.class,
                        path(property("blob"), property("x")),
                        "/blob"),
                arguments("property under a scalar", Root.class, path(property("name"), property("x")), "/name"),
                arguments("index on a scalar", Shapes.class, path(property("plain"), index(2)), "/plain"),
                arguments("index on a bean", Root.class, path(property("owner"), index(0)), "/owner"),
                arguments(
                        "element of a list that is not a container takes no second index",
                        Root.class,
                        path(property("items"), index(0), index(1)),
                        "/items/0"),
                arguments(
                        "ignored collection property",
                        Shapes.class,
                        path(property("hiding"), property("hidden"), index(0)),
                        "/hiding"),
                arguments(
                        "accessorless collection property",
                        Shapes.class,
                        path(property("hiding"), property("internal"), index(2)),
                        "/hiding"),
                arguments("unknown root type", null, path(property("name")), ""),
                arguments("index at the root of a bean", Root.class, path(index(0), property("name")), ""),
                arguments("opaque marker at the root", Root.class, path(opaque(), property("name")), ""),
                arguments(
                        "an unrecognized segment kind stops like an opaque marker",
                        Root.class,
                        path(property("items"), new UnknownSegment(), property("name")),
                        "/items"),
                arguments(
                        "an owner type unrelated to the declared type is ignored",
                        Root.class,
                        path(property("owner", Root.class), property("note", Decoy.class)),
                        "/owner/item_note"),
                // --- Optional and AtomicReference are transparent ---
                arguments(
                        "property inside an Optional",
                        Root.class,
                        path(property("maybe"), property("name")),
                        "/maybe/name"),
                arguments(
                        "renamed property inside an Optional",
                        Root.class,
                        path(property("maybe"), property("note")),
                        "/maybe/item_note"),
                arguments(
                        "renamed property inside an AtomicReference",
                        Root.class,
                        path(property("ref"), property("note")),
                        "/ref/item_note"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredRows")
    @DisplayName("structured property paths convert to wire-name pointers")
    void structuredPathsConvertToWireNamePointers(String row, Type rootType, PropertyPath path, String expected) {
        // Given: a type tree, a structured path relative to its root (the row) and a mapper-only session
        JakartaPathPointers.Resolver session = JakartaPathPointers.resolver(VertiqueJson.mapper(), null);

        // When: the path is resolved without a document
        String pointer = session.toPointer(path, rootType, null);

        // Then: the pointer is exactly the expected wire-name pointer
        assertEquals(expected, pointer, "pointer for row '" + row + "'");
    }

    @Test
    @DisplayName("one session resolves every structured row exactly like a fresh session")
    void oneSessionResolvesEveryRowLikeAFreshSession() {
        // Given: a single mapper-only session shared by every row, so per-class lookups are reused
        JakartaPathPointers.Resolver session = JakartaPathPointers.resolver(VertiqueJson.mapper(), null);
        List<Arguments> rows = structuredRows().toList();

        // When: every row is resolved through it, twice
        List<Executable> checks = new ArrayList<>();
        for (int pass = 1; pass <= 2; pass++) {
            for (Arguments row : rows) {
                Object[] values = row.get();
                String pointer = session.toPointer((PropertyPath) values[2], (Type) values[1], null);
                String label = "pass " + pass + ", row '" + values[0] + "'";

                // Then: the shared session gives each row's expected pointer
                checks.add(() -> assertEquals(values[3], pointer, label));
            }
        }
        assertAll(checks);
    }

    // --- Log rendering ---

    private static final String LONG_PATH = "abc.".repeat(499) + "abcd";

    static Stream<Arguments> loggableRows() {
        return Stream.of(
                // --- a string path stops at its first opaque segment ---
                arguments("map key", "byKey[ZZKEY].name", "byKey[]"),
                arguments("map key followed by a closing bracket and a dot", "byKey[a].ZZKEY].name", "byKey[]"),
                arguments("map key holding a closing bracket", "byKey[a]ZZKEY].name", "byKey[]"),
                arguments("text after a key", "x[a].ZZKEY", "x[]"),
                arguments("text after a key and an index", "x[a][0].ZZKEY", "x[]"),
                arguments("name after a key", "x[a].x", "x[]"),
                arguments("map key holding a line feed", "byKey[ZZ\nKEY].name", "byKey[]"),
                arguments("map key marker node", "byKey<K>[k1].<map key>", "byKey[]"),
                arguments("set member marker node", "tags[].<iterable element>", "tags[]"),
                // --- indexes and container-element markers render through the grammar ---
                arguments("indexed property", "items[0].name", "items[0].name"),
                arguments(
                        "container-element markers contribute only their index",
                        "lists[0].<list element>[1].<list element>",
                        "lists[0][1]"),
                // --- control and line-separator characters are removed ---
                arguments("escape character", "items[0].na\u001Bme", "items[0].name"),
                arguments("next-line character", "items[0].na\u0085me", "items[0].name"),
                arguments("line separator", "items[0].na\u2028me", "items[0].name"),
                arguments("paragraph separator", "items[0].na\u2029me", "items[0].name"),
                arguments("carriage return and line feed", "items[0].na\r\nme", "items[0].name"),
                // --- an unclosable bracket ends the rendering ---
                arguments("unclosable bracket", "byKey[unclosed", "byKey"),
                // --- length ---
                arguments("long path is cut to 512 characters", LONG_PATH, LONG_PATH.substring(0, 512)),
                arguments("empty path", "", ""),
                // --- a structured path holds no key text and renders past opaque markers ---
                arguments(
                        "structured path past an opaque marker",
                        path(property("byKey"), opaque(), property("name")),
                        "byKey[].name"),
                arguments("structured path with indexes", path(property("lists"), index(0), index(1)), "lists[0][1]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loggableRows")
    @DisplayName("log paths are neutralized through the property-path grammar")
    void loggablePathNeutralizesKeysAndControlCharacters(String row, Object input, String expected) {
        // Given: a violation path as a string or as a structured path (the row)

        // When: it is rendered for a log line
        String rendered = input instanceof PropertyPath structured
                ? JakartaPathPointers.loggablePath(structured)
                : JakartaPathPointers.loggablePath((String) input);

        // Then: the rendering is exact, holds no key text, no control or line-separator character, and is bounded
        assertAll(
                () -> assertEquals(expected, rendered, "rendering for row '" + row + "'"),
                () -> assertFalse(rendered.contains("ZZ"), "key text reached the log path"),
                () -> assertTrue(
                        rendered.chars().noneMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029),
                        "a control or line-separator character reached the log path"),
                () -> assertTrue(rendered.length() <= 512, "log path longer than 512 characters"));
    }
}
