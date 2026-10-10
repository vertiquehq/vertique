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
import java.util.IdentityHashMap;
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
     * Returns whether {@code candidate} declares the signature that {@code method} overrides or
     * implements once type variables are resolved: the same name and arity, the same number of
     * method type parameters (mapped by position) and equal erased parameter types.
     *
     * @param method    the method whose declarations are searched
     * @param candidate the possibly overridden declaration
     * @param bindings  the type variable bindings in effect
     * @return {@code true} when the two correspond
     */
    static boolean corresponds(Method method, Method candidate, Map<TypeVariable<?>, Type> bindings) {
        if (!method.getName().equals(candidate.getName())
                || method.getParameterCount() != candidate.getParameterCount()) {
            return false;
        }
        TypeVariable<Method>[] own = method.getTypeParameters();
        TypeVariable<Method>[] other = candidate.getTypeParameters();
        if (own.length != other.length) {
            return false;
        }
        Map<TypeVariable<?>, Type> effective = bindings;
        if (other.length > 0) {
            effective = new LinkedHashMap<>(bindings);
            for (int i = 0; i < other.length; i++) {
                effective.put(other[i], own[i]);
            }
        }
        Type[] wanted = method.getGenericParameterTypes();
        Type[] declared = candidate.getGenericParameterTypes();
        for (int i = 0; i < wanted.length; i++) {
            if (erasure(wanted[i], effective, new IdentityHashMap<>())
                    != erasure(declared[i], effective, new IdentityHashMap<>())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> erasure(
            Type type, Map<TypeVariable<?>, Type> bindings, IdentityHashMap<Type, Boolean> seen) {
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
        if (type instanceof TypeVariable<?> variable && seen.put(variable, Boolean.TRUE) == null) {
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
