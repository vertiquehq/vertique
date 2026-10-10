// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnnotationResolver#inheritedDeclarations(Method, Class)}: which declarations
 * a method overrides once the type variables of its hierarchy are bound.
 */
class InheritedDeclarationsTest {

    // --- Fixtures ---

    interface Crud<ID> {
        void delete(ID id);

        void delete(ID id, boolean force);
    }

    static class Users implements Crud<String> {
        @Override
        public void delete(String id) {}

        @Override
        public void delete(String id, boolean force) {}
    }

    interface StringCrud extends Crud<String> {}

    static class Forwarded implements StringCrud {
        @Override
        public void delete(String id) {}

        @Override
        public void delete(String id, boolean force) {}
    }

    interface Mid<T extends CharSequence> extends Crud<T> {}

    static class Bounded implements Mid<String> {
        @Override
        public void delete(String id) {}

        @Override
        public void delete(String id, boolean force) {}
    }

    static class Base<T> {
        public void get(T value) {}
    }

    static class Derived extends Base<String> {
        @Override
        public void get(String value) {}
    }

    static class Deeper extends Derived {
        @Override
        public void get(String value) {}
    }

    static class ForwardingBase<T> extends Base<T> {}

    static class ForwardedDerived extends ForwardingBase<Integer> {
        @Override
        public void get(Integer value) {}
    }

    static class Outer<T> {
        public class Inner {
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

    interface Plain {
        void run(String value);
    }

    static class PlainImpl implements Plain {
        @Override
        public void run(String value) {}
    }

    interface Same<A> {
        void put(A value);

        void put(Integer value);
    }

    static class SameImpl implements Same<String> {
        @Override
        public void put(String value) {}

        @Override
        public void put(Integer value) {}
    }

    static class MethodLevel implements Crud<String> {
        @Override
        public void delete(String id) {}

        @Override
        public void delete(String id, boolean force) {}

        public <X extends String> void delete(X id, int times) {}
    }

    interface GenericDefault<ID> {
        default void remove(ID id) {}
    }

    interface StringDefault extends GenericDefault<String> {
        @Override
        default void remove(String id) {}
    }

    static class ProtectedBase<T> {
        protected void touch(T value) {}
    }

    static class ProtectedDerived extends ProtectedBase<String> {
        @Override
        protected void touch(String value) {}
    }

    static class ClassAndInterface extends Base<String> implements Crud<String> {
        @Override
        public void get(String value) {}

        @Override
        public void delete(String id) {}

        @Override
        public void delete(String id, boolean force) {}
    }

    // --- Helpers ---

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        return type.getDeclaredMethod(name, parameters);
    }

    private static List<String> ownerSignatures(List<Method> declarations) {
        return declarations.stream()
                .map(m -> m.getDeclaringClass().getSimpleName() + "." + m.getName()
                        + java.util.Arrays.stream(m.getParameterTypes())
                                .map(Class::getSimpleName)
                                .toList())
                .toList();
    }

    // --- Tests ---

    @Test
    @DisplayName("a class override of a generic interface method resolves the interface declaration")
    void genericInterfaceMethod() throws NoSuchMethodException {
        Method override = method(Users.class, "delete", String.class);

        List<Method> inherited = AnnotationResolver.inheritedDeclarations(override, Users.class);

        assertEquals(List.of("Crud.delete[Object]"), ownerSignatures(inherited));
    }

    @Test
    @DisplayName("an overload with another arity maps to its own declaration only")
    void arityKeepsOverloadsApart() throws NoSuchMethodException {
        Method override = method(Users.class, "delete", String.class, boolean.class);

        List<Method> inherited = AnnotationResolver.inheritedDeclarations(override, Users.class);

        assertEquals(List.of("Crud.delete[Object, boolean]"), ownerSignatures(inherited));
    }

