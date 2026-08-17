// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import jakarta.annotation.Nullable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Optional;

/**
 * The single set of type-classification rules used by every reflective site in this module.
 *
 * <p>{@link DefaultInputObjectProcessor} classifies the entry-point target type and its element
 * type; {@link InputPolicyMetadataResolver} classifies each declared field. Both go through this
 * class, so a wildcard, a type variable or a {@code java.util.Optional} layer reduces to the same
 * {@link Class} wherever it appears — a field declared {@code List<? extends Node>} and a body
 * parameter declared {@code List<? extends Node>} are the same shape and must behave the same way.
 * Keeping two implementations in step by convention is what produced the divergence this class
 * removes.
 *
 * <p>The rules, applied in this order by {@link #normalize}:
 *
 * <ul>
 *   <li>a {@link WildcardType} or {@link TypeVariable} resolves to its first bound — the type javac
 *       writes into the erased signature, and therefore the type Jackson binds;</li>
 *   <li>{@code Optional<T>} is transparent and resolves to {@code T}, because the intermediate wire
 *       value of an {@code Optional<T>} is the unwrapped {@code T};</li>
 *   <li>a {@link ParameterizedType} keeps its parameterization; {@link #classify} erases it to the
 *       raw class only when a {@code Class} is what the caller needs.</li>
 * </ul>
 *
 * <p>These rules are symmetric with the APT-time {@code AnnotationCollector} in
 * {@code vertique-codegen-sanitization}, so the generated and reflective paths classify identically.
 *
 * <p>All methods are null-tolerant and side-effect free.
 */
final class TypeClassifier {

    private TypeClassifier() {}

    /**
     * Reduces a declared type to the type that is actually bound at runtime: wildcards and type
     * variables resolve to their bound and {@code Optional} layers are unwrapped, while
     * parameterization is preserved so callers can still read type arguments.
     *
     * <p>A raw {@code Optional} has no type argument to unwrap to and is returned unchanged, which
     * keeps it a classifiable (if schema-free) shape rather than an unclassifiable one.
     *
     * @param type the declared type; may be {@code null}
     * @return the normalized type, or {@code null} when {@code type} is {@code null}
     */
    @Nullable
    static Type normalize(@Nullable Type type) {
        Type current = normalizeToBound(type);
        while (rawClassOf(current) == Optional.class) {
            Type wrapped = normalizeToBound(optionalTypeArgument(current));
            if (rawClassOf(wrapped) == null) {
                // Raw Optional (or a type argument that resolves to no class) — nothing to unwrap to.
                return current;
            }
            current = wrapped;
        }
        return current;
    }

    /**
     * Reduces a declared type to the single {@link Class} it binds against, applying every rule in
     * {@link #normalize} and then erasing any parameterization.
     *
     * <p>Returns {@code null} only for a type that reduces to no class at all — a
     * {@link GenericArrayType} such as {@code List<Inner>[]}, or a non-JDK {@link Type}
     * implementation. Callers treat that as "this shape cannot be processed".
     *
     * @param type the declared type; may be {@code null}
     * @return the bound class, or {@code null} when the type reduces to no class
     */
    @Nullable
    static Class<?> classify(@Nullable Type type) {
        return rawClassOf(normalize(type));
    }

    /**
     * Returns the element class of a type whose wire shape is a JSON array — a generic
     * {@link java.util.Collection} or an array. Both are iterated element-wise by the walker and
     * both carry exactly one element schema, so they classify through one rule.
     *
     * <p>So {@code List<Node>}, {@code List<? extends Node>}, {@code List<Optional<Node>>} and
     * {@code Node[]} all yield {@code Node}.
     *
     * <p>Returns {@code null} — "no element schema", which routes the field to handling that still
     * applies inherited chains to string leaves — for a raw collection, an element type that
     * resolves to {@link Object} or to a raw {@code Optional} (neither carries a property set), an
     * element type that is itself an array, and any type that is neither a single-argument
     * parameterized type nor an array.
     *
     * @param type the collection or array type; may be {@code null}
     * @return the element class, or {@code null} when no element schema is determinable
     */
    @Nullable
    static Class<?> elementType(@Nullable Type type) {
        Type container = normalize(type);
        Class<?> candidate = null;
        if (container instanceof GenericArrayType genericArray) {
            candidate = classify(genericArray.getGenericComponentType());
        } else if (container instanceof Class<?> cls && cls.isArray()) {
            candidate = cls.getComponentType();
        } else if (container instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1) {
                candidate = classify(args[0]);
            }
        }
        if (candidate == null || candidate == Object.class || candidate == Optional.class || candidate.isArray()) {
            return null;
        }
        return candidate;
    }

    /**
     * Normalizes a type to the bound Jackson actually binds against.
     *
     * <ul>
     *   <li>{@link WildcardType} — resolves to the first upper bound. Reflection reports
     *       {@code java.lang.Object} as the upper bound of an unbounded {@code ?} and of a
     *       lower-bounded {@code ? super X}, so both normalize to {@code Object}.</li>
     *   <li>{@link TypeVariable} — resolves to the first declared bound. For an intersection bound
     *       ({@code T extends A & B}) that is {@code A}, which is also the type javac writes into
     *       the erased signature.</li>
     *   <li>Anything else is returned unchanged.</li>
     * </ul>
     *
     * <p>Bounds may themselves be wildcards or type variables, so resolution recurses. Java forbids
     * circular type-variable bounds, so the recursion terminates.
     *
     * @param type the type to normalize; may be {@code null}
     * @return the normalized type, or {@code null} when {@code type} is {@code null}
     */
    @Nullable
    static Type normalizeToBound(@Nullable Type type) {
        if (type == null) {
            return null;
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upperBounds = wildcard.getUpperBounds();
            return upperBounds.length == 0 ? Object.class : normalizeToBound(upperBounds[0]);
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            Type[] bounds = typeVariable.getBounds();
            return bounds.length == 0 ? Object.class : normalizeToBound(bounds[0]);
        }
        return type;
    }

    /**
     * Extracts the raw {@link Class} from a {@link Type}, handling plain classes and parameterized
     * types. Applies no normalization of its own — callers that want wildcard, type-variable and
     * {@code Optional} handling use {@link #classify}.
     *
     * @param type the type to erase; may be {@code null}
     * @return the raw class, or {@code null} when the type has no resolvable raw class
     */
    @Nullable
    static Class<?> rawClassOf(@Nullable Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return null;
    }

    /**
     * Returns the single type argument of an {@code Optional<T>} generic type.
     *
     * @param type the {@code Optional} generic type; may be {@code null}
     * @return the {@code T} type, or {@code null} for a raw {@code Optional}
     */
    @Nullable
    static Type optionalTypeArgument(@Nullable Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1) {
                return args[0];
            }
        }
        return null;
    }
}
