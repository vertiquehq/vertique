// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.meta.MetadataEmitter.AnnotationLiteralRef;
import java.util.List;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link MetadataEmitter#emitParameterMetadata}, the standalone (non-nested)
 * {@code ParameterMetadata} implementation emitter added for jaxrs codegen callers that need a
 * top-level per-parameter metadata class rather than the nested {@code ParameterMetadataImpl}
 * {@link MetadataEmitter#methodMetadataType} builds for the AOP proxy path.
 *
 * <p>This method does not exist yet on {@link MetadataEmitter} — this test class fails to compile
 * against the current {@code MetadataEmitter}, which is the intended RED state for this slice.
 *
 * <p>Following {@code MetadataEmitterTest}'s established convention, all {@link javax.lang.model}
 * elements are Mockito stubs (no {@code compile-testing} dependency), and assertions snapshot the
 * emitted {@link JavaFile#toString()} source text rather than compiling and loading the generated
 * class.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetadataEmitterParameterStandaloneTest {

    private static final ClassName GENERATED_NAME = ClassName.get("com.example", "Greeter_greet_0_P0Meta");

    @Test
    @DisplayName("emits an empty reflection-free findAnnotation lookup when no annotation literals are supplied")
    void emitParameterMetadata_noAnnotationsEmitsEmptyLookup() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        JavaFile file = MetadataEmitter.emitParameterMetadata(GENERATED_NAME, 0, "x", stringMirror, types, List.of());
        String source = file.toString();

        assertFalse(source.contains("PARAM_ANNOTATION_0"), "no annotation literal constant should be emitted");
        assertFalse(source.contains("ANNOTATION_0"), "no annotation literal constant should be emitted");
        assertTrue(source.contains("findAnnotation"), "should still emit a reflection-free findAnnotation method");
        assertTrue(
                source.contains("Optional.<A>empty()"),
                "findAnnotation should fall back to an empty Optional when no literals are supplied");
    }

    @Test
    @DisplayName("emits a literal-backed constant and findAnnotation match when an annotation literal is supplied")
    void emitParameterMetadata_withAnnotationsEmitsLiteralBackedLookup() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        ClassName annotationType = ClassName.get("com.example", "TestMarker");
        ClassName literalClass = ClassName.get("com.example", "TestMarker$JaxRsLiteral");
        AnnotationLiteralRef ref = new AnnotationLiteralRef(annotationType, literalClass, CodeBlock.of("$S", "x"));

        JavaFile file =
                MetadataEmitter.emitParameterMetadata(GENERATED_NAME, 0, "x", stringMirror, types, List.of(ref));
        String source = file.toString();

        assertTrue(source.contains("ANNOTATION_0"), "should emit a per-annotation literal constant field");
        assertTrue(
                source.contains("TestMarker$JaxRsLiteral"),
                "the literal constant's initializer should reference the generated <Ann>$JaxRsLiteral class");
        assertTrue(source.contains("TestMarker.class"), "findAnnotation should match against the annotation type");
    }

    @Test
    @DisplayName("emits a public final top-level class, not a private static nested class")
    void emitParameterMetadata_isPublicFinalTopLevelClass() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        JavaFile file = MetadataEmitter.emitParameterMetadata(GENERATED_NAME, 0, "x", stringMirror, types, List.of());
        String source = file.toString();

        assertTrue(source.contains("public final class"), "generated type should be a public final top-level class");
        assertFalse(
                source.contains("private static final class"),
                "generated type should not carry the nested (AOP) modifiers");
    }

    @Test
    @DisplayName("bakes index, name, and type as compile-time constants on the generated accessors")
    void emitParameterMetadata_indexNameTypeAreConstants() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        JavaFile file = MetadataEmitter.emitParameterMetadata(GENERATED_NAME, 2, "foo", stringMirror, types, List.of());
        String source = file.toString();

        assertTrue(source.contains("return 2;"), "index() should return the constant index");
        assertTrue(source.contains("\"foo\""), "name() should return the constant name as a String literal");
        assertTrue(source.contains("String.class"), "type() should return the erased type as a Class literal");
    }

    @Test
    @DisplayName("6-arg emitParameterMetadata with a null name emits a literal null instead of a String constant")
    void emitParameterMetadata_sixArg_nullNameEmitsNullLiteral() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        JavaFile file = MetadataEmitter.emitParameterMetadata(GENERATED_NAME, 0, null, stringMirror, types, List.of());
        String source = file.toString();

        assertTrue(source.contains("return null;"), "name() should return the null literal, not a String constant");
    }

    @Test
    @DisplayName(
            "7-arg emitParameterMetadata with a null reflectiveFallbackSupplier is purely literal-backed (no REFLECTIVE_FALLBACK field)")
    void emitParameterMetadata_sevenArg_nullFallbackIsPurelyLiteralBacked() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        JavaFile file =
                MetadataEmitter.emitParameterMetadata(GENERATED_NAME, 0, "x", stringMirror, types, List.of(), null);
        String source = file.toString();

        assertFalse(source.contains("REFLECTIVE_FALLBACK"), "no reflective fallback field when supplier is null");
        assertTrue(
                source.contains("return () -> annotations.clone();"),
                "annotationsLazy() should clone the literal-only annotations array");
    }

    @Test
    @DisplayName(
            "7-arg emitParameterMetadata with a non-null reflectiveFallbackSupplier wires the REFLECTIVE_FALLBACK "
                    + "field, checks literals first then the fallback in findAnnotation, and clones the fallback array in annotationsLazy()")
    void emitParameterMetadata_sevenArg_withFallbackWiresFallbackLookupAndAnnotationsLazy() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        ClassName annotationType = ClassName.get("com.example", "TestMarker");
        ClassName literalClass = ClassName.get("com.example", "TestMarker$JaxRsLiteral");
        AnnotationLiteralRef ref = new AnnotationLiteralRef(annotationType, literalClass, CodeBlock.of("$S", "x"));
        CodeBlock fallbackSupplier = CodeBlock.of("() -> new $T[0]", java.lang.annotation.Annotation.class);

        JavaFile file = MetadataEmitter.emitParameterMetadata(
                GENERATED_NAME, 0, "x", stringMirror, types, List.of(ref), fallbackSupplier);
        String source = file.toString();

        assertTrue(source.contains("REFLECTIVE_FALLBACK"), "reflective fallback field should be emitted");
        assertTrue(source.contains("ANNOTATION_0"), "the literal constant should still be emitted (checked first)");
        assertTrue(
                source.contains("TestMarker.class"),
                "findAnnotation should check the literal constant before the fallback");
        assertTrue(
                source.contains("for (Annotation annotation : REFLECTIVE_FALLBACK.get())"),
                "findAnnotation should scan the reflective fallback array after the literals");
        assertTrue(
                source.contains("annotation.annotationType() == type"),
                "the fallback scan should match by annotationType()");
        assertTrue(
                source.contains("return () -> REFLECTIVE_FALLBACK.get().clone();"),
                "annotationsLazy() should clone the fallback array (the full merged set), not the literal subset");
    }

    @Test
    @DisplayName(
            "7-arg emitParameterMetadata with a fallback and no literals still emits a reflection-free findAnnotation")
    void emitParameterMetadata_sevenArg_fallbackOnlyNoLiterals() {
        Types types = mock(Types.class);
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        CodeBlock fallbackSupplier = CodeBlock.of("() -> new $T[0]", java.lang.annotation.Annotation.class);

        JavaFile file = MetadataEmitter.emitParameterMetadata(
                GENERATED_NAME, 0, "x", stringMirror, types, List.of(), fallbackSupplier);
        String source = file.toString();

        assertFalse(source.contains("ANNOTATION_0"), "no literal constant should be emitted when none are supplied");
        assertTrue(source.contains("REFLECTIVE_FALLBACK"), "reflective fallback field should still be emitted");
        assertTrue(
                source.contains("for (Annotation annotation : REFLECTIVE_FALLBACK.get())"),
                "findAnnotation should scan the reflective fallback array even with zero literals");
    }

    // --- helpers (Mockito-stubbed javax.lang.model elements, adapted from MetadataEmitterTest) ---

    /**
     * Creates a mock {@link TypeElement} with the given fully-qualified and simple names, whose
     * {@code asType()} returns a {@link DeclaredType} pointing back at the element.
     *
     * <p>The element's enclosing element is stubbed as a {@link PackageElement} whose {@code accept}
     * dispatches to {@code visitPackage}, so {@code ClassName.get(TypeElement)} (used by the emitter
     * for erased declared types) resolves the package name correctly.
     *
     * @param qualifiedName the fully-qualified name (e.g. {@code java.lang.String})
     * @param simpleName    the simple name (e.g. {@code String})
     * @return the mock type element
     */
    private static TypeElement mockTypeElement(String qualifiedName, String simpleName) {
        TypeElement element = mock(TypeElement.class);
        when(element.getQualifiedName()).thenReturn(mockName(qualifiedName));
        when(element.getSimpleName()).thenReturn(mockName(simpleName));
        DeclaredType type = mock(DeclaredType.class);
        when(type.getKind()).thenReturn(TypeKind.DECLARED);
        when(type.asElement()).thenReturn(element);
        when(element.asType()).thenReturn(type);

        // ClassName.get(TypeElement) walks the enclosing chain via the element visitor; stub the
        // enclosing package so visitPackage(...) supplies the package name.
        int lastDot = qualifiedName.lastIndexOf('.');
        String packageName = lastDot >= 0 ? qualifiedName.substring(0, lastDot) : "";
        PackageElement pkg = mock(PackageElement.class);
        when(pkg.getQualifiedName()).thenReturn(mockName(packageName));
        doAnswer(inv -> {
                    ElementVisitor<?, ?> visitor = inv.getArgument(0);
                    return ((ElementVisitor<Object, Object>) visitor).visitPackage(pkg, inv.getArgument(1));
                })
                .when(pkg)
                .accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(element.getEnclosingElement()).thenReturn(pkg);
        return element;
    }

    /**
     * Creates a mock {@link DeclaredType} backed by a {@link TypeElement} carrying the given names.
     *
     * @param qualifiedName the fully-qualified name of the type
     * @param simpleName    the simple name of the type
     * @return the mock declared type
     */
    private static DeclaredType mockDeclaredType(String qualifiedName, String simpleName) {
        return (DeclaredType) mockTypeElement(qualifiedName, simpleName).asType();
    }

    /**
     * Creates a mock {@link Name} whose {@code toString()} yields the given value.
     *
     * @param value the name value
     * @return the mock name
     */
    private static Name mockName(String value) {
        // A default-answer mock yields the value from toString() without any nested stubbing call.
        // Calling when(...)/doReturn(...) here would open an ongoing-stub on toString() inside the
        // enclosing when(element.getQualifiedName()).thenReturn(mockName(...)), which Mockito flags
        // as UnfinishedStubbing; a settings-based default answer avoids that.
        return mock(
                Name.class,
                invocation -> "toString".equals(invocation.getMethod().getName())
                        ? value
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }
}