    @Test
    @DisplayName("a forwarding sub-interface binds the variable through the chain")
    void forwardingInterface() throws NoSuchMethodException {
        Method override = method(Forwarded.class, "delete", String.class);

        assertEquals(
                List.of("Crud.delete[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(override, Forwarded.class)));
    }

    @Test
    @DisplayName("a bounded variable bound two interfaces up resolves")
    void boundedVariableAtDepthTwo() throws NoSuchMethodException {
        Method override = method(Bounded.class, "delete", String.class);

        assertEquals(
                List.of("Crud.delete[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(override, Bounded.class)));
    }

    @Test
    @DisplayName("a generic superclass method is found, and a deeper override sees both superclasses")
    void genericSuperclass() throws NoSuchMethodException {
        Method derived = method(Derived.class, "get", String.class);
        Method deeper = method(Deeper.class, "get", String.class);

        assertEquals(
                List.of("Base.get[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(derived, Derived.class)));
        assertEquals(
                List.of("Derived.get[String]", "Base.get[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(deeper, Deeper.class)));
    }

    @Test
    @DisplayName("a variable forwarded through an intermediate generic superclass resolves")
    void forwardedSuperclassVariable() throws NoSuchMethodException {
        Method override = method(ForwardedDerived.class, "get", Integer.class);

        assertEquals(
                List.of("Base.get[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(override, ForwardedDerived.class)));
    }

    @Test
    @DisplayName("the argument of an owner type binds the inner class's variable")
    void ownerTypeArgument() throws NoSuchMethodException {
        Method override = method(OwnerBound.class, "take", String.class);

        assertEquals(
                List.of("Inner.take[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(override, OwnerBound.class)));
    }

    @Test
    @DisplayName("an exact erased match is unchanged")
    void exactMatchStillResolves() throws NoSuchMethodException {
        Method override = method(PlainImpl.class, "run", String.class);

        assertEquals(
                List.of("Plain.run[String]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(override, PlainImpl.class)));
    }

    @Test
    @DisplayName("an unrelated same-arity overload is not merged")
    void unrelatedOverload() throws NoSuchMethodException {
        Method generic = method(SameImpl.class, "put", String.class);
        Method concrete = method(SameImpl.class, "put", Integer.class);

        assertEquals(
                List.of("Same.put[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(generic, SameImpl.class)));
        assertEquals(
                List.of("Same.put[Integer]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(concrete, SameImpl.class)));
    }

    @Test
    @DisplayName("a method that declares its own type parameter overrides nothing")
    void methodLevelTypeParameter() throws NoSuchMethodException {
        Method generic = method(MethodLevel.class, "delete", String.class, int.class);

        assertTrue(AnnotationResolver.inheritedDeclarations(generic, MethodLevel.class)
                .isEmpty());
    }

    @Test
    @DisplayName("an interface default overriding a generic default resolves from the declaring interface")
    void defaultOverridingGenericDefault() throws NoSuchMethodException {
        Method override = method(StringDefault.class, "remove", String.class);

        assertEquals(
                List.of("GenericDefault.remove[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(override, StringDefault.class)));
    }

    @Test
    @DisplayName("members that are not public stay unseen, as for the exact lookup")
    void nonPublicMembersAreNotSeen() throws NoSuchMethodException {
        Method override = method(ProtectedDerived.class, "touch", String.class);

        assertTrue(AnnotationResolver.inheritedDeclarations(override, ProtectedDerived.class)
                .isEmpty());
    }

    @Test
    @DisplayName("superclasses come first, then interfaces, each declaration once")
    void walkOrder() throws NoSuchMethodException {
        Method delete = method(ClassAndInterface.class, "delete", String.class);
        Method get = method(ClassAndInterface.class, "get", String.class);

        assertEquals(
                List.of("Crud.delete[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(delete, ClassAndInterface.class)));
        assertEquals(
                List.of("Base.get[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(get, ClassAndInterface.class)));
    }
}
