// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies value semantics of substituted reflective member types. */
class TypeResolverMemberTypeTest {
    static class Base<T> {
        List<T>[] array;
        List<List<T>> nested;
        List<? extends T> upper;
        List<? super T> lower;
    }

    static class Concrete extends Base<String> {}

    static class Expected {
        List<String>[] array;
        List<List<String>> nested;
        List<? extends String> upper;
        List<? super String> lower;
    }

    @Test
    @DisplayName("Resolved member types retain reflection value semantics")
    void resolvedTypesMatchReflectionValues() throws Exception {
        for (String name : List.of("array", "nested", "upper", "lower")) {
            Type declared = Base.class.getDeclaredField(name).getGenericType();
            Type expected = Expected.class.getDeclaredField(name).getGenericType();
            Type actual = TypeResolver.resolveMemberType(Concrete.class, declared);
            assertEquals(expected, actual);
            assertEquals(actual, expected);
            assertEquals(expected.hashCode(), actual.hashCode());
            assertEquals(expected.getTypeName(), actual.getTypeName());
            assertEquals(expected.toString(), actual.toString());
            if (expected instanceof java.lang.reflect.ParameterizedType expectedParameterized) {
                Type expectedArgument = expectedParameterized.getActualTypeArguments()[0];
                Type actualArgument = ((java.lang.reflect.ParameterizedType) actual).getActualTypeArguments()[0];
                assertEquals(expectedArgument, actualArgument);
                assertEquals(actualArgument, expectedArgument);
                assertEquals(expectedArgument.hashCode(), actualArgument.hashCode());
                assertEquals(expectedArgument.getTypeName(), actualArgument.getTypeName());
                assertEquals(expectedArgument.toString(), actualArgument.toString());
            }
        }
    }

    interface Contract<C> {
        C contract(List<C> values);
    }

    interface Forwarding<F> extends Contract<F> {}

    static class Middle<M> implements Forwarding<M> {
        public M contract(List<M> values) {
            return null;
        }

        public <X> X own(X value, M bound) {
            return value;
        }
    }

    static class MultiHop extends Middle<Integer> {}

    @SuppressWarnings("rawtypes")
    static class RawHierarchy extends Middle {}

    @Test
    @DisplayName("A variable forwarded through intermediate classes and interfaces resolves to the bound type")
    void forwardedVariableResolvesThroughTheHierarchy() throws Exception {
        Method method = Contract.class.getMethod("contract", List.class);

        assertEquals(Integer.class, TypeResolver.resolveMemberType(MultiHop.class, method.getGenericReturnType()));
        assertEquals(
                "java.util.List<java.lang.Integer>",
                TypeResolver.resolveMemberType(MultiHop.class, method.getGenericParameterTypes()[0])
                        .getTypeName());
    }

    @Test
    @DisplayName("A variable no class in the hierarchy binds stays unresolved")
    void unboundVariableIsRetained() throws Exception {
        Type declared = Contract.class.getMethod("contract", List.class).getGenericReturnType();

        assertSame(declared, TypeResolver.resolveMemberType(Contract.class, declared));
        // The raw hierarchy forwards C to Middle's own variable M, which nothing binds.
        Type viaRaw = TypeResolver.resolveMemberType(RawHierarchy.class, declared);
        assertEquals("M", ((TypeVariable<?>) viaRaw).getName());
    }

    @Test
    @DisplayName("A method's own type variable stays unresolved while a class variable resolves")
    void methodOwnVariableIsNotBound() throws Exception {
        Method own = Middle.class.getMethod("own", Object.class, Object.class);
        Type[] parameters = own.getGenericParameterTypes();

        Type ownVariable = TypeResolver.resolveMemberType(MultiHop.class, parameters[0]);
        assertSame(parameters[0], ownVariable);
        assertEquals("X", ((TypeVariable<?>) ownVariable).getName());
        assertEquals(Integer.class, TypeResolver.resolveMemberType(MultiHop.class, parameters[1]));
    }
}
