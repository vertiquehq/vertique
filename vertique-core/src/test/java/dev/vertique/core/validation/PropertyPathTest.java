// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import dev.vertique.core.validation.PropertyPath.Segment;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link PropertyPath}: the Java-path rendering of its segments, the validation each
 * segment kind applies to its own components, and the immutability of the segment list.
 *
 * <p>The rendering rows pin exact strings: a property is its name, preceded by a dot unless it is the
 * first rendered token; an index is a bracketed number; an opaque marker or an unrecognized segment is
 * an empty bracket pair; the root is the empty string.
 *
 * <p>No validator, mapper or Vert.x context is required.
 */
class PropertyPathTest {

    /** A segment kind the framework does not know, as a future kind or a foreign implementation would be. */
    record UnknownSegment() implements Segment {}

    private static PropertyPath path(Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static Property property(String name) {
        return new Property(name, null);
    }

    static Stream<Arguments> renderingRows() {
        return Stream.of(
                arguments("root", path(), ""),
                arguments(
                        "property, index, property",
                        path(property("items"), new Index(0), property("zipCode")),
                        "items[0].zipCode"),
                arguments(
                        "property, opaque, property",
                        path(property("byKey"), new Opaque(), property("name")),
                        "byKey[].name"),
                arguments("nested lists", path(property("lists"), new Index(0), new Index(1)), "lists[0][1]"),
                arguments("leading index", path(new Index(2), property("name")), "[2].name"),
                arguments("trailing opaque", path(property("tags"), new Opaque()), "tags[]"),
                arguments("single property", path(property("name")), "name"),
                arguments(
                        "nested properties",
                        path(property("owner"), property("address"), property("city")),
                        "owner.address.city"),
                arguments("leading opaque", path(new Opaque(), property("name")), "[].name"),
                arguments(
                        "owner type does not change the rendering",
                        path(new Property("items", String.class), new Index(3), new Property("name", Integer.class)),
                        "items[3].name"));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("renderingRows")
    @DisplayName("renders the Java path of valid segments")
    void rendersJavaPathAndRejectsInvalidSegments(String label, PropertyPath path, String expected) {
        // Given a path built from the segments of the row
        // When it is rendered
        String rendered = path.toJavaPath();

        // Then the rendering is the exact Java path
        assertEquals(expected, rendered);
    }

    @Test
    @DisplayName("a segment kind the framework does not know renders as an empty bracket pair")
    void rendersAnUnrecognizedSegmentAsAnEmptyBracketPair() {
        // Given a path holding a test-local segment implementation between two properties
        PropertyPath path = path(property("a"), new UnknownSegment(), property("b"));

        // When it is rendered
        String rendered = path.toJavaPath();

        // Then the unrecognized segment renders like an opaque marker
        assertEquals("a[].b", rendered);
        assertEquals("[]", path(new UnknownSegment()).toJavaPath());
    }

    @Test
    @DisplayName("root has no segments and is the only path that reports root")
    void rootHasNoSegments() {
        // Given the root path
        PropertyPath root = PropertyPath.root();

        // When it is inspected
        // Then it has no segments, reports root, and equals an explicitly empty path
        assertTrue(root.isRoot());
        assertTrue(root.segments().isEmpty());
        assertEquals("", root.toJavaPath());
        assertEquals(new PropertyPath(List.of()), root);
        assertFalse(path(property("name")).isRoot());
        assertFalse(path(new Opaque()).isRoot());
    }

    @Test
    @DisplayName("an index below zero is rejected")
    void rejectsANegativeIndex() {
        // Given a negative position
        // When an index is constructed for it
        // Then construction fails; zero is the smallest valid position
        assertThrows(IllegalArgumentException.class, () -> new Index(-1));
        assertThrows(IllegalArgumentException.class, () -> new Index(Integer.MIN_VALUE));
        assertEquals(0, new Index(0).index());
    }

    @Test
    @DisplayName("a property needs a non-empty name")
    void rejectsAnEmptyOrNullPropertyName() {
        // Given an empty name and a null name
        // When a property is constructed for each
        // Then the empty name fails with an invalid-argument error
        assertThrows(IllegalArgumentException.class, () -> new Property("", null));

        // And a null name fails too, as an invalid argument or a null-pointer error
        RuntimeException nullName = assertThrows(RuntimeException.class, () -> new Property(null, null));
        assertTrue(
                nullName instanceof IllegalArgumentException || nullName instanceof NullPointerException,
                "null name must fail with IllegalArgumentException or NullPointerException but was " + nullName);
    }

    @Test
    @DisplayName("a property keeps its name and the optional owner type")
    void propertyKeepsNameAndOwnerType() {
        // Given a property with an owner type and one without
        Property owned = new Property("zipCode", String.class);
        Property unowned = new Property("zipCode", null);

        // When the components are read
        // Then they are returned as supplied
        assertEquals("zipCode", owned.name());
        assertSame(String.class, owned.ownerType());
        assertEquals("zipCode", unowned.name());
        assertNull(unowned.ownerType());
    }

    @Test
    @DisplayName("a segment list holding null is rejected")
    void rejectsANullSegmentElement() {
        // Given a mutable segment list holding a null element
        List<Segment> segments = new ArrayList<>();
        segments.add(property("a"));
        segments.add(null);

        // When a path is constructed from it
        // Then construction fails
        assertThrows(NullPointerException.class, () -> new PropertyPath(segments));
    }

    @Test
    @DisplayName("a path copies its segments and exposes them as an unmodifiable list")
    void copiesSegmentsAndExposesAnUnmodifiableList() {
        // Given a path built from a mutable list
        List<Segment> source = new ArrayList<>();
        source.add(property("a"));
        PropertyPath path = new PropertyPath(source);

        // When the source list changes afterwards
        source.add(new Index(0));

        // Then the path is unaffected
        assertEquals(1, path.segments().size());
        assertEquals("a", path.toJavaPath());

        // And the exposed list cannot be changed
        assertThrows(UnsupportedOperationException.class, () -> path.segments().add(new Opaque()));
        assertThrows(UnsupportedOperationException.class, () -> path.segments().remove(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> PropertyPath.root().segments().add(new Opaque()));
    }
}
