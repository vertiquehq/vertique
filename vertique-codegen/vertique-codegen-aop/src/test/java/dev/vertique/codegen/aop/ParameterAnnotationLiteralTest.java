// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice-2.1 RED tests for reflection-free <em>parameter-level</em> {@code findAnnotation}/
 * {@code hasAnnotation} on the generated proxy's nested {@code ParameterMetadataImpl} (FR-015-07 /
 * PRD-REST-018 AC-6).
 *
 * <p>The bean's intercepted method carries an aspect trigger ({@code @TestTimed}) so the proxy is
 * generated, and its <em>parameter</em> carries a runtime-retained, {@code PARAMETER}-targeted
 * marker ({@link TestParamMarker}). The slice-2.1 GREEN step must, for each {@code @Retention(RUNTIME)}
 * parameter annotation, generate a {@code <Ann>$Literal} and have the nested
 * {@code ParameterMetadataImpl}'s {@code findAnnotation(Class)} return the matching literal by
 * {@code annotationType()} — with no call to {@code Parameter.getAnnotation} /
 * {@code Method.getParameterAnnotations} / {@code Class.getDeclaredMethod} reflection. This mirrors
 * the method-level proof in {@link FindAnnotationReflectionFreeTest}, lowered to the parameter
 * surface.
 *
 * <p>{@link TestParamMarker} is the forcing case: it is neither an aspect trigger (whose literal
 * already exists for the around-chain) nor a method annotation, so its {@code <Ann>$Literal} is only
 * materialized if the generated {@code ParameterMetadataImpl} genuinely covers all runtime-retained
 * parameter annotations.
 *
 * <p>The generated nested {@code ParameterMetadataImpl} is written into the proxy's own source file,
 * so the source-level assertions below read the {@code Greeter$AopProxy} source (the same pattern as
 * {@link ArrayAttributeLiteralCompilesTest} and {@link FindAnnotationReflectionFreeTest}).
 *
 * <p><strong>Expected RED behavior:</strong> the current {@code MetadataEmitter} emits the nested
 * {@code ParameterMetadataImpl}'s {@code findAnnotation} as an {@code Optional.<A>empty()} stub and
 * {@code hasAnnotation} as a {@code false} stub, and never materializes parameter annotations into
 * literals — so no {@code TestParamMarker} literal is referenced anywhere in the proxy. Both content
 * assertions therefore fail until the parameter-level literal-backed lookup lands.
 *
 * <p>{@link #annotationsLazyIsConsistentWithFindAnnotation()} covers a distinct gap: the nested
 * {@code ParameterMetadataImpl} stores the real per-parameter {@code Annotation[]} in a field (used
 * by {@code findAnnotation}/{@code hasAnnotation}), but until the {@code annotationsLazy()} override
 * lands it inherits the {@code ParameterMetadata} SPI default ({@code () -> new Annotation[0]}) — so
 * {@code findAnnotation} sees the marker while {@code annotationsLazy()} reports none. This test
 * loads and instantiates the generated {@code ParameterMetadataImpl} to prove the two views agree.
 */
class ParameterAnnotationLiteralTest {

    private static final String GREETER_FQN = "com.example.ParamGreeter";
    private static final String PROXY_FQN = "com.example.ParamGreeter$AopProxy";

    /** Reads the full generated source of {@code fqn} from the compilation, failing if absent. */
    private static String generatedSource(ProcessorTestHarness.Result result, String fqn) {
        JavaFileObject file = result.compilation()
                .generatedSourceFile(fqn)
                .orElseThrow(() -> new AssertionError("No generated source for " + fqn));
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionError("Failed to read generated source for " + fqn, e);
        }
    }

    /**
     * A single-{@code @Inject}-ctor bean whose {@code Future<String>} method carries an aspect
     * trigger ({@code @TestTimed}) and whose parameter carries a runtime-retained, parameter-targeted
     * marker ({@code @TestParamMarker}).
     */
    private static JavaFileObject paramMarkedGreeterBean() {
        return SourceFiles.inline(GREETER_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestParamMarker;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class ParamGreeter {
                    @Inject
                    public ParamGreeter() {}
                    @TestTimed
                    public Future<String> greet(@TestParamMarker("x") String name) {
                        return Future.succeededFuture("hi " + name);
                    }
                }
                """);
    }

    @Test
    @DisplayName(
            "the generated ParameterMetadataImpl's findAnnotation returns a generated parameter-marker literal (no Optional.empty stub)")
    void parameterFindAnnotationReturnsGeneratedMarkerLiteral() {
        var result = ProcessorTestHarness.run(new AopProcessor(), paramMarkedGreeterBean())
                .assertSuccess();

        String proxy = generatedSource(result, PROXY_FQN);

        // The reflection-free parameter-level lookup must reference the materialized TestParamMarker
        // literal — either the generated TestParamMarker$Literal type or a TestParamMarker-typed
        // literal constant the ParameterMetadataImpl returns. The current stub returns
        // Optional.<A>empty() and references no parameter-marker literal at all.
        boolean referencesParamMarkerLiteral =
                proxy.contains("TestParamMarker$Literal") || proxy.contains("TestParamMarker.class");
        assertTrue(
                referencesParamMarkerLiteral,
                "The generated proxy's ParameterMetadataImpl must materialize a TestParamMarker literal for the "
                        + "reflection-free parameter-level findAnnotation lookup (e.g. reference "
                        + "TestParamMarker$Literal / TestParamMarker.class), not the Optional.empty() stub; "
                        + "generated source:\n" + proxy);
    }

    @Test
    @DisplayName(
            "the reflection-free parameter-level findAnnotation lookup uses no getAnnotation / getParameterAnnotations / getDeclaredMethod reflection")
    void parameterFindAnnotationLookupIsReflectionFree() {
        var result = ProcessorTestHarness.run(new AopProcessor(), paramMarkedGreeterBean())
                .assertSuccess();

        String proxy = generatedSource(result, PROXY_FQN);

        assertFalse(
                proxy.contains(".getAnnotation("),
                "The reflection-free parameter-level findAnnotation must not call getAnnotation(...); generated source:\n"
                        + proxy);
        assertFalse(
                proxy.contains("getParameterAnnotations"),
                "The reflection-free parameter-level findAnnotation must not call getParameterAnnotations(...); "
                        + "generated source:\n" + proxy);
        assertFalse(
                proxy.contains("getDeclaredMethod"),
                "The reflection-free parameter-level findAnnotation must not call getDeclaredMethod(...); "
                        + "generated source:\n" + proxy);
    }

    @Test
    @DisplayName(
            "the generated ParameterMetadataImpl's annotationsLazy().get() agrees with findAnnotation (both see the same TestParamMarker)")
    void annotationsLazyIsConsistentWithFindAnnotation() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), paramMarkedGreeterBean())
                .assertSuccess();

        Class<?> proxyClass = result.loadGeneratedClass(PROXY_FQN);

        // The proxy holds a `private static final MethodMetadata GREET_0_META` constant for the sole
        // intercepted method (see AopProxyEmitter.metaFieldName: <CONSTANT_NAME>_<ordinal>_META).
        Field metaField = proxyClass.getDeclaredField("GREET_0_META");
        metaField.setAccessible(true);
        Object methodMetadata = metaField.get(null);

        // The nested MethodMetadataImpl/ParameterMetadataImpl types are private, so their public
        // interface methods must be unreflected explicitly even though the methods themselves are
        // public (JLS access checks the declaring class, not just the member modifier).
        Method parametersMethod = methodMetadata.getClass().getMethod("parameters");
        parametersMethod.setAccessible(true);
        List<?> parameters = (List<?>) parametersMethod.invoke(methodMetadata);
        assertEquals(1, parameters.size(), "greet(String name) has exactly one parameter");
        Object parameterMetadata = parameters.get(0);

        // findAnnotation(TestParamMarker.class) must resolve the same literal that annotationsLazy()
        // reports in its full array — proving the two views are backed by the same stored data rather
        // than annotationsLazy() silently returning the SPI default empty array.
        Method findAnnotationMethod = parameterMetadata.getClass().getMethod("findAnnotation", Class.class);
        findAnnotationMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        var found = (java.util.Optional<TestParamMarker>)
                findAnnotationMethod.invoke(parameterMetadata, TestParamMarker.class);
        assertTrue(found.isPresent(), "findAnnotation(TestParamMarker.class) must resolve the materialized literal");

        Method annotationsLazyMethod = parameterMetadata.getClass().getMethod("annotationsLazy");
        annotationsLazyMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        var supplier = (Supplier<Annotation[]>) annotationsLazyMethod.invoke(parameterMetadata);
        Annotation[] lazyAnnotations = supplier.get();

        assertEquals(
                1,
                lazyAnnotations.length,
                "annotationsLazy() must report the parameter's real annotation array, not the SPI default empty "
                        + "array; got: " + java.util.Arrays.toString(lazyAnnotations));
        assertArrayEquals(
                new Annotation[] {found.get()},
                lazyAnnotations,
                "annotationsLazy() must return the same annotation instance findAnnotation resolves");
    }
}
