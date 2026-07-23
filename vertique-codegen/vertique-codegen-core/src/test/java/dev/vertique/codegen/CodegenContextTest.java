// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CodegenContext}.
 */
@ExtendWith(MockitoExtension.class)
class CodegenContextTest {

    @Mock
    private ProcessingEnvironment env;

    @Mock
    private Elements elements;

    @Mock
    private Types types;

    @Mock
    private Filer filer;

    @Mock
    private Messager messager;

    private CodegenContext ctx;

    @BeforeEach
    void setUp() {
        when(env.getElementUtils()).thenReturn(elements);
        when(env.getTypeUtils()).thenReturn(types);
        when(env.getFiler()).thenReturn(filer);
        when(env.getMessager()).thenReturn(messager);
        ctx = new CodegenContext(env);
    }

    // --- Accessor tests ---

    @Test
    void accessors_returnDelegates() {
        assertSame(elements, ctx.elements());
        assertSame(types, ctx.types());
        assertSame(filer, ctx.filer());
        assertSame(messager, ctx.messager());
        assertSame(env, ctx.env());
    }

    // --- Lazy helper tests ---

    @Test
    void typeResolver_returnsSameInstance() {
        TypeResolver first = ctx.typeResolver();
        TypeResolver second = ctx.typeResolver();
        assertNotNull(first);
        assertSame(first, second, "typeResolver() must return the same lazy instance");
    }

    @Test
    void annotations_returnsSameInstance() {
        AnnotationMirrors first = ctx.annotations();
        AnnotationMirrors second = ctx.annotations();
        assertNotNull(first);
        assertSame(first, second, "annotations() must return the same lazy instance");
    }

    @Test
    void diagnostics_returnsSameInstance() {
        Diagnostics first = ctx.diagnostics();
        Diagnostics second = ctx.diagnostics();
        assertNotNull(first);
        assertSame(first, second, "diagnostics() must return the same lazy instance");
    }

    // --- isRecord tests ---

    @Test
    void isRecord_trueForRecordKind() {
        TypeElement type = mock(TypeElement.class);
        when(type.getKind()).thenReturn(ElementKind.RECORD);
        assertTrue(ctx.isRecord(type));
    }

    @Test
    void isRecord_falseForClassKind() {
        TypeElement type = mock(TypeElement.class);
        when(type.getKind()).thenReturn(ElementKind.CLASS);
        assertFalse(ctx.isRecord(type));
    }

    // --- injectConstructor tests ---

    @Test
    void injectConstructor_returnsEmptyWhenNoInjectAnnotation() {
        TypeElement type = mock(TypeElement.class);
        ExecutableElement ctor = mock(ExecutableElement.class);
        doReturn(List.of(ctor)).when(type).getEnclosedElements();
        when(ctor.getKind()).thenReturn(javax.lang.model.element.ElementKind.CONSTRUCTOR);
        // No @Inject annotation — getAnnotationMirrors returns empty
        doReturn(List.of()).when(ctor).getAnnotationMirrors();

        Optional<ExecutableElement> result = ctx.injectConstructor(type);

        assertFalse(result.isPresent(), "Should return empty when no @Inject constructor found");
    }

    @Test
    void injectConstructor_returnsConstructorWithJakartaInject() {
        TypeElement type = mock(TypeElement.class);
        ExecutableElement ctor = mock(ExecutableElement.class);
        doReturn(List.of(ctor)).when(type).getEnclosedElements();
        when(ctor.getKind()).thenReturn(javax.lang.model.element.ElementKind.CONSTRUCTOR);

        // Simulate @jakarta.inject.Inject annotation mirror
        javax.lang.model.element.AnnotationMirror injectMirror = mock(javax.lang.model.element.AnnotationMirror.class);
        DeclaredType annoType = mock(DeclaredType.class);
        TypeElement annoElement = mock(TypeElement.class);
        Name annoName = mock(Name.class);
        when(injectMirror.getAnnotationType()).thenReturn(annoType);
        when(annoType.asElement()).thenReturn(annoElement);
        when(annoElement.getQualifiedName()).thenReturn(annoName);
        when(annoName.toString()).thenReturn("jakarta.inject.Inject");
        doReturn(List.of(injectMirror)).when(ctor).getAnnotationMirrors();

        Optional<ExecutableElement> result = ctx.injectConstructor(type);

        assertTrue(result.isPresent(), "Should find constructor annotated with @jakarta.inject.Inject");
        assertSame(ctor, result.get());
    }

    @Test
    void injectConstructor_returnsConstructorWithJavaxInject() {
        TypeElement type = mock(TypeElement.class);
        ExecutableElement ctor = mock(ExecutableElement.class);
        doReturn(List.of(ctor)).when(type).getEnclosedElements();
        when(ctor.getKind()).thenReturn(javax.lang.model.element.ElementKind.CONSTRUCTOR);

        // Simulate @javax.inject.Inject annotation mirror
        javax.lang.model.element.AnnotationMirror injectMirror = mock(javax.lang.model.element.AnnotationMirror.class);
        DeclaredType annoType = mock(DeclaredType.class);
        TypeElement annoElement = mock(TypeElement.class);
        Name annoName = mock(Name.class);
        when(injectMirror.getAnnotationType()).thenReturn(annoType);
        when(annoType.asElement()).thenReturn(annoElement);
        when(annoElement.getQualifiedName()).thenReturn(annoName);
        when(annoName.toString()).thenReturn("javax.inject.Inject");
        doReturn(List.of(injectMirror)).when(ctor).getAnnotationMirrors();

        Optional<ExecutableElement> result = ctx.injectConstructor(type);

        assertTrue(result.isPresent(), "Should find constructor annotated with @javax.inject.Inject");
        assertSame(ctor, result.get());
    }

