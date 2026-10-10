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

    interface MethodGeneric<T> {
        <X> void put(T value, X extra);
    }

    static class ErasureOverride implements MethodGeneric<String> {
        @Override
        public void put(String value, Object extra) {}
    }

    interface BoundedMethod<T> {
        <X extends Number> void put(T value, X extra);
    }

    static class BoundedErasure implements BoundedMethod<String> {
        @Override
        public void put(String value, Number extra) {}
    }

    interface BoundByClassVariable<T> {
        <X extends T> void take(X value);
    }

    static class ByClassVariable implements BoundByClassVariable<String> {
        @Override
        public void take(String value) {}
    }

    static class GenericMethodBase<T> {
        public <X> void put(T value, X extra) {}
    }

    static class GenericMethodDerived extends GenericMethodBase<String> {
        @Override
        public void put(String value, Object extra) {}
    }

    interface CharSequenceBound {
        <U extends CharSequence> void take(U value);
    }

    static class DifferentBounds implements CharSequenceBound {
        public <T extends Number> void take(T value) {}

        @Override
        public <U extends CharSequence> void take(U value) {}
    }

    interface BothApi<T> {
        void both(T value);
    }

    static class BothBase<T> {
        public void both(T value) {}
    }

    static class BothImpl extends BothBase<String> implements BothApi<String> {
        @Override
        public void both(String value) {}
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
    @DisplayName("a non-generic override of a generic method overrides it by erasure")
    void nonGenericOverrideOfGenericMethod() throws NoSuchMethodException {
        Method plain = method(ErasureOverride.class, "put", String.class, Object.class);
        Method bounded = method(BoundedErasure.class, "put", String.class, Number.class);
        Method byClass = method(ByClassVariable.class, "take", String.class);
        Method superclass = method(GenericMethodDerived.class, "put", String.class, Object.class);

        assertEquals(
                List.of("MethodGeneric.put[Object, Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(plain, ErasureOverride.class)));
        assertEquals(
                List.of("BoundedMethod.put[Object, Number]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(bounded, BoundedErasure.class)));
        assertEquals(
                List.of("BoundByClassVariable.take[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(byClass, ByClassVariable.class)));
        assertEquals(
                List.of("GenericMethodBase.put[Object, Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(superclass, GenericMethodDerived.class)));
    }

    @Test
    @DisplayName("generic methods with different bounds are unrelated overloads")
    void differentBoundsAreUnrelated() throws NoSuchMethodException {
        Method number = method(DifferentBounds.class, "take", Number.class);
        Method charSequence = method(DifferentBounds.class, "take", CharSequence.class);

        assertTrue(AnnotationResolver.inheritedDeclarations(number, DifferentBounds.class)
                .isEmpty());
        assertEquals(
                List.of("CharSequenceBound.take[CharSequence]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(charSequence, DifferentBounds.class)));
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
    @DisplayName("a declaration on both a superclass and an interface lists the superclass first")
    void superclassBeforeInterface() throws NoSuchMethodException {
        Method both = method(BothImpl.class, "both", String.class);

        assertEquals(
                List.of("BothBase.both[Object]", "BothApi.both[Object]"),
                ownerSignatures(AnnotationResolver.inheritedDeclarations(both, BothImpl.class)));
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
