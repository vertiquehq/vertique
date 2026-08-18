// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TypeClassifier#elementType}'s element-resolution rule: the element of a collection is
 * {@code E} in its {@code Collection<E>} <em>supertype binding</em>, never "type argument 0" of the
 * declared type.
 *
 * <p>The three fixtures below are the counterexamples that separate the two rules. A
 * {@code Pair<A, B> extends ArrayList<A>} makes argument 0 accidentally right, a
 * {@code Fixed<T> extends ArrayList<String>} makes it wrong even at arity one, and a
 * {@code Weird<A, B> extends ArrayList<B>} makes it outright wrong. Only the supertype binding
 * answers all three with the element type Jackson actually binds.
 */
class TypeClassifierTest {

    @Nested
    @DisplayName("elementType")
    class ElementType {

        @Test
        @DisplayName("a multi-argument collection subtype resolves its element through the supertype binding")
        void pairElementResolvesThroughTheSupertypeBinding() {
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("pair")),
                    "Pair<A, B> extends ArrayList<A>, so a Pair<Dto, Other> binds Dto elements");
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("boundedPair")),
                    "a wildcard element resolves to its bound like any other element type");
        }

        @Test
        @DisplayName("a collection subtype that fixes its element ignores the declared type argument")
        void fixedElementIsTheSupertypeArgumentNotTheDeclaredOne() {
            assertEquals(
                    String.class,
                    TypeClassifier.elementType(declaredTypeOf("fixed")),
                    "Fixed<T> extends ArrayList<String>, so a Fixed<Dto> binds String elements — the "
                            + "declared argument is not the element type, whatever its arity");
        }

        @Test
        @DisplayName("a collection subtype whose element is its second argument resolves to that argument")
        void weirdElementResolvesToTheSecondArgument() {
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("weird")),
                    "Weird<A, B> extends ArrayList<B>, so a Weird<Other, Dto> binds Dto elements");
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("deep")),
                    "the binding is followed through every hop, so Deep<Dto> extends Weird<String, Dto> "
                            + "still binds Dto elements");
        }

        @Test
        @DisplayName("ordinary collection and array shapes are unchanged")
        void ordinaryCollectionShapesAreUnchanged() {
            assertEquals(Dto.class, TypeClassifier.elementType(declaredTypeOf("list")), "List<Dto> binds Dto");
            assertEquals(
                    String.class, TypeClassifier.elementType(declaredTypeOf("strings")), "Set<String> binds String");
            assertNull(
                    TypeClassifier.elementType(declaredTypeOf("raw")),
                    "a raw collection carries no binding, so it has no element schema");
            assertNull(
                    TypeClassifier.elementType(declaredTypeOf("nested")),
                    "a container element carries no property set, so List<List<Dto>> has no element schema");
            assertEquals(Dto.class, TypeClassifier.elementType(declaredTypeOf("array")), "Dto[] binds Dto");
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("optionalElements")),
                    "an Optional element is transparent, so List<Optional<Dto>> binds Dto");
        }
    }

    // --- Harness ---

    /**
     * Reads a fixture field's full generic type, so a shape such as {@code Weird<Other, Dto>} can be
     * handed to the classifier without a type-token helper.
     *
     * @param fieldName the fixture field's name
     * @return the field's generic type
     */
    private static Type declaredTypeOf(String fieldName) {
        try {
            return Shapes.class.getDeclaredField(fieldName).getGenericType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("fixture field Shapes#" + fieldName + " is missing", e);
        }
    }

    // --- Fixtures ---

    /** The element type each collection shape must resolve to. */
    static class Dto {
        String value;
    }

    /** A second DTO, so a wrong argument index picks a distinguishable class. */
    static class Other {
        String value;
    }

    /** Two type arguments, element bound to the first — argument 0 is accidentally right here. */
    static class Pair<A, B> extends ArrayList<A> {
        private static final long serialVersionUID = 1L;
    }

    /** One type argument, element fixed to {@code String} — argument 0 is wrong at arity one. */
    static class Fixed<T> extends ArrayList<String> {
        private static final long serialVersionUID = 1L;
    }

    /** Two type arguments, element bound to the second — argument 0 is outright wrong. */
    static class Weird<A, B> extends ArrayList<B> {
        private static final long serialVersionUID = 1L;
    }

    /** A second hop, so the walk must follow the binding through an intermediate subtype. */
    static class Deep<T> extends Weird<String, T> {
        private static final long serialVersionUID = 1L;
    }

    /** Every declared shape the element rule is pinned against. */
    @SuppressWarnings("rawtypes")
    static class Shapes {
        Pair<Dto, Other> pair;
        Pair<? extends Dto, Other> boundedPair;
        Fixed<Dto> fixed;
        Weird<Other, Dto> weird;
        Deep<Dto> deep;
        List<Dto> list;
        Set<String> strings;
        List raw;
        List<List<Dto>> nested;
        Dto[] array;
        List<Optional<Dto>> optionalElements;
    }
}
