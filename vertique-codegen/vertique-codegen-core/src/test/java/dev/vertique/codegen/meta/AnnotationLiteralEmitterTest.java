// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.meta.AnnotationLiteralEmitter.UnsupportedAttribute;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
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
 * <p>{@code firstUnsupportedAttribute} rejects unsupported primitive members before emission. Nested
 * annotation values are materialized recursively so repeatable annotation containers can be emitted.
 *
 * <p>Following the codegen-core convention (see {@code MetadataEmitterTest}), all
 * {@link javax.lang.model} elements are Mockito stubs — this module deliberately avoids a dependency
 * on {@code compile-testing}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnotationLiteralEmitterTest {

    @Test
    @DisplayName("valid generator namespace produces the exact processor-owned literal class name")
    void usesGeneratorNamespaceInLiteralClassNameAndEmission() {
        TypeElement annotationType = mockTypeElement("com.example.Audited", "Audited");
        PackageElement annotationPackage = (PackageElement) annotationType.getEnclosingElement();
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        Elements elements = mock(Elements.class);
        Types types = mock(Types.class);
        when(elements.getPackageOf(annotationType)).thenReturn(annotationPackage);
        lenient().when(elements.getElementValuesWithDefaults(mirror)).thenReturn(java.util.Map.of());

        ClassName literalClassName = AnnotationLiteralEmitter.literalClassName(annotationType, elements, "Aop");
        JavaFile literal = AnnotationLiteralEmitter.emit(annotationType, mirror, elements, types, "Aop");

        assertEquals(ClassName.get("com.example", "Audited$AopLiteral"), literalClassName);
        assertEquals("Audited$AopLiteral", literal.typeSpec().name());
    }

    @Test
    @DisplayName("namespaced emitter entry points reject blank and invalid generator namespaces")
    void rejectsBlankGeneratorNamespace() throws NoSuchMethodException {
        Method literalClassName = AnnotationLiteralEmitter.class.getDeclaredMethod(
                "literalClassName", TypeElement.class, Elements.class, String.class);
        Method emit = AnnotationLiteralEmitter.class.getDeclaredMethod(
                "emit", TypeElement.class, AnnotationMirror.class, Elements.class, Types.class, String.class);

        TypeElement annotationType = mock(TypeElement.class);
        AnnotationMirror mirror = mock(AnnotationMirror.class);
        Elements elements = mock(Elements.class);
        Types types = mock(Types.class);

        for (String namespace : List.of("", " ", "not-valid")) {
            assertIllegalArgument(literalClassName, annotationType, elements, namespace);
            assertIllegalArgument(emit, annotationType, mirror, elements, types, namespace);
        }
    }

    @Test
    @DisplayName("firstUnsupportedAttribute flags an array of unsupported primitive values")
    void firstUnsupportedAttributeFlagsUnsupportedPrimitiveArray() {
        ArrayType nestedArray = mockPrimitiveArray(TypeKind.CHAR);
        ExecutableElement member = mockMember("values", nestedArray);
        TypeElement annotationType = mockAnnotationTypeWithMembers(List.of(member));

        Optional<UnsupportedAttribute> result = AnnotationLiteralEmitter.firstUnsupportedAttribute(annotationType);

        assertTrue(result.isPresent(), "an array of unsupported primitive values must be flagged");
        assertTrue(
                result.get().member().equals("values"),
                "the flagged member must be the primitive array member 'values', got: " + result.get());
    }

    @Test
    @DisplayName("the emit/guard path rejects an empty array of unsupported primitive values")
    void emitPathRejectsUnsupportedPrimitiveArray() {
        ArrayType nestedArray = mockPrimitiveArray(TypeKind.CHAR);
        ExecutableElement member = mockMember("values", nestedArray);

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
        // the empty-array branch) must refuse the unsupported primitive component.
        assertThrows(
                UnsupportedOperationException.class,
                () -> AnnotationLiteralEmitter.constructorArgs(mirror, elements, types),
                "the emit path must reject an unsupported primitive array");
    }

    // --- helpers (Mockito-stubbed javax.lang.model elements) ---

    /**
     * Builds a mock {@link ArrayType} whose component is a nested-annotation declared type (its
     * element's kind is {@link ElementKind#ANNOTATION_TYPE}).
     *
     * @return the mock array type of a nested annotation
     */
    private static ArrayType mockPrimitiveArray(TypeKind kind) {
        Element nestedElement = mock(Element.class);
        lenient().when(nestedElement.getKind()).thenReturn(ElementKind.CLASS);

        DeclaredType nested = mock(DeclaredType.class);

        ArrayType array = mock(ArrayType.class);
        lenient().when(array.getKind()).thenReturn(TypeKind.ARRAY);
        TypeMirror component = mock(TypeMirror.class);
        lenient().when(component.getKind()).thenReturn(kind);
        lenient().when(array.getComponentType()).thenReturn(component);
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
     * Creates a mock {@link TypeElement} whose enclosing package supports
     * {@link ClassName#get(TypeElement)}.
     *
     * @param qualifiedName the annotation's fully-qualified name
     * @param simpleName    the annotation's simple name
     * @return the mock type element
     */
    @SuppressWarnings("unchecked")
    private static TypeElement mockTypeElement(String qualifiedName, String simpleName) {
        TypeElement element = mock(TypeElement.class);
        when(element.getQualifiedName()).thenReturn(mockName(qualifiedName));
        when(element.getSimpleName()).thenReturn(mockName(simpleName));

        DeclaredType type = mock(DeclaredType.class);
        when(type.getKind()).thenReturn(TypeKind.DECLARED);
        when(type.asElement()).thenReturn(element);
        when(element.asType()).thenReturn(type);

        String packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
        PackageElement pkg = mock(PackageElement.class);
        when(pkg.getQualifiedName()).thenReturn(mockName(packageName));
        doAnswer(invocation -> {
                    ElementVisitor<?, ?> visitor = invocation.getArgument(0);
                    return ((ElementVisitor<Object, Object>) visitor).visitPackage(pkg, invocation.getArgument(1));
                })
                .when(pkg)
                .accept(any(), any());
        when(element.getEnclosingElement()).thenReturn(pkg);
        return element;
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

    /**
     * Invokes a namespaced emitter entry point and verifies that it rejects the supplied namespace.
     *
     * @param method the reflected emitter method
     * @param args   method arguments ending with an invalid generator namespace
     */
    private static void assertIllegalArgument(Method method, Object... args) {
        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(null, args),
                () -> method.getName() + " must reject namespace '" + args[args.length - 1] + "'");
        assertTrue(
                thrown.getCause() instanceof IllegalArgumentException,
                () -> method.getName() + " must throw IllegalArgumentException, got " + thrown.getCause());
    }
}
