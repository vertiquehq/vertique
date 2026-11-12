// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Utility for resolving generic type arguments and collecting interface hierarchies.
 *
 * <p>Provides two complementary BFS-based operations over the class and interface hierarchy:
 *
 * <ul>
 *   <li>{@link #resolveTypeArgument(Class, Class)} — finds the first concrete type argument bound
 *       to a given target interface
 *   <li>{@link #resolveTypeArgument(Class, Class, int)} — finds the type argument at a specified
 *       index bound to a given target interface
 *   <li>{@link #getAllInterfaces} — collects all interfaces transitively reachable from a class
 * </ul>
 *
 * <p>Example: given {@code class Foo implements Bar<String>}, calling
 * {@code resolveTypeArgument(Foo.class, Bar.class)} returns {@code String.class}.
 *
 * <p>Known limitation of {@link #resolveTypeArgument(Class, Class)}: type variable forwarding
 * through parameterized intermediate interfaces is not supported. For example, given
 * {@code interface Forwarding<T> extends Target<T>} and
 * {@code class Impl implements Forwarding<String>}, the resolver returns {@code null} because it
 * sees {@code Target<T>} (a type variable) rather than a concrete class. Only the case where a
 * concrete type is directly bound in an {@code extends} or {@code implements} clause is resolved.
 */
public final class TypeResolver {

    private TypeResolver() {}

    /** Resolves hierarchy variables in a type as viewed from the concrete class.
     * Unbound variables are retained.
     * @param context concrete hierarchy
     * @param type declared type
     * @return substituted type
     */
    public static Type resolveMemberType(Class<?> context, Type type) {
        return substitute(type, GenericBindings.of(context), new HashSet<>());
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings, Set<TypeVariable<?>> visiting) {
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            if (bound == null || !visiting.add(variable)) return type;
            Type result = substitute(bound, bindings, visiting);
            visiting.remove(variable);
            return result;
        }
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) args[i] = substitute(args[i], bindings, visiting);
            Type owner = pt.getOwnerType();
            Type resolvedOwner = owner == null ? null : substitute(owner, bindings, visiting);
            if (Arrays.equals(args, pt.getActualTypeArguments()) && Objects.equals(owner, resolvedOwner)) return pt;
            return new ResolvedParameterizedType(pt.getRawType(), resolvedOwner, args);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            Type[] lower = wildcard.getLowerBounds();
            for (int i = 0; i < upper.length; i++) upper[i] = substitute(upper[i], bindings, visiting);
            for (int i = 0; i < lower.length; i++) lower[i] = substitute(lower[i], bindings, visiting);
            if (Arrays.equals(upper, wildcard.getUpperBounds()) && Arrays.equals(lower, wildcard.getLowerBounds()))
                return wildcard;
            return new WildcardType() {
                public Type[] getUpperBounds() {
                    return upper.clone();
                }

                public Type[] getLowerBounds() {
                    return lower.clone();
                }

                public boolean equals(Object other) {
                    return other instanceof WildcardType w
                            && Arrays.equals(upper, w.getUpperBounds())
                            && Arrays.equals(lower, w.getLowerBounds());
                }

                public int hashCode() {
                    return Arrays.hashCode(upper) ^ Arrays.hashCode(lower);
                }

                public String getTypeName() {
                    if (lower.length > 0) return "? super " + lower[0].getTypeName();
                    if (upper.length == 0 || upper[0] == Object.class) return "?";
                    return "? extends " + upper[0].getTypeName();
                }

                public String toString() {
                    return getTypeName();
                }
            };
        }
        if (type instanceof GenericArrayType array) {
            Type component = substitute(array.getGenericComponentType(), bindings, visiting);
            if (component instanceof Class<?> cls)
                return Array.newInstance(cls, 0).getClass();
            return new ResolvedGenericArrayType(component);
        }
        return type;
    }

    private record ResolvedGenericArrayType(Type component) implements GenericArrayType {
        @Override
        public Type getGenericComponentType() {
            return component;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType array && component.equals(array.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return component.hashCode();
        }

        @Override
        public String getTypeName() {
            return component.getTypeName() + "[]";
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }

    private record ResolvedParameterizedType(Type raw, Type owner, Type[] arguments) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType pt
                    && Objects.equals(raw, pt.getRawType())
                    && Objects.equals(owner, pt.getOwnerType())
                    && Arrays.equals(arguments, pt.getActualTypeArguments());
        }

        @Override
        public String getTypeName() {
            String base =
                    owner == null ? raw.getTypeName() : owner.getTypeName() + "$" + ((Class<?>) raw).getSimpleName();
            if (arguments.length == 0) return base;
            return base + "<"
                    + Arrays.stream(arguments).map(Type::getTypeName).collect(java.util.stream.Collectors.joining(", "))
                    + ">";
        }

        @Override
        public String toString() {
            return getTypeName();
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ Objects.hashCode(raw);
        }
    }

    /**
     * Resolves the first type argument (index {@code 0}) of the given {@code targetInterface}
     * as implemented by {@code clazz}. Delegates to
     * {@link #resolveTypeArgument(Class, Class, int)} with {@code index = 0}.
     *
     * @param clazz           the concrete class to inspect
     * @param targetInterface the generic interface whose type argument to resolve (e.g.,
     *                        {@code ExceptionMapper.class})
     * @return the resolved type argument as a {@link Class}, or {@code null} if it cannot be
     *         determined (e.g., the type argument is a type variable, wildcard, or the interface
     *         is not found in the hierarchy)
     */
    public static Class<?> resolveTypeArgument(Class<?> clazz, Class<?> targetInterface) {
        return resolveTypeArgument(clazz, targetInterface, 0);
    }

    /**
     * Resolves the type argument at the given index from a concrete class's implementation of a
     * generic interface. Performs a BFS over the class and interface hierarchy to find a
     * parameterized declaration of {@code targetInterface} with a concrete type argument at the
     * specified position.
     *
     * @param clazz           the concrete class to inspect
     * @param targetInterface the generic interface whose type argument to resolve
     * @param index           the zero-based index of the type argument
     * @return the resolved class, or {@code null} if the type argument cannot be resolved
     *         (e.g., the argument is a type variable, wildcard, the index is out of range, or
     *         the interface is not found in the hierarchy)
     */
    public static Class<?> resolveTypeArgument(Class<?> clazz, Class<?> targetInterface, int index) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();

        if (clazz != null && clazz != Object.class) {
            queue.add(clazz);
        }

        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }

            // Check generic interfaces at this level
            for (Type genericInterface : current.getGenericInterfaces()) {
                Class<?> resolved = extractTypeArgument(genericInterface, targetInterface, index);
                if (resolved != null) {
                    return resolved;
                }
                // Queue the raw interface class for further BFS exploration
                Class<?> rawIface = rawClass(genericInterface);
                if (rawIface != null && !visited.contains(rawIface)) {
                    queue.add(rawIface);
                }
            }

            // Check generic superclass
            Type genericSuperclass = current.getGenericSuperclass();
            if (genericSuperclass != null) {
                Class<?> resolved = extractTypeArgument(genericSuperclass, targetInterface, index);
                if (resolved != null) {
                    return resolved;
                }
            }

            // Queue the superclass for further exploration
            Class<?> superclass = current.getSuperclass();
            if (superclass != null && superclass != Object.class && !visited.contains(superclass)) {
                queue.add(superclass);
            }
        }

        return null;
    }

    /**
     * Collects all interfaces transitively reachable from {@code clazz}, including super-interfaces
     * of every directly or indirectly implemented interface.
     *
     * <p>Uses BFS over the full interface graph seeded from the class chain (the class itself plus
     * all superclasses). Interfaces are returned in discovery order; the {@link LinkedHashSet}
     * guarantees no duplicates.
     *
     * @param clazz the class to inspect; {@code null} and {@code Object.class} produce an empty set
     * @return ordered set of all interfaces in the transitive hierarchy; never {@code null}
     */
    public static Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();

        // Seed from the class chain (class + superclasses)
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                if (interfaces.add(iface)) {
                    queue.add(iface);
                }
            }
            current = current.getSuperclass();
        }

        // BFS through interface hierarchy
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            for (Class<?> superIface : iface.getInterfaces()) {
                if (interfaces.add(superIface)) {
                    queue.add(superIface);
                }
            }
        }

        return interfaces;
    }

    /**
     * Extracts the type argument at the given index from a {@link Type} if it is a
     * {@link ParameterizedType} whose raw type matches {@code targetInterface}.
     *
     * @param type            the type to inspect
     * @param targetInterface the raw interface type to match against
     * @param index           zero-based index of the type argument to extract
     * @return the type argument at the given index as a {@link Class}, or {@code null} if the
     *         type does not match, the argument is not a concrete class, or the index is out of range
     */
    private static Class<?> extractTypeArgument(Type type, Class<?> targetInterface, int index) {
        if (!(type instanceof ParameterizedType parameterized)) {
            return null;
        }
        if (parameterized.getRawType() != targetInterface) {
            return null;
        }
        Type[] typeArgs = parameterized.getActualTypeArguments();
        if (index >= typeArgs.length) {
            return null;
        }
        Type arg = typeArgs[index];
        if (arg instanceof Class<?> argClass) {
            return argClass;
        }
        return null;
    }

    /**
     * Extracts the raw {@link Class} from a {@link Type}, handling both plain {@link Class}
     * instances and {@link ParameterizedType} instances.
     *
     * @param type the type to extract the raw class from
     * @return the raw class, or {@code null} if the type is not a class or parameterized type
     */
    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return null;
    }
}
