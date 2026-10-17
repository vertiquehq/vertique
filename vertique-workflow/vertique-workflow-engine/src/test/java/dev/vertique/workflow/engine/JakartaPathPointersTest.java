// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link JakartaPathPointers}: the conversion of a Jakarta property-path string into
 * an RFC 6901 pointer over the wire names of the process mapper.
 *
 * <p>The input grammar is the one the violation detail mapper produces from a property path:
 * nodes joined by {@code .}, each node a property name with an optional {@code <...>} marker and an
 * optional {@code [index]} or {@code [key]} suffix. A map entry ends the pointer at the map
 * property, so the caller's key never reaches the output. Container-element nodes are dropped.
 *
 * <p>No database, Vert.x context or validator is required.
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

    /** The type tree most rows convert against. */
    record Root(
            String name,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("a/b") String slash,
            @JsonProperty("t~x") String tilde,
            @JsonProperty("~/") String both,
            Item owner,
            LegacyBean legacy,
            List<Item> items,
            List<String> tags,
            List<List<String>> lists,
            Map<String, Item> byKey,
            @JsonProperty("lookup_table") Map<String, Item> table) {}

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
                // --- container-element nodes are dropped ---
                arguments("list element", Root.class, "tags[0].<list element>", "/tags/0"),
                arguments("map value", Root.class, "byKey[k1].<map value>", "/byKey"),
                arguments("map key", Root.class, "byKey<K>[k1].<map key>", "/byKey"),
                arguments("iterable element of a set", SetRoot.class, "tags[].<iterable element>", "/tags"),
                arguments("iterable element marker", Root.class, "tags[].<iterable element>", "/tags"),
                arguments(
                        "nested list elements keep only the first index",
                        Root.class,
                        "lists[0].<list element>[1].<list element>",
                        "/lists/0"),
                // --- unknown segments keep the Java name ---
                arguments("unknown root segment", Root.class, "unknown.deep", "/unknown/deep"),
                arguments("unknown segment under a known element", Root.class, "items[0].ghost", "/items/0/ghost"),
                arguments(
                        "unknown segment under a known bean", Root.class, "owner.ghost.deeper", "/owner/ghost/deeper"),
                // --- an integer-looking bracket is an index only after a resolved non-map container ---
                arguments("unknown indexed segment", Root.class, "ghost[3].x", "/ghost"),
                arguments("unresolved property with a numeric key", Root.class, "ghost[123]", "/ghost"),
                arguments(
                        "unresolved property with a numeric key then property",
                        Root.class,
                        "ghost[1].secret",
                        "/ghost"),
                arguments("Object-typed property with an index", Shapes.class, "blob[7].x", "/blob"),
                arguments("non-container property with an index", Shapes.class, "plain[2]", "/plain"),
                arguments(
                        "ignored collection property with an index",
                        Shapes.class,
                        "hiding.hidden[0].name",
                        "/hiding/hidden"),
                arguments(
                        "accessorless collection property with an index",
                        Shapes.class,
                        "hiding.internal[2]",
                        "/hiding/internal"),
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
        String pointer = JakartaPathPointers.toPointer(path, rootType);

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
        String pointer = JakartaPathPointers.toPointer(path, Root.class);

        // Then: the pointer ends at the map property and holds no part of the key
        assertEquals("/byKey", pointer);
    }
}
