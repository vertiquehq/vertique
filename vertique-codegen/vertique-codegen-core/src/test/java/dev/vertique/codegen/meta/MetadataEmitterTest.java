// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.meta.MetadataEmitter.AnnotationLiteralRef;
import java.util.List;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
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
 * Unit tests for {@link MetadataEmitter}, the reusable JavaPoet emitter that, given a method
 * element, emits a {@code MethodMetadataImpl} whose accessors return compile-time constants (method
 * name, declaring/return/parameter {@code Class} literals, and an ordered {@code ParameterMetadataImpl}
 * list) and never reflects at call time.
 *
 * <p>Following the established codegen-core convention (see {@code MethodOverridesTest},
 * {@code AnnotationMirrorsTest}), all {@link javax.lang.model} elements are Mockito stubs — this
 * module deliberately avoids a dependency on {@code compile-testing}. Assertions snapshot the
 * emitted {@link JavaFile#toString()} the way the sibling {@code DaggerModuleWriterTest} asserts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetadataEmitterTest {

    private static final ClassName GENERATED_NAME = ClassName.get("com.example", "Greeter$GreetMethodMetadata");

    @Test
    @DisplayName("emits a MethodMetadataImpl with constant-only accessors for a sample method")
    void emitsMethodMetadataImplWithConstantsForSampleMethod() {
        Types types = mock(Types.class);

        // Sample method: String greet(String name) declared on com.example.Greeter
        TypeElement greeter = mockTypeElement("com.example.Greeter", "Greeter");
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        VariableElement nameParam = mockParam("name", stringMirror);
        ExecutableElement greet = mockMethod("greet", greeter, stringMirror, List.of(nameParam));

        JavaFile file = MetadataEmitter.emitMethodMetadata(greet, GENERATED_NAME, types);
        String source = file.toString();

        // Method name baked as a String literal constant.
        assertTrue(source.contains("\"greet\""), "method name should be a constant String literal");

        // Erased parameter type baked as a Class literal (String.class).
        assertTrue(source.contains("String.class"), "parameter/return type should be baked as a Class literal");

        // A parameters() list of ParameterMetadataImpl is emitted.
        assertTrue(source.contains("ParameterMetadataImpl"), "should emit a ParameterMetadataImpl for each parameter");

        // The emitted impl implements the neutral SPI.
        assertTrue(source.contains("MethodMetadata"), "should implement the MethodMetadata SPI");

        // Reflection-free: no reflective method/annotation lookups in the generated source.
        assertFalse(source.contains("getDeclaredMethod"), "generated source must not call getDeclaredMethod");
        assertFalse(source.contains("getMethod("), "generated source must not call getMethod");
        assertFalse(source.contains("Method.invoke"), "generated source must not call Method.invoke");
        assertFalse(source.contains(".getAnnotation("), "generated source must not call getAnnotation reflectively");
    }

    @Test
    @DisplayName("methodMetadataType bakes a method-level annotation literal constant and a literal-backed "
            + "findAnnotation match")
    void methodMetadataType_withMethodAnnotationEmitsLiteralBackedLookup() {
        Types types = mock(Types.class);

        TypeElement greeter = mockTypeElement("com.example.Greeter", "Greeter");
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        VariableElement nameParam = mockParam("name", stringMirror);
        ExecutableElement greet = mockMethod("greet", greeter, stringMirror, List.of(nameParam));

        ClassName annotationType = ClassName.get("com.example", "TestMarker");
        ClassName literalClass = ClassName.get("com.example", "TestMarker$AopLiteral");
        AnnotationLiteralRef ref = new AnnotationLiteralRef(annotationType, literalClass, CodeBlock.of("$S", "x"));

        JavaFile file = JavaFile.builder(
                        GENERATED_NAME.packageName(),
                        MetadataEmitter.methodMetadataType(
                                        greet, GENERATED_NAME, types, List.of(ref), List.of(List.of()))
                                .addModifiers(javax.lang.model.element.Modifier.FINAL)
                                .build())
                .build();
        String source = file.toString();

        assertTrue(source.contains("ANNOTATION_0"), "should emit a per-method-annotation literal constant field");
        assertTrue(
                source.contains("TestMarker$AopLiteral"),
                "the literal constant's initializer should reference the generated <Ann>$AopLiteral class");
        assertTrue(
                source.contains("if (type == TestMarker.class)"),
                "findAnnotation should match the looked-up type against the literal's annotation type");
        assertTrue(source.contains("@SuppressWarnings(\"unchecked\")"), "the unchecked cast should be suppressed");
    }

    @Test
    @DisplayName("methodMetadataType bakes a parameter-level annotation literal on the nested "
            + "ParameterMetadataImpl construction")
    void methodMetadataType_withParameterAnnotationBakesNestedLiteral() {
        Types types = mock(Types.class);

        TypeElement greeter = mockTypeElement("com.example.Greeter", "Greeter");
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        VariableElement nameParam = mockParam("name", stringMirror);
        ExecutableElement greet = mockMethod("greet", greeter, stringMirror, List.of(nameParam));

        ClassName annotationType = ClassName.get("com.example", "ParamMarker");
        ClassName literalClass = ClassName.get("com.example", "ParamMarker$AopLiteral");
        AnnotationLiteralRef ref = new AnnotationLiteralRef(annotationType, literalClass, CodeBlock.of("$S", "p"));

        JavaFile file = JavaFile.builder(
                        GENERATED_NAME.packageName(),
                        MetadataEmitter.methodMetadataType(
                                        greet, GENERATED_NAME, types, List.of(), List.of(List.of(ref)))
                                .addModifiers(javax.lang.model.element.Modifier.FINAL)
                                .build())
                .build();
        String source = file.toString();

        assertTrue(
                source.contains("PARAM_0_ANNOTATION_0"),
                "should emit a per-parameter-annotation literal constant field named PARAM_<p>_ANNOTATION_<i>");
        assertTrue(
                source.contains("ParamMarker$AopLiteral"),
                "the literal constant's initializer should reference the generated <Ann>$AopLiteral class");
        assertTrue(
                source.contains("PARAM_0_ANNOTATION_0)"),
                "the ParameterMetadataImpl construction should pass the literal field as a trailing argument");
    }

    @Test
    @DisplayName("emits a reflection-free generic return type for parameterized methods")
    void emitsGenericReturnTypeForParameterizedMethod() {
        Types types = mock(Types.class);

        TypeElement service = mockTypeElement("com.example.Service", "Service");
        TypeElement future = mockTypeElement("io.vertx.core.Future", "Future");
        TypeMirror stringMirror = mockDeclaredType("java.lang.String", "String");
        DeclaredType rawFuture = (DeclaredType) future.asType();
        DeclaredType futureOfString = mock(DeclaredType.class);
        NoType noOwner = mock(NoType.class);
        when(futureOfString.getKind()).thenReturn(TypeKind.DECLARED);
        when(futureOfString.asElement()).thenReturn(future);
        doReturn(List.of(stringMirror)).when(futureOfString).getTypeArguments();
        when(futureOfString.getEnclosingType()).thenReturn(noOwner);
        when(noOwner.getKind()).thenReturn(TypeKind.NONE);
        when(types.erasure(futureOfString)).thenReturn(rawFuture);
        lenient().when(types.erasure(stringMirror)).thenReturn(stringMirror);

        ExecutableElement probe = mockMethod("probe", service, futureOfString, List.of());

        JavaFile file = MetadataEmitter.emitMethodMetadata(probe, GENERATED_NAME, types);
        String source = file.toString();

        assertTrue(source.contains("genericReturnType"), "the metadata impl should implement the accessor");
        assertTrue(source.contains("ParameterizedType"), "parameterized returns need a Type graph");
        assertTrue(source.contains("getActualTypeArguments"), "the payload type must be retained");
        assertTrue(source.contains("Future.class"), "the raw return type should be emitted as a class literal");
        assertTrue(source.contains("String.class"), "the generic payload should be emitted as a class literal");
        assertFalse(
                source.contains("genericReturnType is part of the reflective-accessor group"),
                "generated cache metadata must not retain the old throwing stub");
    }

    // --- helpers (Mockito-stubbed javax.lang.model elements) ---

    /**
     * Creates a mock {@link TypeElement} with the given fully-qualified and simple names, whose
     * {@code asType()} returns a {@link DeclaredType} pointing back at the element.
     *
     * <p>The element's enclosing element is stubbed as a {@link PackageElement} whose {@code accept}
     * dispatches to {@code visitPackage}, so {@code ClassName.get(TypeElement)} (used by the emitter
     * for the declaring and erased declared types) resolves the package name correctly.
     *
     * @param qualifiedName the fully-qualified name (e.g. {@code com.example.Greeter})
     * @param simpleName    the simple name (e.g. {@code Greeter})
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
     * Creates a mock {@link VariableElement} (method parameter) with the given name and type.
     *
     * @param name the parameter name
     * @param type the parameter type mirror
     * @return the mock parameter element
     */
    private static VariableElement mockParam(String name, TypeMirror type) {
        VariableElement param = mock(VariableElement.class);
        when(param.getSimpleName()).thenReturn(mockName(name));
        when(param.asType()).thenReturn(type);
        return param;
    }

    /**
     * Creates a mock {@link ExecutableElement} with the given name, declaring type, return type, and
     * parameters.
     *
     * @param name        the method name
     * @param declaring   the declaring type element
     * @param returnType  the return type mirror
     * @param parameters  the parameter elements
     * @return the mock method element
     */
    private static ExecutableElement mockMethod(
            String name, TypeElement declaring, TypeMirror returnType, List<VariableElement> parameters) {
        ExecutableElement method = mock(ExecutableElement.class);
        when(method.getSimpleName()).thenReturn(mockName(name));
        when(method.getEnclosingElement()).thenReturn(declaring);
        when(method.getReturnType()).thenReturn(returnType);
        // doReturn avoids wildcard-capture inference issues (getParameters returns
        // List<? extends VariableElement>).
        doReturn(parameters).when(method).getParameters();
        return method;
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
