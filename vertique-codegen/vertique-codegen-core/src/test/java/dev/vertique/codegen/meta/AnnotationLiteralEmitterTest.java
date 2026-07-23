// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.vertique.codegen.meta.AnnotationLiteralEmitter.UnsupportedAttribute;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link AnnotationLiteralEmitter}'s rejection / backstop parity (P2R2-W2).
 *
 * <p>{@code firstUnsupportedAttribute} rejects nested-annotation members and arrays of them; the
 * helper's documented contract is that {@code firstUnsupportedAttribute} ⟺ what the emit / guard
 * path refuses. These tests prove the parity for the <em>array-of-nested-annotation</em> shape: the
 * pre-check flags it, and the emit-time backstop ({@code arrayComponentGuard}, reached even on the
 * empty-array path) rejects it loudly rather than silently emitting a {@code new NestedAnn[0]}
 * literal.
 *
 * <p>Following the codegen-core convention (see {@code MetadataEmitterTest}), all
 * {@link javax.lang.model} elements are Mockito stubs — this module deliberately avoids a dependency
 * on {@code compile-testing}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnotationLiteralEmitterTest {

    @Test
    @DisplayName("firstUnsupportedAttribute flags an array-of-nested-annotation member")
    void firstUnsupportedAttributeFlagsNestedAnnotationArray() {
        ArrayType nestedArray = mockNestedAnnotationArray();
        ExecutableElement member = mockMember("nestedList", nestedArray);
        TypeElement annotationType = mockAnnotationTypeWithMembers(List.of(member));

        Optional<UnsupportedAttribute> result = AnnotationLiteralEmitter.firstUnsupportedAttribute(annotationType);

        assertTrue(result.isPresent(), "an array-of-nested-annotation member must be flagged as unsupported");
        assertTrue(
                result.get().member().equals("nestedList"),
                "the flagged member must be the nested-annotation array member 'nestedList', got: " + result.get());
    }

    @Test
    @DisplayName("the emit/guard path rejects an empty array-of-nested-annotation member (parity with the pre-check)")
    void emitPathRejectsNestedAnnotationArray() {
        ArrayType nestedArray = mockNestedAnnotationArray();
        ExecutableElement member = mockMember("nestedList", nestedArray);

        AnnotationMirror mirror = mock(AnnotationMirror.class);
        AnnotationValue emptyArrayValue = mock(AnnotationValue.class);
        // An array member's AnnotationValue.getValue() is the (here empty) List<? extends AnnotationValue>.
        lenient().when(emptyArrayValue.getValue()).thenReturn(List.of());

        Elements elements = mock(Elements.class);
        lenient()
                .doReturn(java.util.Map.of(member, emptyArrayValue))
                .when(elements)
                .getElementValuesWithDefaults(mirror);

        Types types = mock(Types.class);

        // The emit/guard path (constructorArgs -> arrayLiteral -> arrayComponentGuard, reached even on
        // the empty-array branch) must refuse the nested-annotation component, matching the pre-check.
        assertThrows(
                UnsupportedOperationException.class,
                () -> AnnotationLiteralEmitter.constructorArgs(mirror, elements, types),
                "the emit path must reject an array-of-nested-annotation member, not silently emit new NestedAnn[0]");
    }

    // --- helpers (Mockito-stubbed javax.lang.model elements) ---

    /**
     * Builds a mock {@link ArrayType} whose component is a nested-annotation declared type (its
     * element's kind is {@link ElementKind#ANNOTATION_TYPE}).
     *
     * @return the mock array type of a nested annotation
     */
    private static ArrayType mockNestedAnnotationArray() {
        Element nestedElement = mock(Element.class);
        lenient().when(nestedElement.getKind()).thenReturn(ElementKind.ANNOTATION_TYPE);

        DeclaredType nested = mock(DeclaredType.class);
        lenient().when(nested.getKind()).thenReturn(TypeKind.DECLARED);
        lenient().when(nested.asElement()).thenReturn(nestedElement);

        ArrayType array = mock(ArrayType.class);
        lenient().when(array.getKind()).thenReturn(TypeKind.ARRAY);
        lenient().when(array.getComponentType()).thenReturn(nested);
        return array;
    }

    /**
     * Builds a mock annotation-member {@link ExecutableElement} with the given name and return type.
     *
     * @param name       the member simple name
     * @param returnType the member return type mirror
     * @return the mock member
     */
    private static ExecutableElement mockMember(String name, TypeMirror returnType) {
        ExecutableElement member = mock(ExecutableElement.class);
        lenient().when(member.getKind()).thenReturn(ElementKind.METHOD);
        lenient().when(member.getReturnType()).thenReturn(returnType);
        lenient().when(member.getSimpleName()).thenReturn(mockName(name));
        return member;
    }

    /**
     * Builds a mock annotation {@link TypeElement} whose enclosed elements are the given members so
     * {@code ElementFilter.methodsIn(...)} yields them.
     *
     * @param members the annotation's member methods
     * @return the mock annotation type element
     */
    private static TypeElement mockAnnotationTypeWithMembers(List<ExecutableElement> members) {
        TypeElement annotationType = mock(TypeElement.class);
        org.mockito.Mockito.doReturn(members).when(annotationType).getEnclosedElements();
        return annotationType;
    }

    /**
     * Creates a mock {@link Name} whose {@code toString()} yields the given value.
     *
     * @param value the name value
     * @return the mock name
     */
    private static Name mockName(String value) {
        return mock(
                Name.class,
                invocation -> "toString".equals(invocation.getMethod().getName())
                        ? value
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }
}