    @Test
    void injectConstructor_skipsNonConstructorElements() {
        TypeElement type = mock(TypeElement.class);
        VariableElement field = mock(VariableElement.class);
        doReturn(List.of(field)).when(type).getEnclosedElements();
        when(field.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);

        Optional<ExecutableElement> result = ctx.injectConstructor(type);

        assertFalse(result.isPresent(), "Fields should not be returned as inject constructor");
    }

    // --- asTypeElement tests ---

    @Test
    void asTypeElement_returnsEmptyForNull() {
        Optional<TypeElement> result = ctx.asTypeElement(null);
        assertFalse(result.isPresent(), "null mirror should return empty");
    }

    @Test
    void asTypeElement_returnsEmptyForNonTypeElement() {
        TypeMirror mirror = mock(TypeMirror.class);
        when(types.asElement(mirror)).thenReturn(null);

        Optional<TypeElement> result = ctx.asTypeElement(mirror);
        assertFalse(result.isPresent(), "mirror with null element should return empty");
    }

    @Test
    void asTypeElement_returnsPresentForDeclaredType() {
        TypeMirror mirror = mock(TypeMirror.class);
        TypeElement te = mock(TypeElement.class);
        when(types.asElement(mirror)).thenReturn(te);

        Optional<TypeElement> result = ctx.asTypeElement(mirror);
        assertTrue(result.isPresent(), "declared type mirror should return present");
        assertSame(te, result.get());
    }

    // --- unwrapFuture tests ---

    @Test
    void unwrapFuture_returnsOriginalForNonFuture() {
        // Create a DeclaredType whose erasure doesn't equal "io.vertx.core.Future"
        DeclaredType declaredNonFuture = mock(DeclaredType.class);
        // The erasure mock's toString() returns a Mockito default (not "io.vertx.core.Future")
        DeclaredType erased = mock(DeclaredType.class);
        when(types.erasure(declaredNonFuture)).thenReturn(erased);
        // erased.toString() is the key comparison; Mockito default won't equal "io.vertx.core.Future"
        doReturn(List.of()).when(types).directSupertypes(declaredNonFuture);

        TypeMirror result = ctx.unwrapFuture(declaredNonFuture);

        assertSame(declaredNonFuture, result, "Non-Future type should be returned unchanged");
    }

    // --- outputPackage tests ---

    @Test
    void outputPackage_usesOptionWhenSet() {
        TypeElement origin = mock(TypeElement.class);
        when(env.getOptions()).thenReturn(Map.of(CodegenContext.OPTION_OUTPUT_PACKAGE, "com.example.gen"));

        String pkg = ctx.outputPackage(origin);

        assertEquals("com.example.gen", pkg);
    }

    @Test
    void outputPackage_fallsBackToOriginPackage() {
        TypeElement origin = mock(TypeElement.class);
        when(env.getOptions()).thenReturn(Map.of());
        javax.lang.model.element.PackageElement pkg = mock(javax.lang.model.element.PackageElement.class);
        Name pkgName = mock(Name.class);
        when(pkgName.toString()).thenReturn("dev.vertique.example");
        when(pkg.getQualifiedName()).thenReturn(pkgName);
        when(elements.getPackageOf(origin)).thenReturn(pkg);

        String result = ctx.outputPackage(origin);

        assertEquals("dev.vertique.example", result);
    }

    /**
     * Proves per-origin package resolution: two elements in different packages each resolve
     * independently, not to a single global default.
     */
    @Test
    void outputPackage_perOriginResolution() {
        TypeElement originA = mock(TypeElement.class);
        TypeElement originB = mock(TypeElement.class);
        when(env.getOptions()).thenReturn(Map.of());

        javax.lang.model.element.PackageElement pkgA = mock(javax.lang.model.element.PackageElement.class);
        Name pkgAName = mock(Name.class);
        when(pkgAName.toString()).thenReturn("com.example.a");
        when(pkgA.getQualifiedName()).thenReturn(pkgAName);
        when(elements.getPackageOf(originA)).thenReturn(pkgA);

        javax.lang.model.element.PackageElement pkgB = mock(javax.lang.model.element.PackageElement.class);
        Name pkgBName = mock(Name.class);
        when(pkgBName.toString()).thenReturn("com.example.b");
        when(pkgB.getQualifiedName()).thenReturn(pkgBName);
        when(elements.getPackageOf(originB)).thenReturn(pkgB);

        assertEquals("com.example.a", ctx.outputPackage(originA));
        assertEquals("com.example.b", ctx.outputPackage(originB));
    }
}
