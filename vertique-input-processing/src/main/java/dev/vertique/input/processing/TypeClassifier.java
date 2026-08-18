// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import jakarta.annotation.Nullable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>{@link #elementType} adds one further rule: a collection's element type is {@code E} in its
 * {@code Collection<E>} <em>supertype binding</em>, not a type argument read off the declared type by
 * position. A {@code Fixed<T> extends ArrayList<String>} binds {@code String} elements however it is
 * parameterized, and a {@code Weird<A, B> extends ArrayList<B>} binds its second argument.
 *
 * <p>These rules are symmetric with the APT-time {@code AnnotationCollector} in
 * {@code vertique-codegen-sanitization}, so the generated and reflective paths classify identically.
 *
 * <p>All {@link Type}-accepting methods are null-tolerant, and every method is side-effect free.
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
     * <p><strong>Only a genuine container yields an element type.</strong> A parameterized type is
     * consulted for an element only when its raw type is a {@link Collection} — the same gate
     * {@link InputPolicyMetadataResolver} applies before it reaches this method. Without it any
     * single-argument generic ({@code Wrapper<Node>}, {@code Holder<Node>}) would report as a
     * container, and the startup gate that calls this method directly would claim a policy the
     * walker cannot reach: the walker classifies {@code Wrapper}'s own field to its {@code Object}
     * bound and stops there.
     *
     * <p><strong>The element is the {@code Collection<E>} supertype binding</strong>, resolved by
     * {@link #collectionElementBinding} — never "type argument 0" of the declared type. Argument
     * position is not the element type: {@code Pair<A, B> extends ArrayList<A>} binds its
     * <em>first</em> argument, {@code Weird<A, B> extends ArrayList<B>} its second, and
     * {@code Fixed<T> extends ArrayList<String>} binds {@code String} whatever {@code T} is. Only
     * the supertype binding answers all three with the type Jackson actually binds.
     *
     * <p>Returns {@code null} — "no element schema", which routes the field to handling that still
     * applies inherited chains to string leaves — for a collection with no type arguments (a raw
     * one) or no resolvable binding, an element type that resolves to {@link Object} or to a raw
     * {@code Optional} (neither carries a property set), an element type that is itself a container
     * (an array, a {@link Collection} or a {@link Map}), and any type that is neither a
     * parameterized collection nor an array.
     *
     * <p>The nested-container exclusion is what keeps {@code List<List<String>>},
     * {@code Set<List<Node>>} and {@code List<Node>[]} on the inherited-chain path. Their wire
     * element is another JSON array, not an object: recording the inner container as the element
     * type routes the field into element-wise dispatch, whose non-object arm returns each inner
     * container verbatim and silently drops every chain — including the invocation-level one — that
     * should have reached the leaves beneath it. A {@link Map} element is excluded for the same
     * reason a {@code Map} field type is not descendable: its keys are arbitrary, so it carries no
     * statically known property set.
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
            Class<?> rawType = rawClassOf(parameterized);
            // Array raw types are impossible for a ParameterizedType, so the array half of the
            // caller-side gate is covered by the two branches above rather than repeated here.
            if (rawType != null && Collection.class.isAssignableFrom(rawType)) {
                // The binding may itself be a wildcard or a type variable — it is a type argument
                // written at some use site like any other — so it goes through classify, which
                // resolves it to its bound exactly as a directly-declared argument would be.
                candidate = classify(collectionElementBinding(parameterized));
            }
        }
        if (candidate == null
                || candidate == Object.class
                || candidate == Optional.class
                || candidate.isArray()
                || Collection.class.isAssignableFrom(candidate)
                || Map.class.isAssignableFrom(candidate)) {
            return null;
        }
        return candidate;
    }

    /**
     * Resolves {@code E} from the {@code Collection<E>} supertype binding of a collection type.
     *
     * <p>The walk starts at the declared type and climbs {@link Class#getGenericSuperclass()} and
     * {@link Class#getGenericInterfaces()}, substituting each class's {@link Class#getTypeParameters()
     * type parameters} with the arguments seen at its use site, until {@code java.util.Collection}
     * itself is reached; its single resolved argument is the element type. So a
     * {@code Weird<Other, Dto>} declared over {@code class Weird<A, B> extends ArrayList<B>} resolves
     * through {@code ArrayList<Dto>} to {@code Dto}, and a {@code Fixed<Dto>} declared over
     * {@code class Fixed<T> extends ArrayList<String>} resolves to {@code String}.
     *
     * <p>The result is <em>not</em> normalized here: it is a type argument as written at its
     * declaration site and may be a wildcard, a type variable or an {@code Optional} layer, so the
     * caller runs it through {@link #classify} like any other argument.
     *
     * <p>Returns {@code null} when no binding is resolvable — for a raw usage of a collection type,
     * whose type parameters have no arguments to substitute. The recursion terminates because Java
     * forbids circular inheritance, so every step is strictly closer to {@code Collection}.
     *
     * <p>Deliberately reflection-only: this module classifies types for every codec, so no Jackson
     * type machinery may leak into the rule. The APT-time {@code AnnotationCollector} in
     * {@code vertique-codegen-sanitization} resolves the same binding through
     * {@code Types.directSupertypes}, so the generated and reflective paths pick the same element.
     *
     * @param collectionType the collection type, already normalized; may be {@code null}
     * @return the element type as written at its declaration site, or {@code null} when the type is
     *         not a collection or carries no resolvable binding
     */
    @Nullable
    private static Type collectionElementBinding(@Nullable Type collectionType) {
        Class<?> rawType = rawClassOf(collectionType);
        if (rawType == null || !Collection.class.isAssignableFrom(rawType)) {
            return null;
        }
        if (rawType == Collection.class) {
            Type[] args = collectionType instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()
                    : new Type[0];
            return args.length == 1 ? args[0] : null;
        }
        Map<TypeVariable<?>, Type> bindings = bindingsOf(rawType, collectionType);
        for (Type supertype : supertypesOf(rawType)) {
            Type resolved = substitute(supertype, bindings);
            Class<?> resolvedRaw = rawClassOf(resolved);
            if (resolvedRaw != null && Collection.class.isAssignableFrom(resolvedRaw)) {
                Type element = collectionElementBinding(resolved);
                if (element != null) {
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * Maps a class's type parameters to the arguments its use site supplies.
     *
     * @param rawType the erased class
     * @param useSite the type as written at the use site
     * @return the substitution environment, empty for a raw use site
     */
    private static Map<TypeVariable<?>, Type> bindingsOf(Class<?> rawType, @Nullable Type useSite) {
        TypeVariable<?>[] parameters = rawType.getTypeParameters();
        if (!(useSite instanceof ParameterizedType parameterized)) {
            return Map.of();
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != parameters.length) {
            return Map.of();
        }
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (int i = 0; i < parameters.length; i++) {
            bindings.put(parameters[i], arguments[i]);
        }
        return bindings;
    }

    /**
     * Returns the generic supertypes of a class — its superclass first, then its interfaces.
     *
     * @param rawType the class to climb from
     * @return the declared generic supertypes
     */
    private static List<Type> supertypesOf(Class<?> rawType) {
        List<Type> supertypes = new ArrayList<>();
        Type superclass = rawType.getGenericSuperclass();
        if (superclass != null) {
            supertypes.add(superclass);
        }
        supertypes.addAll(Arrays.asList(rawType.getGenericInterfaces()));
        return supertypes;
    }

    /**
     * Rewrites {@code type} with every type variable the environment binds replaced by its argument,
     * recursing into type arguments so a supertype written {@code ArrayList<Optional<T>>} resolves to
     * {@code ArrayList<Optional<Dto>>}.
     *
     * <p>A variable the environment does not bind is left as-is: it is the raw-use-site case, and
     * {@link #normalizeToBound} later resolves it to its declared bound. A generic array component is
     * left as-is too — an array element carries no element schema either way, so substituting inside
     * it could not change an answer.
     *
     * @param type     the type to rewrite
     * @param bindings the substitution environment
     * @return the rewritten type, or {@code type} itself when nothing changed
     */
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) {
            return bindings.getOrDefault(variable, variable);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] substituted = new Type[arguments.length];
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                substituted[i] = substitute(arguments[i], bindings);
                changed |= substituted[i] != arguments[i];
            }
            return changed
                    ? new SubstitutedParameterizedType(
                            parameterized.getRawType(), parameterized.getOwnerType(), substituted)
                    : parameterized;
        }
        return type;
    }

    /**
     * A {@link ParameterizedType} whose arguments have been resolved against a substitution
     * environment. Only the three interface methods are meaningful; instances never escape
     * {@link #collectionElementBinding}'s walk and are consumed by {@link #classify} alone.
     *
     * @param rawType   the erased class of the parameterized type
     * @param ownerType the enclosing type, or {@code null}
     * @param arguments the resolved type arguments
     */
    private record SubstitutedParameterizedType(
            Type rawType, @Nullable Type ownerType, Type[] arguments) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        @Nullable
        public Type getOwnerType() {
            return ownerType;
        }
    }

    /**
     * Returns {@code true} for a scalar leaf: a primitive type or its boxed counterpart, one of the
     * other common immutable scalar JDK types that cannot carry annotations and need no recursion,
     * or an enum. A scalar leaf carries no property set the walker could descend into.
     *
     * <p>The set mirrors {@code AnnotationCollector.SCALAR_FQNS} in
     * {@code vertique-codegen-sanitization} so the generated and reflective paths classify the same
     * element and field types as scalar leaves. The two entries that set is allowed to hold without
     * a counterpart here are {@code java.lang.String} and {@code java.lang.Object}, each of which
     * {@link InputPolicyMetadataResolver} routes through its own dedicated branch.
     *
     * @param type the type to test
     * @return {@code true} if the type is a scalar leaf
     */
    static boolean isScalarLeaf(Class<?> type) {
        return type.isPrimitive()
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == Number.class
                || type == java.math.BigDecimal.class
                || type == java.math.BigInteger.class
                || type == java.time.LocalDate.class
                || type == java.time.LocalDateTime.class
                || type == java.time.OffsetDateTime.class
                || type == java.time.ZonedDateTime.class
                || type == java.time.Instant.class
                || type == java.util.UUID.class
                // Primitive Optional specializations carry no string payload and have no type
                // argument to unwrap, so they are scalar leaves rather than descendable objects.
                || type == java.util.OptionalInt.class
                || type == java.util.OptionalLong.class
                || type == java.util.OptionalDouble.class
                || type.isEnum();
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
