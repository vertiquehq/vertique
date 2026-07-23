// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.context.ContextValue;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural assertions that verify the generic bounds on {@link ContextValues} write methods and
 * {@link ContextScopeBinder#bindAll(Map)} exist at the bytecode level.
 *
 * <p>These tests are the compile-time-contract proof: they confirm that the {@code <T extends
 * ContextValue>} type-parameter constraint is present in the method signatures, preventing
 * non-{@link ContextValue} types from being passed at compile time.
 *
 * <p>All assertions are structural (reflection-based) and do not require a Vert.x context.
 */
class ContextValueBoundsTest {

    // --- ContextValues.bind ---

    @Test
    @DisplayName("ContextValues.bind has a type parameter bounded by ContextValue")
    void bindTypeParameterBoundedByContextValue() throws NoSuchMethodException {
        // The second parameter T extends ContextValue erases to ContextValue at the bytecode level.
        Method method = ContextValues.class.getDeclaredMethod("bind", Class.class, ContextValue.class);
        TypeVariable<?>[] typeParams = method.getTypeParameters();
        assertEquals(1, typeParams.length, "bind must have exactly one type parameter");
        assertSame(
                ContextValue.class,
                typeParams[0].getBounds()[0],
                "bind type parameter must be bounded by ContextValue");
    }

    // --- ContextValues.mutate ---

    @Test
    @DisplayName("ContextValues.mutate has a type parameter bounded by ContextValue")
    void mutateTypeParameterBoundedByContextValue() throws NoSuchMethodException {
        Method method = ContextValues.class.getDeclaredMethod("mutate", Class.class, Supplier.class, Consumer.class);
        TypeVariable<?>[] typeParams = method.getTypeParameters();
        assertEquals(1, typeParams.length, "mutate must have exactly one type parameter");
        assertSame(
                ContextValue.class,
                typeParams[0].getBounds()[0],
                "mutate type parameter must be bounded by ContextValue");
    }

    // --- ContextValues.mutateIfPresent ---

    @Test
    @DisplayName("ContextValues.mutateIfPresent has a type parameter bounded by ContextValue")
    void mutateIfPresentTypeParameterBoundedByContextValue() throws NoSuchMethodException {
        Method method = ContextValues.class.getDeclaredMethod("mutateIfPresent", Class.class, Consumer.class);
        TypeVariable<?>[] typeParams = method.getTypeParameters();
        assertEquals(1, typeParams.length, "mutateIfPresent must have exactly one type parameter");
        assertSame(
                ContextValue.class,
                typeParams[0].getBounds()[0],
                "mutateIfPresent type parameter must be bounded by ContextValue");
    }

    // --- ContextScopeBinder.bindAll ---

    @Test
    @DisplayName("ContextScopeBinder.bindAll Map parameter has key type Class<? extends ContextValue>")
    void bindAllMapKeyTypeIsContextValueBounded() throws NoSuchMethodException {
        Method method = ContextScopeBinder.class.getMethod("bindAll", Map.class);

        // bindAll is not itself generic — the bound lives inside the Map parameter's type args.
        assertEquals(0, method.getTypeParameters().length, "bindAll must not be a generic method");

        Type[] genericParamTypes = method.getGenericParameterTypes();
        assertEquals(1, genericParamTypes.length, "bindAll must have exactly one parameter");

        // The parameter must be a ParameterizedType (Map<K, V>).
        ParameterizedType mapType = assertInstanceOf(
                ParameterizedType.class, genericParamTypes[0], "bindAll parameter must be a parameterized Map type");
        assertSame(Map.class, mapType.getRawType(), "raw type must be Map");

        Type[] typeArgs = mapType.getActualTypeArguments();
        assertEquals(2, typeArgs.length, "Map must have exactly two type arguments");

        // First type arg: Class<? extends ContextValue>
        ParameterizedType keyType = assertInstanceOf(
                ParameterizedType.class, typeArgs[0], "Map key type must be a parameterized Class type");
        assertSame(Class.class, keyType.getRawType(), "Map key raw type must be Class");

        Type[] keyTypeArgs = keyType.getActualTypeArguments();
        assertEquals(1, keyTypeArgs.length, "Class type must have exactly one type argument");

        WildcardType keyWildcard =
                assertInstanceOf(WildcardType.class, keyTypeArgs[0], "Class type argument must be a wildcard");
        assertNotNull(keyWildcard.getUpperBounds(), "wildcard must have upper bounds");
        assertEquals(1, keyWildcard.getUpperBounds().length, "wildcard must have exactly one upper bound");
        assertSame(
                ContextValue.class,
                keyWildcard.getUpperBounds()[0],
                "Map key wildcard upper bound must be ContextValue");

        // Second type arg: ? extends ContextValue
        WildcardType valueWildcard =
                assertInstanceOf(WildcardType.class, typeArgs[1], "Map value type must be a wildcard");
        assertNotNull(valueWildcard.getUpperBounds(), "value wildcard must have upper bounds");
        assertEquals(1, valueWildcard.getUpperBounds().length, "value wildcard must have exactly one upper bound");
        assertSame(
                ContextValue.class,
                valueWildcard.getUpperBounds()[0],
                "Map value wildcard upper bound must be ContextValue");
    }
}
