// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Binds the type variables of a class hierarchy and compares method signatures once those variables
 * are resolved.
 *
 * <p>{@code interface Crud<ID> { void delete(ID id); }} implemented as
 * {@code class Users implements Crud<String> { public void delete(String id) {} }} declares
 * {@code delete(Object)} in the interface and {@code delete(String)} in the class. The two have
 * different erased signatures but the class method overrides the interface method; this type
 * recognizes that by resolving {@code ID} through the hierarchy.
 *
 * <p>Bindings are keyed by the type variable itself, which is unique per declaration, so one map
 * serves a whole hierarchy and a chain of forwarding declarations
 * ({@code Crud.ID -> Base.T -> String}) resolves by following it. The first binding found for a
 * variable wins. Arguments of an owner type ({@code Outer<String>.Inner}) are bound too.
 */
final class GenericBindings {

    private static final ClassValue<Map<TypeVariable<?>, Type>> HIERARCHY = new ClassValue<>() {
        @Override
        protected Map<TypeVariable<?>, Type> computeValue(Class<?> type) {
            Map<TypeVariable<?>, Type> bindings = new LinkedHashMap<>();
            walk(type, bindings, new HashSet<>());
            return Map.copyOf(bindings);
        }
    };

    private GenericBindings() {}

    /**
     * Returns the type variable bindings visible from {@code type}: the arguments it and its
     * supertypes pass to their generic superclasses and interfaces.
     *
     * @param type the class or interface whose hierarchy is bound
     * @return an immutable map of variable to bound type; never {@code null}
     */
    static Map<TypeVariable<?>, Type> of(Class<?> type) {
        return HIERARCHY.get(type);
    }

