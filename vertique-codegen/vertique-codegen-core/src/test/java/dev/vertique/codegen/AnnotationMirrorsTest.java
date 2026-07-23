// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AnnotationMirrors}.
 */
@ExtendWith(MockitoExtension.class)
class AnnotationMirrorsTest {

    @Mock
    private Elements elements;

    private AnnotationMirrors annotationMirrors;

    @Retention(RetentionPolicy.RUNTIME)
    @interface SampleAnnotation {
        String value() default "";

        int count() default 0;

        Class<?> clazz() default Object.class;
    }

    @BeforeEach
    void setUp() {
        annotationMirrors = new AnnotationMirrors(elements);
    }

    // --- find ---

    @Test
    void find_returnsEmptyWhenAnnotationAbsent() {
        Element element = mock(Element.class);
        doReturn(List.of()).when(element).getAnnotationMirrors();

        Optional<AnnotationMirror> result = annotationMirrors.find(element, SampleAnnotation.class);

        assertFalse(result.isPresent());
    }

    @Test
    void find_returnsMirrorWhenAnnotationPresent() {
        Element element = mock(Element.class);
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        DeclaredType annoType = mock(DeclaredType.class);
        TypeElement annoElement = mock(TypeElement.class);
        Name annoName = mock(Name.class);

        when(mirror.getAnnotationType()).thenReturn(annoType);
        when(annoType.asElement()).thenReturn(annoElement);
        when(annoElement.getQualifiedName()).thenReturn(annoName);
        when(annoName.toString()).thenReturn(SampleAnnotation.class.getName());
        doReturn(List.of(mirror)).when(element).getAnnotationMirrors();

        Optional<AnnotationMirror> result = annotationMirrors.find(element, SampleAnnotation.class);

        assertTrue(result.isPresent());
        assertEquals(mirror, result.get());
    }

    // --- attribute ---

    @Test
    void attribute_extractsStringValue() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        AnnotationValue value = mock(AnnotationValue.class);
        when(value.getValue()).thenReturn("hello");

        var executableElement = mock(javax.lang.model.element.ExecutableElement.class);
        var execName = mock(Name.class);
        when(execName.toString()).thenReturn("value");
        when(executableElement.getSimpleName()).thenReturn(execName);

        doReturn(Map.of(executableElement, value)).when(elements).getElementValuesWithDefaults(mirror);

        Optional<String> result = annotationMirrors.attribute(mirror, "value", String.class);

        assertTrue(result.isPresent());
        assertEquals("hello", result.get());
    }

    @Test
    void attribute_extractsIntegerValue() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        AnnotationValue value = mock(AnnotationValue.class);
        when(value.getValue()).thenReturn(42);

        var executableElement = mock(javax.lang.model.element.ExecutableElement.class);
        var execName = mock(Name.class);
        when(execName.toString()).thenReturn("count");
        when(executableElement.getSimpleName()).thenReturn(execName);

        doReturn(Map.of(executableElement, value)).when(elements).getElementValuesWithDefaults(mirror);

        Optional<Integer> result = annotationMirrors.attribute(mirror, "count", Integer.class);

        assertTrue(result.isPresent());
        assertEquals(42, result.get());
    }

    @Test
    void attribute_returnsEmptyWhenAttributeAbsent() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        doReturn(Map.of()).when(elements).getElementValuesWithDefaults(mirror);

        Optional<String> result = annotationMirrors.attribute(mirror, "missing", String.class);

        assertFalse(result.isPresent());
    }

    @Test
    void attribute_returnsEmptyWhenTypeMismatch() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        AnnotationValue value = mock(AnnotationValue.class);
        when(value.getValue()).thenReturn("not-an-int");

        var executableElement = mock(javax.lang.model.element.ExecutableElement.class);
        var execName = mock(Name.class);
        when(execName.toString()).thenReturn("count");
        when(executableElement.getSimpleName()).thenReturn(execName);

        doReturn(Map.of(executableElement, value)).when(elements).getElementValuesWithDefaults(mirror);

        Optional<Integer> result = annotationMirrors.attribute(mirror, "count", Integer.class);

        assertFalse(result.isPresent());
    }

    // --- attributeArray ---

    @Test
    void attributeArray_returnsEmptyListWhenAbsent() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        doReturn(Map.of()).when(elements).getElementValuesWithDefaults(mirror);

        List<AnnotationValue> result = annotationMirrors.attributeArray(mirror, "items");

        assertTrue(result.isEmpty());
    }

    @Test
    void attributeArray_returnsListOfValuesForArrayAttribute() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        AnnotationValue arrayValue = mock(AnnotationValue.class);
        AnnotationValue elem1 = mock(AnnotationValue.class);
        AnnotationValue elem2 = mock(AnnotationValue.class);
        doReturn(List.of(elem1, elem2)).when(arrayValue).getValue();

        var executableElement = mock(javax.lang.model.element.ExecutableElement.class);
        var execName = mock(Name.class);
        when(execName.toString()).thenReturn("items");
        when(executableElement.getSimpleName()).thenReturn(execName);

        doReturn(Map.of(executableElement, arrayValue)).when(elements).getElementValuesWithDefaults(mirror);

        List<AnnotationValue> result = annotationMirrors.attributeArray(mirror, "items");

        assertEquals(2, result.size());
        assertEquals(elem1, result.get(0));
        assertEquals(elem2, result.get(1));
    }

    // --- attributeClass ---

    @Test
    void attributeClass_returnsEmptyWhenAbsent() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        doReturn(Map.of()).when(elements).getElementValuesWithDefaults(mirror);

        Optional<TypeMirror> result = annotationMirrors.attributeClass(mirror, "clazz");

        assertFalse(result.isPresent());
    }

    @Test
    void attributeClass_extractsTypeMirrorFromAnnotationValue() {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        AnnotationValue value = mock(AnnotationValue.class);
        TypeMirror typeMirror = mock(TypeMirror.class);
        when(value.getValue()).thenReturn(typeMirror);

        var executableElement = mock(javax.lang.model.element.ExecutableElement.class);
        var execName = mock(Name.class);
        when(execName.toString()).thenReturn("clazz");
        when(executableElement.getSimpleName()).thenReturn(execName);

        doReturn(Map.of(executableElement, value)).when(elements).getElementValuesWithDefaults(mirror);

        Optional<TypeMirror> result = annotationMirrors.attributeClass(mirror, "clazz");

        assertTrue(result.isPresent());
        assertEquals(typeMirror, result.get());
    }
}
