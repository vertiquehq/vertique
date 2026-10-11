// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.security.authz.chain.ChainMid;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@link AccessPolicyResolver} and {@link AnnotationResolver#inheritedDeclarations},
 * which the inline-annotation path uses, agree on which declarations an override reaches. The
 * resolver asks the shared override predicate for the signature and for package-private
 * visibility, so this is the test that keeps the typed-policy walk and the inline walk from
 * drifting apart, in particular for the owner-type, method-type-parameter and package-chain shapes
 * where the rule is easiest to get wrong.
 */
class AccessPolicyResolverBindingEquivalenceTest {

    @RolesAllowed("admin")
    public interface AdminPolicy extends AccessPolicy {}

    interface Crud<ID> {
        @RequiresPolicy(AdminPolicy.class)
        void delete(ID id);
    }

    static class Users implements Crud<String> {
        @Override
        public void delete(String id) {}
    }

    interface StringCrud extends Crud<String> {}

    static class Forwarded implements StringCrud {
        @Override
        public void delete(String id) {}
    }

    interface Mid<T extends CharSequence> extends Crud<T> {}

    static class Bounded implements Mid<String> {
        @Override
        public void delete(String id) {}
    }

    static class Base<T> {
        @RequiresPolicy(AdminPolicy.class)
        public void get(T value) {}
    }

    static class Derived extends Base<String> {
        @Override
        public void get(String value) {}
    }

    static class Outer<T> {
        public class Inner {
            @RequiresPolicy(AdminPolicy.class)
            public void take(T value) {}
        }
    }

    static class OwnerBound extends Outer<String>.Inner {
        OwnerBound(Outer<String> outer) {
            outer.super();
        }

        @Override
        public void take(String value) {}
    }

    static class MethodLevel implements Crud<String> {
        @Override
        public void delete(String id) {}

        public <X extends String> void delete(X id, int times) {}
    }

    interface GenericMethod<T> {
        @RequiresPolicy(AdminPolicy.class)
        <X> void put(T value, X extra);
    }

    static class ErasureOverride implements GenericMethod<String> {
        @Override
        public void put(String value, Object extra) {}
    }

    interface CharSequenceBound {
        @RequiresPolicy(AdminPolicy.class)
        <U extends CharSequence> void take(U value);
    }

    static class DifferentBounds implements CharSequenceBound {
        public <T extends Number> void take(T value) {}

        @Override
        public <U extends CharSequence> void take(U value) {}
    }

    static class SecuredBase<T> {
        @RequiresPolicy(AdminPolicy.class)
        public void purge(T value) {}
    }

    /** Same erasure as the base method, another type argument: an overload, not an override. */
    static class SameErasureOverload extends SecuredBase<java.util.List<String>> {
        public void purge(java.util.List<Integer> value) {}
    }

    static class Unrelated implements Crud<Integer> {
        @Override
        public void delete(Integer id) {}

        public void delete(String id) {}
    }

    /**
     * Carries a type-level policy over a class of another package that overrides, through
     * {@link ChainMid}, the secured package-private method of its base.
     */
    @RequiresPolicy(AdminPolicy.class)
    static class PolicyOverChain extends ChainMid {
        @Override
        public String m() {
            return "consumer";
        }
    }

    private static boolean reachedByResolver(Class<?> consumer, Method method) {
        List<Annotation> collected = AccessPolicyResolver.collectMethodAnnotations(consumer, method, List.of());
        return collected.stream().anyMatch(a -> a.annotationType() == RequiresPolicy.class);
    }

    private static boolean reachedByPrimitive(Class<?> consumer, Method method) {
        return AnnotationResolver.inheritedDeclarations(method, consumer).stream()
                .anyMatch(m -> m.isAnnotationPresent(RequiresPolicy.class));
    }

    private static void assertAgree(Class<?> consumer, String name, Class<?> parameter, boolean expected)
            throws NoSuchMethodException {
        assertAgree(consumer, name, new Class<?>[] {parameter}, expected);
    }

    private static void assertAgree(Class<?> consumer, String name, Class<?>[] parameters, boolean expected)
            throws NoSuchMethodException {
        Method method = consumer.getDeclaredMethod(name, parameters);
        assertEquals(expected, reachedByPrimitive(consumer, method), "core primitive for " + consumer.getSimpleName());
        assertEquals(
                expected,
                reachedByResolver(consumer, method),
                "access policy resolver for " + consumer.getSimpleName());
    }

    @Test
    @DisplayName("an interface method bound through a type argument is reached by both")
    void interfaceTypeArgument() throws Exception {
        assertAgree(Users.class, "delete", String.class, true);
    }

    @Test
    @DisplayName("a forwarding sub-interface and a bounded variable two interfaces up are reached by both")
    void forwardingAndBounded() throws Exception {
        assertAgree(Forwarded.class, "delete", String.class, true);
        assertAgree(Bounded.class, "delete", String.class, true);
    }

    @Test
    @DisplayName("a generic superclass method is reached by both")
    void genericSuperclass() throws Exception {
        assertAgree(Derived.class, "get", String.class, true);
    }

    @Test
    @DisplayName("an inner class's variable bound by its owner type is reached by both")
    void ownerType() throws Exception {
        assertAgree(OwnerBound.class, "take", String.class, true);
    }

    @Test
    @DisplayName("a non-generic override of a generic method is reached by erasure, and different bounds are not")
    void methodTypeParameterRules() throws Exception {
        assertAgree(ErasureOverride.class, "put", new Class<?>[] {String.class, Object.class}, true);
        assertAgree(DifferentBounds.class, "take", new Class<?>[] {Number.class}, false);
        assertAgree(DifferentBounds.class, "take", new Class<?>[] {CharSequence.class}, true);
    }

    @Test
    @DisplayName("a same-erasure overload with another type argument reaches no declaration, in both")
    void sameErasureOverloadIsNotAnOverride() throws Exception {
        assertAgree(SameErasureOverload.class, "purge", java.util.List.class, false);
    }

    @Test
    @DisplayName("a method that declares its own type parameter, and an unrelated overload, are not reached")
    void methodLevelAndUnrelated() throws Exception {
        Method typeParameter = MethodLevel.class.getDeclaredMethod("delete", String.class, int.class);
        assertEquals(false, reachedByPrimitive(MethodLevel.class, typeParameter));
        assertEquals(false, reachedByResolver(MethodLevel.class, typeParameter));
        assertAgree(Unrelated.class, "delete", String.class, false);
        assertAgree(Unrelated.class, "delete", Integer.class, true);
    }

    @Test
    @DisplayName("an inline annotation reached through a package-private chain is not replaced by a type policy")
    void inlineSecurityThroughPackageChainIsNotSilentlyReplaced() throws Exception {
        Method method = PolicyOverChain.class.getDeclaredMethod("m");
        List<Annotation> legacy = AnnotationResolver.resolveMethodAnnotations(method, PolicyOverChain.class);

        assertTrue(
                legacy.stream().anyMatch(a -> a.annotationType() == RolesAllowed.class),
                "the inline walk reaches the secured declaration through the intermediate class");
        assertEquals(
                "RequiresPolicy is mixed with an inline security annotation",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> AccessPolicyResolver.collectMethodAnnotations(
                                        PolicyOverChain.class, method, legacy))
                        .getMessage());
    }
}
