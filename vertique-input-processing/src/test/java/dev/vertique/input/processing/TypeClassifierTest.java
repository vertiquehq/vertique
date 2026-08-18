// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.ParameterizedType;
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
        @DisplayName("a binding nested inside a supertype's type argument is substituted transitively")
        void nestedParameterizedBindingSubstitutesTransitively() {
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("nestedBinding")),
                    "Opt<T> extends ArrayList<Optional<T>>, so an Opt<Dto> binds Optional<Dto> elements — "
                            + "the substitution must recurse into the supertype's nested type argument, and "
                            + "the Optional layer then normalizes away to Dto");
        }

        @Test
        @DisplayName("a binding nested inside a wildcard bound is substituted too")
        void wildcardNestedBindingSubstitutesIntoItsBounds() {
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declaredTypeOf("wildcardNestedBinding")),
                    "WildOpt<T> extends ArrayList<Optional<? extends T>>, so a WildOpt<Dto> binds "
                            + "Optional<? extends Dto> elements — the substitution must recurse into the "
                            + "wildcard's bound as well as into nested type arguments, or T is left "
                            + "unbound and normalizes to its Object bound instead of Dto");
        }

        @Test
        @DisplayName("a lower-bounded wildcard still resolves through its Object upper bound")
        void lowerBoundedWildcardStillResolvesToObject() {
            assertNull(
                    TypeClassifier.elementType(declaredTypeOf("wildcardSuperBinding")),
                    "a ? super T element has no upper bound beyond Object, so it resolves to Object and "
                            + "carries no element schema — substituting into the lower bound must not "
                            + "change that documented outcome");
        }

        @Test
        @DisplayName("a non-generic fixed subtype resolves its element from a plain Class-typed use site")
        void nonGenericFixedSubtypeResolvesItsElement() {
            Type declared = declaredTypeOf("dtos");
            assertInstanceOf(
                    Class.class,
                    declared,
                    "Dtos declares no type parameters of its own, so reflection reports the field's "
                            + "generic type as the plain Class Dtos.class rather than a ParameterizedType — "
                            + "confirmed by javap/jshell before writing this assertion");
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declared),
                    "Dtos extends ArrayList<Dto>, so the element must resolve from the class's own "
                            + "generic superclass even though the use site carries no local type argument "
                            + "to read at all");
        }

        @Test
        @DisplayName("an owner-bound inner class resolves its element through the parameterized owner type")
        void ownerBoundInnerClassResolvesItsElement() {
            Type declared = declaredTypeOf("ownerBound");
            // Observed via reflection before writing the assertions below: the field's generic type is
            // a ParameterizedType for Outer<Dto>.Inner whose own getActualTypeArguments() is empty
            // (Inner declares no type parameters of its own) and whose getOwnerType() is the
            // ParameterizedType Outer<Dto>. Inner.class.getGenericSuperclass() is ArrayList<T>, where T
            // is Outer's own TypeVariable — reflection caches TypeVariable instances per declaration and
            // name, so that T is reference-equal to Outer.class.getTypeParameters()[0]. The element
            // binding therefore is resolvable, but only by reading it off the owner type, never off
            // Inner's own (empty) actual type arguments.
            assertInstanceOf(
                    ParameterizedType.class,
                    declared,
                    "Outer<Dto>.Inner is a ParameterizedType use site even though Inner has no type "
                            + "parameters of its own");
            ParameterizedType parameterized = (ParameterizedType) declared;
            assertEquals(
                    0,
                    parameterized.getActualTypeArguments().length,
                    "Inner declares no type parameters of its own, so its use site carries no local type "
                            + "argument — the T binding cannot come from getActualTypeArguments()");
            assertInstanceOf(
                    ParameterizedType.class,
                    parameterized.getOwnerType(),
                    "the T binding for Inner's ArrayList<T> supertype is only observable through the "
                            + "owner type Outer<Dto>");
            assertEquals(
                    Dto.class,
                    TypeClassifier.elementType(declared),
                    "Outer<T> { class Inner extends ArrayList<T> {} } used as Outer<Dto>.Inner binds Dto "
                            + "elements — the binding for T arrives through the parameterized owner, since "
                            + "Inner carries no local type argument of its own");
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

    /**
     * The element variable sits <em>inside</em> the supertype's type argument, so resolving it needs
     * substitution to recurse into nested arguments rather than only rewriting top-level variables.
     */
    static class Opt<T> extends ArrayList<Optional<T>> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The element variable sits inside a <em>wildcard bound</em> nested in the supertype's type
     * argument, so resolving it needs substitution to recurse through the wildcard as well — a
     * wildcard left unsubstituted keeps {@code T}, which normalizes to its {@code Object} bound.
     */
    static class WildOpt<T> extends ArrayList<Optional<? extends T>> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The lower-bounded sibling of {@link WildOpt}. Its element has no upper bound beyond
     * {@code Object}, so it resolves to {@code Object} whether or not the bound is substituted.
     */
    static class WildSuper<T> extends ArrayList<Optional<? super T>> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * A concrete, non-generic subtype whose element is fixed by its own declaration. Unlike
     * {@link Fixed}, this type has no type parameters at all, so a field declared with this type is a
     * plain {@code Class} use site — not a {@code ParameterizedType} — and carries no local type
     * argument whatsoever.
     */
    static final class Dtos extends ArrayList<Dto> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * An outer/inner pair where the inner class's {@code Collection<E>} binding comes from the
     * enclosing instance's type argument rather than from any type argument local to {@code Inner}
     * itself — {@code Inner} declares no type parameters of its own.
     */
    static class Outer<T> {
        class Inner extends ArrayList<T> {
            private static final long serialVersionUID = 1L;
        }
    }

    /** Every declared shape the element rule is pinned against. */
    @SuppressWarnings("rawtypes")
    static class Shapes {
        Pair<Dto, Other> pair;
        Pair<? extends Dto, Other> boundedPair;
        Fixed<Dto> fixed;
        Weird<Other, Dto> weird;
        Deep<Dto> deep;
        Opt<Dto> nestedBinding;
        WildOpt<Dto> wildcardNestedBinding;
        WildSuper<Dto> wildcardSuperBinding;
        List<Dto> list;
        Set<String> strings;
        List raw;
        List<List<Dto>> nested;
        Dto[] array;
        List<Optional<Dto>> optionalElements;
        Dtos dtos;
        Outer<Dto>.Inner ownerBound;
    }
}
