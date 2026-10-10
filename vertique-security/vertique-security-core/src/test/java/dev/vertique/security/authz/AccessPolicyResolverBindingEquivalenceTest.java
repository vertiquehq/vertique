// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.util.AnnotationResolver;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@link AccessPolicyResolver}, which binds type variables with its own walk, and
 * {@link AnnotationResolver#inheritedDeclarations}, which the inline-annotation path uses, agree on
 * which declarations an override reaches. The two binders are separate code; this is the test that
 * keeps them from drifting apart, in particular for the owner-type and method-type-parameter shapes
 * where a binder is easiest to get wrong.
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

    static class Unrelated implements Crud<Integer> {
        @Override
        public void delete(Integer id) {}

        public void delete(String id) {}
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
        Method method = consumer.getDeclaredMethod(name, parameter);
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
    @DisplayName("a method that declares its own type parameter, and an unrelated overload, are not reached")
    void methodLevelAndUnrelated() throws Exception {
        Method typeParameter = MethodLevel.class.getDeclaredMethod("delete", String.class, int.class);
        assertEquals(false, reachedByPrimitive(MethodLevel.class, typeParameter));
        assertEquals(false, reachedByResolver(MethodLevel.class, typeParameter));
        assertAgree(Unrelated.class, "delete", String.class, false);
        assertAgree(Unrelated.class, "delete", Integer.class, true);
    }
}
