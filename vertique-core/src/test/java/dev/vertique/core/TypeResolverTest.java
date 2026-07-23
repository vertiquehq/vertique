// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.util.TypeResolver;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TypeResolver}.
 *
 * <p>Covers {@link TypeResolver#resolveTypeArgument}: direct implementation, abstract base class,
 * unrelated class, intermediate interface, deep interface chains, known limitations around type
 * variable forwarding, and the indexed overload with multiple type parameters.
 *
 * <p>Also covers {@link TypeResolver#getAllInterfaces}: null/Object edge cases, direct interfaces,
 * and transitive collection through multi-level interface hierarchies.
 */
class TypeResolverTest {

    // --- Test Fixtures ---

    /** Generic target interface used across all test cases. */
    interface GenericInterface<T> {}

    /** Two-parameter generic interface for index-based resolution tests. */
    interface TwoParam<A, B> {}

    /** Implementation binding both type arguments directly in the {@code implements} clause. */
    static class TwoParamImpl implements TwoParam<String, Integer> {}

    /** Direct implementation — binds type argument in the {@code implements} clause. */
    static class DirectImpl implements GenericInterface<String> {}

    /** Abstract middle layer that forwards the type variable. */
    abstract static class AbstractImpl<T> implements GenericInterface<T> {}

    /** Concrete class that binds the type argument in the {@code extends} clause. */
    static class ConcreteImpl extends AbstractImpl<Integer> implements GenericInterface<Integer> {}

    /** Unrelated class with no connection to {@link GenericInterface}. */
    static class NoGenericInterface {}

    /** Intermediate interface that binds the type argument in its own {@code extends} clause. */
    interface IntermediateInterface extends GenericInterface<String> {}

    /** Implementation that reaches {@link GenericInterface} only through {@link IntermediateInterface}. */
    static class IntermediateImpl implements IntermediateInterface {}

    /** First level in a multi-hop interface chain. */
    interface Level1 extends GenericInterface<Integer> {}

    /** Second level in a multi-hop interface chain — extends Level1 without rebinding. */
    interface Level2 extends Level1 {}

    /** Implementation that reaches {@link GenericInterface} through a two-level interface chain. */
    static class DeepChainImpl implements Level2 {}

    /**
     * Intermediate interface that forwards its own type variable — the concrete type is bound
     * only by the implementing class, not in the {@code extends} clause.
     */
    interface ForwardingInterface<T> extends GenericInterface<T> {}

    /**
     * Implementation where the concrete type ({@code String}) is bound at the class level
     * through a forwarding interface. This case is NOT supported due to type variable forwarding.
     */
    static class ForwardingImpl implements ForwardingInterface<String> {}

    // --- Fixtures for getAllInterfaces ---

    /** Leaf interface — no super-interfaces. */
    interface Leaf {}

    /** Mid-level interface that extends {@link Leaf}. */
    interface MiddleA extends Leaf {}

    /** Another mid-level interface with no super-interfaces. */
    interface MiddleB {}

    /** Root interface that extends both {@link MiddleA} and {@link MiddleB}. */
    interface Root extends MiddleA, MiddleB {}

    /** Concrete class that transitively reaches {@link Leaf} through {@link Root} → {@link MiddleA}. */
    static class ConcreteWithDeepInterfaces implements Root {}

    // --- Tests ---

    @Test
    @DisplayName("Should resolve type argument from direct interface implementation")
    void shouldResolveFromDirectImpl() {
        Class<?> result = TypeResolver.resolveTypeArgument(DirectImpl.class, GenericInterface.class);
        assertEquals(String.class, result);
    }

    @Test
    @DisplayName("Should resolve type argument from concrete subclass of abstract parameterized class")
    void shouldResolveFromAbstractBaseClass() {
        Class<?> result = TypeResolver.resolveTypeArgument(ConcreteImpl.class, GenericInterface.class);
        assertEquals(Integer.class, result);
    }

    @Test
    @DisplayName("Should return null when class does not implement the target interface")
    void shouldReturnNullForUnrelatedClass() {
        Class<?> result = TypeResolver.resolveTypeArgument(NoGenericInterface.class, GenericInterface.class);
        assertNull(result);
    }

    @Test
    @DisplayName("Should resolve type argument through intermediate interface extending the target")
    void shouldResolveThroughIntermediateInterface() {
        Class<?> result = TypeResolver.resolveTypeArgument(IntermediateImpl.class, GenericInterface.class);
        assertEquals(String.class, result);
    }

    @Test
    @DisplayName("Should resolve type argument through a deep multi-level interface chain")
    void shouldResolveThroughDeepInterfaceChain() {
        Class<?> result = TypeResolver.resolveTypeArgument(DeepChainImpl.class, GenericInterface.class);
        assertEquals(Integer.class, result);
    }

    @Test
    @DisplayName(
            "Should return null for type variable forwarding through parameterized intermediate interface (known limitation)")
    void shouldReturnNullForTypeVariableForwarding() {
        // ForwardingImpl implements ForwardingInterface<String>, which extends GenericInterface<T>.
        // The resolver sees GenericInterface<T> (a type variable) — not a concrete class — so
        // resolution fails. This is a documented limitation of the resolver.
        Class<?> result = TypeResolver.resolveTypeArgument(ForwardingImpl.class, GenericInterface.class);
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for Object.class")
    void shouldReturnNullForObjectClass() {
        Class<?> result = TypeResolver.resolveTypeArgument(Object.class, GenericInterface.class);
        assertNull(result);
    }

    // --- resolveTypeArgument(clazz, targetInterface, index) tests ---

    @Test
    @DisplayName("Should resolve first type argument at index 0 for two-parameter interface")
    void shouldResolveTypeArgAtIndex0() {
        Class<?> result = TypeResolver.resolveTypeArgument(TwoParamImpl.class, TwoParam.class, 0);
        assertEquals(String.class, result);
    }

    @Test
    @DisplayName("Should resolve second type argument at index 1 for two-parameter interface")
    void shouldResolveTypeArgAtIndex1() {
        Class<?> result = TypeResolver.resolveTypeArgument(TwoParamImpl.class, TwoParam.class, 1);
        assertEquals(Integer.class, result);
    }

    @Test
    @DisplayName("Should return null for out-of-range index")
    void shouldReturnNullForOutOfRangeIndex() {
        Class<?> result = TypeResolver.resolveTypeArgument(TwoParamImpl.class, TwoParam.class, 2);
        assertNull(result);
    }

    @Test
    @DisplayName("Indexed overload at index 0 should behave the same as the single-arg overload")
    void shouldMatchSingleArgOverloadAtIndex0() {
        Class<?> indexed = TypeResolver.resolveTypeArgument(DirectImpl.class, GenericInterface.class, 0);
        Class<?> singleArg = TypeResolver.resolveTypeArgument(DirectImpl.class, GenericInterface.class);
        assertEquals(singleArg, indexed);
    }

    // --- getAllInterfaces tests ---

    @Test
    @DisplayName("getAllInterfaces: should return empty set for null")
    void getAllInterfaces_shouldReturnEmptyForNull() {
        assertTrue(TypeResolver.getAllInterfaces(null).isEmpty());
    }

    @Test
    @DisplayName("getAllInterfaces: should return empty set for Object.class")
    void getAllInterfaces_shouldReturnEmptyForObjectClass() {
        assertTrue(TypeResolver.getAllInterfaces(Object.class).isEmpty());
    }

    @Test
    @DisplayName("getAllInterfaces: should return directly implemented interfaces")
    void getAllInterfaces_shouldReturnDirectInterfaces() {
        Set<Class<?>> result = TypeResolver.getAllInterfaces(DirectImpl.class);
        assertTrue(result.contains(GenericInterface.class));
    }

    @Test
    @DisplayName("getAllInterfaces: should transitively collect super-interfaces through multi-level chain")
    void getAllInterfaces_shouldCollectTransitiveSuperInterfaces() {
        Set<Class<?>> result = TypeResolver.getAllInterfaces(ConcreteWithDeepInterfaces.class);
        assertTrue(result.contains(Root.class));
        assertTrue(result.contains(MiddleA.class));
        assertTrue(result.contains(MiddleB.class));
        assertTrue(result.contains(Leaf.class));
    }
}
