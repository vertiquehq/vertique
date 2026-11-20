// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import dev.vertique.aop.AspectProvider;
import dev.vertique.codegen.meta.MetadataEmitter;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proofs for the opt-in reflective {@code MethodMetadata.asMethod()} accessor on generated metadata.
 *
 * <p>{@code asMethod()} is not part of the reflection-free guarantee: a generated proxy never calls
 * it, and the metadata resolves the {@link Method} lazily — through
 * {@code declaringType().getDeclaredMethod(name(), parameterTypes())} — only when a consumer such as
 * a validation or sanitization aspect asks for it, then caches the result. These tests load the
 * generated proxy and drive the accessor for real: the resolved method is the declared one (with an
 * overload and primitive, array and generic parameters, since the erased {@code parameterTypes()} is
 * the lookup key), the second call returns the cached instance, an aspect can read it from
 * {@code invocation.target()} while the proxied method runs, a metadata class whose method is not
 * declared fails with a signature-bearing {@link IllegalStateException}, and the proxy source holds
 * no {@code .asMethod(} call and no {@code getDeclaredMethod} outside the accessor's own body.
 */
class AsMethodAccessorTest {

    private static final String GREETER_FQN = "com.example.Greeter";
    private static final String PROXY_FQN = "com.example.Greeter$AopProxy";
    private static final String MISSING_METADATA_FQN = "com.example.MissingMethodMetadata";

    /** A bean with two intercepted {@code greet} overloads: one {@code String}, one mixed-shape. */
    private static JavaFileObject greeterBean() {
        return SourceFiles.inline(GREETER_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.List;
                public class Greeter {
                    @Inject
                    public Greeter() {}
                    @TestTimed
                    public Future<String> greet(String name) {
                        return Future.succeededFuture("hello " + name);
                    }
                    @TestTimed
                    public Future<String> greet(int times, String[] names, List<String> tags) {
                        return Future.succeededFuture("hello x" + times);
                    }
                }
                """);
    }

    /** A plain class whose {@code @TestMarker} method feeds the renaming processor below. */
    private static JavaFileObject markedBean() {
        return SourceFiles.inline(GREETER_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestMarker;
                public class Greeter {
                    @TestMarker("hello")
                    public String greet(String name) {
                        return "hello " + name;
                    }
                }
                """);
    }

    private static MethodMetadata metadata(Class<?> proxyType, String fieldName) throws ReflectiveOperationException {
        Field field = proxyType.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (MethodMetadata) field.get(null);
    }

    @Test
    @DisplayName("asMethod() returns the declared method for each overload and caches the instance")
    void asMethodReturnsTheDeclaredMethodAndCachesIt() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), greeterBean()).assertSuccess();
        Class<?> greeter = result.loadGeneratedClass(GREETER_FQN);
        Class<?> proxy = result.loadGeneratedClass(PROXY_FQN);

        MethodMetadata simple = metadata(proxy, "GREET_0_META");
        MethodMetadata mixed = metadata(proxy, "GREET_1_META");

        Method expectedSimple = greeter.getDeclaredMethod("greet", String.class);
        Method expectedMixed = greeter.getDeclaredMethod("greet", int.class, String[].class, List.class);

        assertEquals(expectedSimple, simple.asMethod(), "asMethod() must resolve the String overload");
        assertEquals(
                expectedMixed,
                mixed.asMethod(),
                "asMethod() must resolve the overload with primitive, array and generic parameters by erasure");
        assertEquals(simple.name(), simple.asMethod().getName());
        assertSame(simple.declaringType(), simple.asMethod().getDeclaringClass());

        assertSame(simple.asMethod(), simple.asMethod(), "the second call must return the cached Method instance");
        assertSame(mixed.asMethod(), mixed.asMethod(), "the second call must return the cached Method instance");
    }

    @Test
    @DisplayName("an aspect can call invocation.target().asMethod() on a proxied bean while the method runs")
    void anAspectCanReadTheReflectiveMethodFromTheInvocation() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), greeterBean()).assertSuccess();
        Class<?> greeter = result.loadGeneratedClass(GREETER_FQN);
        Class<?> proxy = result.loadGeneratedClass(PROXY_FQN);

        List<Method> seen = new ArrayList<>();
        AspectProvider<TestTimed> provider = (target, annotation) -> invocation -> {
            seen.add(invocation.target().asMethod());
            return invocation.proceed();
        };

        Constructor<?> ctor = proxy.getDeclaredConstructor(AspectProvider.class);
        Object bean = ctor.newInstance(provider);
        Object outcome = proxy.getMethod("greet", String.class).invoke(bean, "world");

        assertInstanceOf(Future.class, outcome);
        Future<?> future = (Future<?>) outcome;
        assertTrue(future.succeeded(), "the intercepted call must complete");
        assertEquals("hello world", future.result(), "the aspect must still proceed to the real method");
        assertEquals(
                List.of(greeter.getDeclaredMethod("greet", String.class)),
                seen,
                "the aspect must observe the reflective Method of the intercepted method");
    }

    @Test
    @DisplayName("asMethod() on metadata whose method is not declared fails with the expected signature")
    void asMethodFailsClearlyWhenTheMethodIsNotDeclared() throws Exception {
        var result = ProcessorTestHarness.run(new RenamingMetadataProcessor(), markedBean())
                .assertSuccess();
        Class<?> metadataType = result.loadGeneratedClass(MISSING_METADATA_FQN);
        Constructor<?> ctor = metadataType.getDeclaredConstructor();
        ctor.setAccessible(true);
        MethodMetadata metadata = (MethodMetadata) ctor.newInstance();

        assertEquals("missing", metadata.name(), "the fixture must describe a method the class does not declare");

        IllegalStateException failure = assertThrows(IllegalStateException.class, metadata::asMethod);
        assertTrue(
                failure.getMessage().contains("com.example.Greeter.missing(java.lang.String)"),
                "the failure must name the expected signature; was: " + failure.getMessage());
        assertInstanceOf(NoSuchMethodException.class, failure.getCause(), "the reflective cause must be kept");
    }

    @Test
    @DisplayName("the generated proxy never calls asMethod() and reflects only inside the accessor's own body")
    void generatedProxyNeverCallsAsMethod() {
        var result = ProcessorTestHarness.run(new AopProcessor(), greeterBean())
                .assertSuccess()
                .assertGeneratedSourceDoesNotContain(PROXY_FQN, ".asMethod(")
                .assertGeneratedSourceContains(PROXY_FQN, "getDeclaredMethod");

        String proxy = GeneratedSources.of(result, PROXY_FQN);
        String outsideAccessor = GeneratedSources.withoutAsMethodBodies(proxy);
        assertFalse(
                outsideAccessor.contains("getDeclaredMethod"),
                "getDeclaredMethod may appear only inside asMethod(); generated source:\n" + proxy);
        assertEquals(
                2,
                GeneratedSources.count(outsideAccessor, "asMethod() { /* asMethod body elided */ }"),
                "the helper must have elided one accessor body per intercepted method");
        assertEquals(
                GeneratedSources.count(outsideAccessor, "public Method asMethod() {"),
                GeneratedSources.count(outsideAccessor, "asMethod("),
                "every asMethod( in the proxy must be a declaration, never a call (qualified or not)");
    }

    @Test
    @DisplayName("running the proxied method through a pass-through aspect never resolves the reflective Method")
    void proxyOverridesNeverResolveTheMethod() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), greeterBean()).assertSuccess();
        Class<?> proxy = result.loadGeneratedClass(PROXY_FQN);

        AspectProvider<TestTimed> passThrough = (target, annotation) -> invocation -> invocation.proceed();
        Object bean = proxy.getDeclaredConstructor(AspectProvider.class).newInstance(passThrough);
        Future<?> future = (Future<?>) proxy.getMethod("greet", String.class).invoke(bean, "world");
        assertEquals("hello world", future.result());

        MethodMetadata metadata = metadata(proxy, "GREET_0_META");
        Field cache = metadata.getClass().getDeclaredField("resolvedMethod");
        cache.setAccessible(true);
        assertNull(
                cache.get(metadata),
                "neither the proxy override nor the chain may call asMethod(); the cache must still be empty");
    }

    @Test
    @DisplayName("an interface-declared trigger resolves the interface's declared method, matching declaringType()")
    void interfaceDeclaredTriggerResolvesTheInterfaceMethod() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), genericContract(), genericBean())
                .assertSuccess();
        Class<?> contract = result.loadGeneratedClass("com.example.GenericContract");
        Class<?> proxy = result.loadGeneratedClass("com.example.GenericBean$AopProxy");

        MethodMetadata metadata = metadata(proxy, "ECHO_0_META");

        assertSame(contract, metadata.declaringType(), "the woven interface method is declared on the interface");
        assertEquals(
                contract.getDeclaredMethod("echo", Object.class),
                metadata.asMethod(),
                "asMethod() must resolve the interface's declared method by its erased signature");
        assertSame(metadata.asMethod(), metadata.asMethod());
    }

    @Test
    @DisplayName("a nested bean's metadata resolves the method declared on the nested class")
    void nestedBeanResolvesTheNestedDeclaringType() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), nestedBean()).assertSuccess();
        Class<?> nested = result.loadGeneratedClass("com.example.Container$Bean");
        Class<?> proxy = result.loadGeneratedClass("com.example.Container_Bean$AopProxy");

        MethodMetadata metadata = metadata(proxy, "GREET_0_META");

        assertSame(nested, metadata.declaringType());
        assertEquals(nested.getDeclaredMethod("greet", String.class), metadata.asMethod());
    }

    /** A generic interface whose default method carries the trigger; the bean inherits it unchanged. */
    private static JavaFileObject genericContract() {
        return SourceFiles.inline("com.example.GenericContract", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                public interface GenericContract<T> {
                    @TestTimed
                    default Future<T> echo(T value) {
                        return Future.succeededFuture(value);
                    }
                }
                """);
    }

    private static JavaFileObject genericBean() {
        return SourceFiles.inline("com.example.GenericBean", """
                package com.example;
                import jakarta.inject.Inject;
                public class GenericBean implements GenericContract<String> {
                    @Inject
                    public GenericBean() {}
                }
                """);
    }

    /** A public static nested bean, whose proxy is {@code Container_Bean$AopProxy}. */
    private static JavaFileObject nestedBean() {
        return SourceFiles.inline("com.example.Container", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Container {
                    public static class Bean {
                        @Inject
                        public Bean() {}
                        @TestTimed
                        public Future<String> greet(String name) {
                            return Future.succeededFuture("hello " + name);
                        }
                    }
                }
                """);
    }

    /**
     * Emits {@code MetadataEmitter} metadata for the {@code @TestMarker} method under the name
     * {@code missing}, producing a metadata class whose declaring type does not declare the method.
     *
     * <p>The renamed element is a {@link Proxy} over javac's real element. That works because the
     * emitter only reads the element (name, enclosing element, return type, parameters) and never
     * hands it back to a javac utility such as {@code Types.asMemberOf}, which casts to javac's own
     * symbol classes; should the emitter ever do that, this fixture fails with a
     * {@code ClassCastException} unrelated to {@code asMethod()} and must be rebuilt by compiling the
     * metadata against a {@code Greeter} that lacks the method.
     */
    @SupportedAnnotationTypes("dev.vertique.codegen.aop.TestMarker")
    static final class RenamingMetadataProcessor extends AbstractProcessor {

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            for (Element element : roundEnv.getElementsAnnotatedWith(TestMarker.class)) {
                ExecutableElement renamed = renamed((ExecutableElement) element, "missing");
                JavaFile file = MetadataEmitter.emitMethodMetadata(
                        renamed, ClassName.bestGuess(MISSING_METADATA_FQN), processingEnv.getTypeUtils());
                try {
                    file.writeTo(processingEnv.getFiler());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }

        /** Wraps {@code real} so that only {@code getSimpleName()} answers {@code name}. */
        private static ExecutableElement renamed(ExecutableElement real, String name) {
            ClassLoader loader = AsMethodAccessorTest.class.getClassLoader();
            Name simpleName = (Name) Proxy.newProxyInstance(
                    loader, new Class<?>[] {Name.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> name;
                        case "contentEquals" -> name.contentEquals((CharSequence) args[0]);
                        default -> method.invoke(name, args);
                    });
            return (ExecutableElement) Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {ExecutableElement.class},
                    (proxy, method, args) ->
                            method.getName().equals("getSimpleName") ? simpleName : method.invoke(real, args));
        }
    }
}
