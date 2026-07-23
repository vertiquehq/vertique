// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.codegen.support.MethodOverrides;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link MethodOverrides}.
 *
 * <p>Covers: distinct overloads are not collapsed; a sub-interface override collapses to the
 * most-specific declaration; a method with no duplicate passes through unchanged; genuine siblings
 * (same erased signature declared by unrelated interfaces) are both retained for runtime parity
 * whether their return types are covariant or identical; a same-enclosing-type duplicate (diamond
 * via a common ancestor) collapses to a single representative.
 *
 * <p>All tests use Mockito stubs for {@link javax.lang.model} elements to avoid a dependency on
 * {@code compile-testing} in this module.
 */
@ExtendWith(MockitoExtension.class)
class MethodOverridesTest {

    @Mock
    private Types types;

    // --- erasedSignature ---

    @Nested
    @DisplayName("erasedSignature")
    class ErasedSignatureTests {

        @Test
        @DisplayName("no-parameter method produces name followed by empty parens")
        void noParameters_producesNameWithEmptyParens() {
            ExecutableElement method = mockMethod("doWork", List.of());
            String sig = MethodOverrides.erasedSignature(method, types);
            assertEquals("doWork()", sig);
        }

        @Test
        @DisplayName("single-parameter method includes erased parameter type FQN")
        void singleParameter_includesErasedType() {
            TypeMirror paramType = mock(TypeMirror.class);
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");

            ExecutableElement method = mockMethod("onEvent", List.of(mockParam(paramType)));
            String sig = MethodOverrides.erasedSignature(method, types);
            assertEquals("onEvent(com.example.Payload)", sig);
        }

        @Test
        @DisplayName("multi-parameter method joins erased type FQNs with commas")
        void multipleParameters_joinsWithCommas() {
            TypeMirror typeA = mock(TypeMirror.class);
            TypeMirror typeB = mock(TypeMirror.class);
            when(types.erasure(typeA)).thenReturn(typeA);
            when(types.erasure(typeB)).thenReturn(typeB);
            when(typeA.toString()).thenReturn("com.example.A");
            when(typeB.toString()).thenReturn("com.example.B");

            ExecutableElement method = mockMethod("handle", List.of(mockParam(typeA), mockParam(typeB)));
            String sig = MethodOverrides.erasedSignature(method, types);
            assertEquals("handle(com.example.A,com.example.B)", sig);
        }
    }

    // --- deduplicateByErasedSignature ---

