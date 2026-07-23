// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TypeResolver}.
 */
@ExtendWith(MockitoExtension.class)
class TypeResolverTest {

    @Mock
    private Types types;

    @Mock
    private Elements elements;

    private TypeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TypeResolver(types, elements);
    }

    // --- directSupertypes ---

    @Test
    void directSupertypes_nullSafe_returnsEmptyStream() {
        Stream<TypeMirror> result = resolver.directSupertypes(null);
        assertNotNull(result);
        assertEquals(0, result.count());
    }

    @Test
    void directSupertypes_delegatesToTypesUtil() {
        DeclaredType type = mock(DeclaredType.class);
        DeclaredType supertype = mock(DeclaredType.class);
        // Use doReturn to avoid wildcard capture inference issues
        doReturn(List.of(supertype)).when(types).directSupertypes(type);

        List<TypeMirror> result = resolver.directSupertypes(type).toList();

        assertEquals(1, result.size());
        assertEquals(supertype, result.get(0));
    }

    // --- allSupertypes BFS dedup ---

    @Test
    void allSupertypes_includesStartingType() {
        DeclaredType type = mock(DeclaredType.class);
        when(type.getKind()).thenReturn(TypeKind.DECLARED);
        when(types.erasure(type)).thenReturn(type);
        doReturn(List.of()).when(types).directSupertypes(type);

        List<TypeMirror> result = resolver.allSupertypes(type).toList();

        assertTrue(result.contains(type), "Result must include the starting type itself");
    }

    @Test
    void allSupertypes_deduplicatesByErasure() {
        // Build a diamond: type -> A, type -> B -> A (A appears from two paths)
        DeclaredType type = mock(DeclaredType.class);
        DeclaredType typeA = mock(DeclaredType.class);
        DeclaredType typeB = mock(DeclaredType.class);

        when(type.getKind()).thenReturn(TypeKind.DECLARED);
        when(typeA.getKind()).thenReturn(TypeKind.DECLARED);
        when(typeB.getKind()).thenReturn(TypeKind.DECLARED);

        // Each erasure call returns itself for simplicity
        when(types.erasure(type)).thenReturn(type);
        when(types.erasure(typeA)).thenReturn(typeA);
        when(types.erasure(typeB)).thenReturn(typeB);

        doReturn(List.of(typeA, typeB)).when(types).directSupertypes(type);
        doReturn(List.of()).when(types).directSupertypes(typeA);
        doReturn(List.of(typeA)).when(types).directSupertypes(typeB); // A reachable again

        List<TypeMirror> result = resolver.allSupertypes(type).toList();

        // typeA should appear exactly once despite being reachable from two paths
        long countA = result.stream().filter(t -> t == typeA).count();
        assertEquals(1, countA, "typeA should appear exactly once (BFS dedup)");
    }

    // --- resolveTypeArgument ---

    @Test
    void resolveTypeArgument_findsArgForDirectImplementation() {
        // Simulate: class Foo implements Bar<String>
        // subject = Foo (erased), targetInterface = Bar
        DeclaredType foo = mock(DeclaredType.class);
        DeclaredType barOfString = mock(DeclaredType.class);
        DeclaredType barErased = mock(DeclaredType.class);
        DeclaredType stringType = mock(DeclaredType.class);

        TypeElement barElement = mock(TypeElement.class);

        // Bar<String> has one type arg: String (a DECLARED type)
        List<? extends TypeMirror> typeArgs = new ArrayList<>(List.of(stringType));
        doReturn(typeArgs).when(barOfString).getTypeArguments();
        when(stringType.getKind()).thenReturn(TypeKind.DECLARED);

        // Erasure of Bar<String> = barErased, which matches barElement.asType()
        when(types.erasure(barOfString)).thenReturn(barErased);
        when(barElement.asType()).thenReturn(barErased);
        when(types.erasure(barErased)).thenReturn(barErased);

        // Foo's direct supertypes: [Bar<String>]
        when(foo.getKind()).thenReturn(TypeKind.DECLARED);
        when(types.erasure(foo)).thenReturn(foo);
        doReturn(List.of(barOfString)).when(types).directSupertypes(foo);
        when(barOfString.getKind()).thenReturn(TypeKind.DECLARED);
        doReturn(List.of()).when(types).directSupertypes(barOfString);

        Optional<TypeMirror> result = resolver.resolveTypeArgument(foo, barElement, 0);

        assertTrue(result.isPresent(), "Should resolve type argument from direct implementation");
        assertEquals(stringType, result.get());
    }

    @Test
    void resolveTypeArgument_returnsEmptyForUnboundTypeVar() {
        // Simulate: class Foo<T> implements Bar<T> — type argument is a type variable
        DeclaredType foo = mock(DeclaredType.class);
        DeclaredType barOfT = mock(DeclaredType.class);
        DeclaredType barErased = mock(DeclaredType.class);
        TypeVariable typeVar = mock(TypeVariable.class);

        TypeElement barElement = mock(TypeElement.class);
        List<? extends TypeMirror> typeArgs = new ArrayList<>(List.of(typeVar));
        doReturn(typeArgs).when(barOfT).getTypeArguments();
        when(typeVar.getKind()).thenReturn(TypeKind.TYPEVAR); // not DECLARED — unresolvable

        when(types.erasure(barOfT)).thenReturn(barErased);
        when(barElement.asType()).thenReturn(barErased);
        when(types.erasure(barErased)).thenReturn(barErased);

        when(foo.getKind()).thenReturn(TypeKind.DECLARED);
        when(types.erasure(foo)).thenReturn(foo);
        doReturn(List.of(barOfT)).when(types).directSupertypes(foo);
        when(barOfT.getKind()).thenReturn(TypeKind.DECLARED);
        doReturn(List.of()).when(types).directSupertypes(barOfT);

        Optional<TypeMirror> result = resolver.resolveTypeArgument(foo, barElement, 0);

        assertFalse(result.isPresent(), "Unbound type variable should return empty");
    }

    @Test
    void resolveTypeArgument_returnsEmptyWhenTargetNotInHierarchy() {
        DeclaredType foo = mock(DeclaredType.class);
        TypeElement barElement = mock(TypeElement.class);
        DeclaredType barErased = mock(DeclaredType.class);

        when(foo.getKind()).thenReturn(TypeKind.DECLARED);
        when(types.erasure(foo)).thenReturn(foo);
        doReturn(List.of()).when(types).directSupertypes(foo); // No supertypes at all
        when(barElement.asType()).thenReturn(barErased);
        when(types.erasure(barErased)).thenReturn(barErased);

        Optional<TypeMirror> result = resolver.resolveTypeArgument(foo, barElement, 0);

        assertFalse(result.isPresent());
    }

    // --- isAssignable ---

    @Test
    void isAssignable_delegatesToTypesWithErasure() {
        DeclaredType sub = mock(DeclaredType.class);
        DeclaredType superType = mock(DeclaredType.class);
        TypeElement superElement = mock(TypeElement.class);

        when(elements.getTypeElement("java.lang.CharSequence")).thenReturn(superElement);
        when(superElement.asType()).thenReturn(superType);
        when(types.erasure(superType)).thenReturn(superType);
        when(types.erasure(sub)).thenReturn(sub);
        when(types.isAssignable(sub, superType)).thenReturn(true);

        assertTrue(resolver.isAssignable(sub, CharSequence.class));
    }

    @Test
    void isAssignable_returnsFalseWhenNotAssignable() {
        DeclaredType sub = mock(DeclaredType.class);
        DeclaredType superType = mock(DeclaredType.class);
        TypeElement superElement = mock(TypeElement.class);

        when(elements.getTypeElement("java.lang.Integer")).thenReturn(superElement);
        when(superElement.asType()).thenReturn(superType);
        when(types.erasure(superType)).thenReturn(superType);
        when(types.erasure(sub)).thenReturn(sub);
        when(types.isAssignable(sub, superType)).thenReturn(false);

        assertFalse(resolver.isAssignable(sub, Integer.class));
    }
}