    /**
     * Returns whether {@code method}'s signature is a subsignature of {@code candidate}'s once type
     * variables are resolved (JLS 8.4.2): the same name, arity and parameter types after
     * substitution, or parameter types equal to the erasure of the declaration's substituted
     * signature. This is the signature half of "overrides"; modifiers and visibility are the
     * caller's.
     *
     * <p>Parameter types are compared structurally, not by erasure: {@code purge(List<Integer>)}
     * does not override {@code purge(T)} of a {@code Base<List<String>>}, although both erase to
     * {@code List}. A method with type parameters of its own overrides only a declaration with the
     * same number of them, mapped by position, with equal bounds and the same parameter types; a
     * method with none may also override a generic declaration by erasure, so
     * {@code put(String, Object)} overrides {@code <X> put(T, X)} of a {@code Base<String>}, and a
     * raw {@code List} parameter overrides {@code List<String>}.
     *
     * @param method    the possibly overriding method
     * @param candidate the possibly overridden declaration
     * @param bindings  the type variable bindings in effect
     * @return {@code true} when {@code method}'s signature is a subsignature of {@code candidate}'s
     */
    static boolean corresponds(Method method, Method candidate, Map<TypeVariable<?>, Type> bindings) {
        if (!method.getName().equals(candidate.getName())
                || method.getParameterCount() != candidate.getParameterCount()) {
            return false;
        }
        TypeVariable<Method>[] own = method.getTypeParameters();
        TypeVariable<Method>[] other = candidate.getTypeParameters();
        boolean generic = own.length > 0;
        Map<TypeVariable<?>, Type> effective = bindings;
        if (generic) {
            if (own.length != other.length) {
                return false;
            }
            effective = new LinkedHashMap<>(bindings);
            for (int i = 0; i < own.length; i++) {
                effective.put(other[i], own[i]);
            }
            for (int i = 0; i < own.length; i++) {
                if (!sameBounds(own[i].getBounds(), other[i].getBounds(), effective)) {
                    return false;
                }
            }
        }
        Type[] wanted = method.getGenericParameterTypes();
        Type[] declared = candidate.getGenericParameterTypes();
        boolean same = true;
        for (int i = 0; i < wanted.length && same; i++) {
            same = sameType(wanted[i], declared[i], effective, 0);
        }
        if (same) {
            return true;
        }
        if (generic) {
            return false;
        }
        // A method without type parameters of its own is also a subsignature when its parameter
        // types equal the erasure of the declaration's substituted signature.
        for (int i = 0; i < wanted.length; i++) {
            Class<?> erased = erasure(declared[i], bindings, new HashSet<>());
            if (!(wanted[i] instanceof Class<?> raw) || raw != erased) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two intersection bounds as sets: {@code <T extends A & B>} and
     * {@code <T extends B & A>} declare the same type parameter, and javac accepts one as an
     * override of the other.
     */
    private static boolean sameBounds(Type[] left, Type[] right, Map<TypeVariable<?>, Type> bindings) {
        if (left.length != right.length) {
            return false;
        }
        boolean[] used = new boolean[right.length];
        for (Type bound : left) {
            boolean matched = false;
            for (int j = 0; j < right.length && !matched; j++) {
                if (!used[j] && sameType(bound, right[j], bindings, 0)) {
                    used[j] = true;
                    matched = true;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        for (int hops = 0; hops < 64 && current instanceof TypeVariable<?> variable; hops++) {
            Type bound = bindings.get(variable);
            if (bound == null) {
                break;
            }
            current = bound;
        }
        return current;
    }

    static boolean sameType(Type a, Type b, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (depth > 32) {
            return false;
        }
        Type left = resolve(a, bindings);
        Type right = resolve(b, bindings);
        if (left instanceof Class<?> leftClass && right instanceof Class<?> rightClass) {
            return leftClass == rightClass;
        }
        if (left instanceof ParameterizedType leftType && right instanceof ParameterizedType rightType) {
            Type[] leftArguments = leftType.getActualTypeArguments();
            Type[] rightArguments = rightType.getActualTypeArguments();
            if (leftType.getRawType() != rightType.getRawType() || leftArguments.length != rightArguments.length) {
                return false;
            }
            for (int i = 0; i < leftArguments.length; i++) {
                if (!sameType(leftArguments[i], rightArguments[i], bindings, depth + 1)) {
                    return false;
                }
            }
            Type leftOwner = leftType.getOwnerType();
            Type rightOwner = rightType.getOwnerType();
            return leftOwner == null || rightOwner == null
                    ? leftOwner == rightOwner
                    : sameType(leftOwner, rightOwner, bindings, depth + 1);
        }
        Type leftComponent = componentOf(left);
        Type rightComponent = componentOf(right);
        if (leftComponent != null || rightComponent != null) {
            return leftComponent != null
                    && rightComponent != null
                    && sameType(leftComponent, rightComponent, bindings, depth + 1);
        }
        if (left instanceof WildcardType leftWildcard && right instanceof WildcardType rightWildcard) {
            return sameTypes(leftWildcard.getUpperBounds(), rightWildcard.getUpperBounds(), bindings, depth)
                    && sameTypes(leftWildcard.getLowerBounds(), rightWildcard.getLowerBounds(), bindings, depth);
        }
        // Reflection may hand out equal but not identical TypeVariable instances: generic info is
        // initialised lazily and without synchronisation, so a concurrent first read of a class
        // produces two copies, and every Method copy parses its own. equals() is the contract.
        return left instanceof TypeVariable<?> && left.equals(right);
    }

    private static boolean sameTypes(Type[] left, Type[] right, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!sameType(left[i], right[i], bindings, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static Type componentOf(Type type) {
        if (type instanceof GenericArrayType array) {
            return array.getGenericComponentType();
        }
        if (type instanceof Class<?> clazz && clazz.isArray()) {
            return clazz.getComponentType();
        }
        return null;
    }

    private static Class<?> erasure(Type type, Map<TypeVariable<?>, Type> bindings, Set<TypeVariable<?>> seen) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return erasure(parameterized.getRawType(), bindings, seen);
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = erasure(array.getGenericComponentType(), bindings, seen);
            return Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 ? Object.class : erasure(upper[0], bindings, seen);
        }
        if (type instanceof TypeVariable<?> variable && seen.add(variable)) {
            Type bound = bindings.get(variable);
            if (bound != null) {
                return erasure(bound, bindings, seen);
            }
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : erasure(bounds[0], bindings, seen);
        }
        return Object.class;
    }

    private static void walk(Class<?> type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        if (type == null || type == Object.class || !seen.add(type)) {
            return;
        }
        visit(type.getGenericSuperclass(), bindings, seen);
        for (Type genericInterface : type.getGenericInterfaces()) {
            visit(genericInterface, bindings, seen);
        }
    }

    private static void visit(Type supertype, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        if (supertype instanceof ParameterizedType parameterized) {
            bind(parameterized, bindings);
            if (parameterized.getRawType() instanceof Class<?> raw) {
                walk(raw, bindings, seen);
            }
        } else if (supertype instanceof Class<?> raw) {
            walk(raw, bindings, seen);
        }
    }

    private static void bind(ParameterizedType parameterized, Map<TypeVariable<?>, Type> bindings) {
        if (parameterized.getRawType() instanceof Class<?> raw) {
            TypeVariable<?>[] parameters = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < parameters.length && i < arguments.length; i++) {
                bindings.putIfAbsent(parameters[i], arguments[i]);
            }
        }
        if (parameterized.getOwnerType() instanceof ParameterizedType owner) {
            bind(owner, bindings);
        }
    }
}