    @Nested
    @DisplayName("deduplicateByErasedSignature")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class DeduplicateTests {

        /** Shared types used across dedup tests. */
        private TypeElement superInterface;

        private TypeElement subInterface;
        private TypeMirror superType;
        private TypeMirror subType;
        private TypeMirror paramType;

        @BeforeEach
        void setUp() {
            // Two interfaces in an override relationship: subInterface extends superInterface.
            superInterface = mock(TypeElement.class);
            subInterface = mock(TypeElement.class);
            superType = mock(TypeMirror.class);
            subType = mock(TypeMirror.class);
            when(superInterface.asType()).thenReturn(superType);
            when(subInterface.asType()).thenReturn(subType);

            // A single parameter type shared by all methods in these tests.
            paramType = mock(TypeMirror.class);
        }

        @Test
        @DisplayName("single method passes through unchanged")
        void singleMethod_passesThroughUnchanged() {
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");

            ExecutableElement method = mockMethodOnType("start", List.of(mockParam(paramType)), subInterface);
            List<ExecutableElement> result = MethodOverrides.deduplicateByErasedSignature(List.of(method), types);

            assertEquals(1, result.size());
            assertEquals(method, result.get(0));
        }

        @Test
        @DisplayName("two methods with distinct erased params are NOT collapsed")
        void distinctOverloads_areNotCollapsed() {
            // start(Payload) — erased as com.example.Payload
            TypeMirror paramTypeA = mock(TypeMirror.class);
            when(types.erasure(paramTypeA)).thenReturn(paramTypeA);
            when(paramTypeA.toString()).thenReturn("com.example.PayloadA");

            // start(OtherPayload) — erased as com.example.OtherPayload
            TypeMirror paramTypeB = mock(TypeMirror.class);
            when(types.erasure(paramTypeB)).thenReturn(paramTypeB);
            when(paramTypeB.toString()).thenReturn("com.example.PayloadB");

            ExecutableElement methodA = mockMethodOnType("start", List.of(mockParam(paramTypeA)), superInterface);
            ExecutableElement methodB = mockMethodOnType("start", List.of(mockParam(paramTypeB)), subInterface);

            List<ExecutableElement> result =
                    MethodOverrides.deduplicateByErasedSignature(List.of(methodA, methodB), types);

            // Different erased params → two distinct signatures → both survive.
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("sub-interface override wins over super-interface declaration")
        void subInterfaceOverride_winsOverSuper() {
            // Both declare start(Payload) with the same erased signature.
            // subInterface extends superInterface → subType is assignable to superType.
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");
            // subType IS assignable to superType (sub-type relationship)
            when(types.isAssignable(subType, superType)).thenReturn(true);

            // superInterface declares start(Payload) first (encountered first in getAllMembers)
            ExecutableElement superDecl = mockMethodOnType("start", List.of(mockParam(paramType)), superInterface);
            // subInterface overrides start(Payload) — encountered second
            ExecutableElement subDecl = mockMethodOnType("start", List.of(mockParam(paramType)), subInterface);

            List<ExecutableElement> result =
                    MethodOverrides.deduplicateByErasedSignature(List.of(superDecl, subDecl), types);

            assertEquals(1, result.size());
            assertEquals(subDecl, result.get(0), "sub-interface override must win");
        }

        @Test
        @DisplayName("super-interface encountered second does not displace sub-interface first")
        void superEncounteredSecond_doesNotDisplaceSubFirst() {
            // Same erased signature. subInterface declaration appears first in the list.
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");
            // subType IS assignable to superType (sub extends super) — so subDecl overrides superDecl
            // regardless of encounter order; superType is NOT assignable to subType.
            when(types.isAssignable(subType, superType)).thenReturn(true);
            when(types.isAssignable(superType, subType)).thenReturn(false);

            ExecutableElement subDecl = mockMethodOnType("start", List.of(mockParam(paramType)), subInterface);
            ExecutableElement superDecl = mockMethodOnType("start", List.of(mockParam(paramType)), superInterface);

            List<ExecutableElement> result =
                    MethodOverrides.deduplicateByErasedSignature(List.of(subDecl, superDecl), types);

            assertEquals(1, result.size());
            assertEquals(subDecl, result.get(0), "sub-interface declaration must be retained");
        }

        @Test
        @DisplayName("covariant siblings (same erased signature, distinct return types, unrelated interfaces) "
                + "are both retained")
        void covariantSiblings_areBothRetained() {
            // Two unrelated interfaces (neither isAssignable to the other — Mockito default false)
            // declare op(Payload) with DISTINCT return types. Class#getMethods() reports both, so the
            // dedup must too — collapsing them would let codegen validate one while the runtime sees two.
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");

            TypeMirror returnA = mock(TypeMirror.class);
            TypeMirror returnB = mock(TypeMirror.class);
            when(types.isSameType(returnA, returnB)).thenReturn(false);
            when(types.isSameType(returnB, returnA)).thenReturn(false);

            ExecutableElement a = mockMethodReturning("op", List.of(mockParam(paramType)), superInterface, returnA);
            ExecutableElement b = mockMethodReturning("op", List.of(mockParam(paramType)), subInterface, returnB);

            List<ExecutableElement> result = MethodOverrides.deduplicateByErasedSignature(List.of(a, b), types);

            assertEquals(2, result.size(), "covariant siblings must both be retained for runtime parity");
        }

        @Test
        @DisplayName("same-erased-signature siblings with the SAME return type are also both retained")
        void sameReturnSiblings_areBothRetained() {
            // Two unrelated interfaces declare op(Payload) with the SAME return type. Class#getMethods()
            // still returns BOTH (verified), and the declarations may carry different annotations, so the
            // dedup must NOT collapse by return-type identity — return type is not a merge criterion.
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");

            TypeMirror sameReturn = mock(TypeMirror.class);

            ExecutableElement a = mockMethodReturning("op", List.of(mockParam(paramType)), superInterface, sameReturn);
            ExecutableElement b = mockMethodReturning("op", List.of(mockParam(paramType)), subInterface, sameReturn);

            List<ExecutableElement> result = MethodOverrides.deduplicateByErasedSignature(List.of(a, b), types);

            assertEquals(2, result.size(), "same-return siblings must both be retained — getMethods() parity");
        }

        @Test
        @DisplayName("same-enclosing-type duplicate (e.g. diamond via common ancestor) collapses to one")
        void sameEnclosingTypeDuplicate_collapsesToOne() {
            // The same method element reported twice from the SAME declaring interface (a getAllMembers
            // duplicate, or a method reached via a diamond through a common ancestor) collapses to one —
            // it is not a genuine sibling.
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");
            when(types.isSameType(superType, superType)).thenReturn(true);

            ExecutableElement first = mockMethodOnType("op", List.of(mockParam(paramType)), superInterface);
            ExecutableElement second = mockMethodOnType("op", List.of(mockParam(paramType)), superInterface);

            List<ExecutableElement> result =
                    MethodOverrides.deduplicateByErasedSignature(List.of(first, second), types);

            assertEquals(1, result.size(), "same-enclosing-type duplicates collapse to one representative");
            assertEquals(first, result.get(0), "the first-encountered representative is retained");
        }

        @Test
        @DisplayName("multiple distinct method names each survive dedup independently")
        void distinctMethodNames_allSurvive() {
            when(types.erasure(paramType)).thenReturn(paramType);
            when(paramType.toString()).thenReturn("com.example.Payload");

            ExecutableElement start = mockMethodOnType("start", List.of(mockParam(paramType)), superInterface);
            ExecutableElement cancel = mockMethodOnType("cancel", List.of(mockParam(paramType)), superInterface);
            ExecutableElement status = mockMethodOnType("status", List.of(), superInterface);

            // paramType for status is never erased — adjust: status has no params
            List<ExecutableElement> result =
                    MethodOverrides.deduplicateByErasedSignature(List.of(start, cancel, status), types);

            assertEquals(3, result.size());
            assertNotNull(result.stream()
                    .filter(m -> nameOf(m).equals("start"))
                    .findFirst()
                    .orElse(null));
            assertNotNull(result.stream()
                    .filter(m -> nameOf(m).equals("cancel"))
                    .findFirst()
                    .orElse(null));
            assertNotNull(result.stream()
                    .filter(m -> nameOf(m).equals("status"))
                    .findFirst()
                    .orElse(null));
        }

        @Test
        @DisplayName("encounter order is preserved for methods with distinct signatures")
        void encounterOrder_isPreserved() {
            TypeMirror paramTypeA = mock(TypeMirror.class);
            TypeMirror paramTypeB = mock(TypeMirror.class);
            when(types.erasure(paramTypeA)).thenReturn(paramTypeA);
            when(types.erasure(paramTypeB)).thenReturn(paramTypeB);
            when(paramTypeA.toString()).thenReturn("com.example.A");
            when(paramTypeB.toString()).thenReturn("com.example.B");

            ExecutableElement first = mockMethodOnType("alpha", List.of(mockParam(paramTypeA)), superInterface);
            ExecutableElement second = mockMethodOnType("beta", List.of(mockParam(paramTypeB)), superInterface);
            ExecutableElement third = mockMethodOnType("gamma", List.of(), superInterface);

            List<ExecutableElement> result =
                    MethodOverrides.deduplicateByErasedSignature(List.of(first, second, third), types);

            assertEquals(3, result.size());
            assertEquals(first, result.get(0));
            assertEquals(second, result.get(1));
            assertEquals(third, result.get(2));
        }
    }

    // --- helpers ---

    private static String nameOf(ExecutableElement method) {
        return method.getSimpleName().toString();
    }

    /**
     * Creates a mock {@link ExecutableElement} with the given name and parameters, not bound to
     * any enclosing type.
     *
     * @param name       the simple name of the method
     * @param parameters the parameter elements
     * @return the mock method element
     */
    private static ExecutableElement mockMethod(String name, List<VariableElement> parameters) {
        ExecutableElement method = mock(ExecutableElement.class);
        Name nameObj = mock(Name.class);
        when(nameObj.toString()).thenReturn(name);
        when(method.getSimpleName()).thenReturn(nameObj);
        // Use doReturn to avoid wildcard capture inference issues (getParameters returns
        // List<? extends VariableElement>).
        doReturn(parameters).when(method).getParameters();
        return method;
    }

    /**
     * Creates a mock {@link ExecutableElement} bound to the given enclosing {@link TypeElement}.
     *
     * @param name          the simple name of the method
     * @param parameters    the parameter elements
     * @param enclosingType the type that declares the method
     * @return the mock method element
     */
    private static ExecutableElement mockMethodOnType(
            String name, List<VariableElement> parameters, TypeElement enclosingType) {
        ExecutableElement method = mockMethod(name, parameters);
        when(method.getEnclosingElement()).thenReturn(enclosingType);
        return method;
    }

    /**
     * Creates a mock {@link ExecutableElement} bound to the given enclosing {@link TypeElement} and
     * declaring the given return type — used by the sibling tests, where return-type identity decides
     * whether two same-erased-signature methods merge (identical) or are both retained (covariant).
     *
     * @param name          the simple name of the method
     * @param parameters    the parameter elements
     * @param enclosingType the type that declares the method
     * @param returnType    the declared return type
     * @return the mock method element
     */
    private static ExecutableElement mockMethodReturning(
            String name, List<VariableElement> parameters, TypeElement enclosingType, TypeMirror returnType) {
        ExecutableElement method = mockMethodOnType(name, parameters, enclosingType);
        when(method.getReturnType()).thenReturn(returnType);
        return method;
    }

    /**
     * Creates a mock {@link VariableElement} (parameter) with the given type mirror.
     *
     * @param type the declared type of the parameter
     * @return the mock parameter element
     */
    private static VariableElement mockParam(TypeMirror type) {
        VariableElement param = mock(VariableElement.class);
        when(param.asType()).thenReturn(type);
        return param;
    }
}
