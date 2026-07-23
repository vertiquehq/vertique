// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Constructors#findInjectConstructors(TypeElement)}.
 *
 * <p>Uses Mockito to simulate the APT element model so that the helper can be verified
 * without launching a real compilation.
 */
class ConstructorsTest {

    // --- Helpers ---

    /** Builds a mock constructor element with the given annotation FQNs. */
    private static ExecutableElement mockConstructor(String... annotationFqns) {
        ExecutableElement ctor = mock(ExecutableElement.class);
        when(ctor.getKind()).thenReturn(ElementKind.CONSTRUCTOR);

        List<AnnotationMirror> mirrors = new java.util.ArrayList<>();
        for (String fqn : annotationFqns) {
            mirrors.add(mockAnnotationMirror(fqn));
        }
        doReturn(mirrors).when(ctor).getAnnotationMirrors();
        return ctor;
    }

    /** Builds a mock annotation mirror with the given FQN. */
    private static AnnotationMirror mockAnnotationMirror(String fqn) {
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        DeclaredType dt = mock(DeclaredType.class);
        TypeElement annoElement = mock(TypeElement.class);
        Name name = mock(Name.class);
        when(mirror.getAnnotationType()).thenReturn(dt);
        when(dt.asElement()).thenReturn(annoElement);
        when(annoElement.getQualifiedName()).thenReturn(name);
        when(name.toString()).thenReturn(fqn);
        return mirror;
    }

    /** Builds a mock TypeElement whose enclosed elements are the given list. */
    private static TypeElement mockType(List<ExecutableElement> constructors) {
        TypeElement type = mock(TypeElement.class);
        // ElementFilter.constructorsIn filters by CONSTRUCTOR kind; provide mix with a field.
        ExecutableElement field = mock(ExecutableElement.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        // Use raw cast to satisfy doReturn on the untyped enclosed-elements list
        @SuppressWarnings({"unchecked", "rawtypes"})
        List allElements = new java.util.ArrayList<>(constructors);
        allElements.add(field);
        doReturn(allElements).when(type).getEnclosedElements();
        return type;
    }

    // --- Tests ---

    @Test
    @DisplayName("returns one constructor when a single @jakarta.inject.Inject is present")
    void singleJakartaInjectConstructor_returnsOne() {
        TypeElement type = mockType(List.of(mockConstructor("jakarta.inject.Inject")));

        List<ExecutableElement> result = Constructors.findInjectConstructors(type);

        assertEquals(1, result.size(), "Expected exactly one @jakarta.inject.Inject constructor");
    }

    @Test
    @DisplayName("returns one constructor when a single @javax.inject.Inject is present")
    void singleJavaxInjectConstructor_returnsOne() {
        TypeElement type = mockType(List.of(mockConstructor("javax.inject.Inject")));

        List<ExecutableElement> result = Constructors.findInjectConstructors(type);

        assertEquals(1, result.size(), "Expected exactly one @javax.inject.Inject constructor");
    }

    @Test
    @DisplayName("returns empty list when no @Inject annotation is present on constructors")
    void noInjectConstructor_returnsEmpty() {
        TypeElement type = mockType(List.of(mockConstructor("some.other.Annotation")));

        List<ExecutableElement> result = Constructors.findInjectConstructors(type);

        assertEquals(0, result.size(), "Expected zero @Inject constructors");
    }

    @Test
    @DisplayName("returns empty list when type has no constructors at all")
    void noConstructors_returnsEmpty() {
        TypeElement type = mockType(List.of());

        List<ExecutableElement> result = Constructors.findInjectConstructors(type);

        assertEquals(0, result.size(), "Expected zero constructors when none are present");
    }

    @Test
    @DisplayName("returns all constructors when multiple @Inject constructors are present")
    void multipleInjectConstructors_returnsAll() {
        TypeElement type =
                mockType(List.of(mockConstructor("jakarta.inject.Inject"), mockConstructor("jakarta.inject.Inject")));

        List<ExecutableElement> result = Constructors.findInjectConstructors(type);

        assertEquals(2, result.size(), "Expected two @Inject constructors to be returned");
    }

    @Test
    @DisplayName("ignores non-CONSTRUCTOR elements (fields) even if present in enclosed elements")
    void nonConstructorElements_ignored() {
        // mockType adds a field element to the enclosed-elements list; verify it's filtered out
        TypeElement type = mockType(List.of(mockConstructor("jakarta.inject.Inject")));

        List<ExecutableElement> result = Constructors.findInjectConstructors(type);

        assertEquals(1, result.size(), "Only constructors should be counted, not fields");
    }
}
