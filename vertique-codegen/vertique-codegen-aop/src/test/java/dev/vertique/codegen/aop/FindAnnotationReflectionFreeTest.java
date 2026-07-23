// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice-2.1 RED tests for reflection-free {@code findAnnotation}/{@code hasAnnotation} on the
 * generated proxy's nested {@code MethodMetadataImpl} (FR-013-13).
 *
 * <p>The bean's intercepted method carries an aspect trigger ({@code @TestTimed}) <em>and</em> a
 * runtime-retained, non-aspect {@link TestMarker} marker. The slice-2.1 GREEN step must, for each
 * {@code @Retention(RUNTIME)} method annotation, generate a {@code <Ann>$Literal} and have
 * {@code findAnnotation(Class)} return the matching literal by {@code annotationType()} — with no
 * call to {@code Method.getAnnotation} / {@code Class.getDeclaredMethod}. {@code @TestMarker} is the
 * forcing case: unlike an aspect trigger (whose literal already exists for the around-chain), a
 * non-aspect marker is only materialized if the metadata impl genuinely covers all runtime-retained
 * method annotations.
 *
 * <p>The generated nested {@code MethodMetadataImpl} is written into the proxy's own source file, so
 * the source-level assertions below read the {@code Greeter$AopProxy} source (the same pattern as
 * {@link ArrayAttributeLiteralCompilesTest}).
 *
 * <p><strong>Expected RED behavior:</strong> the current {@code MetadataEmitter} emits
 * {@code findAnnotation} as a {@code return Optional.empty()} stub and {@code hasAnnotation} as a
 * {@code return false} stub, and the proxy materializes literals only for aspect triggers — so no
 * {@code TestMarker} literal is referenced from {@code findAnnotation}. Both content assertions
 * therefore fail until the literal-backed lookup lands.
 */
class FindAnnotationReflectionFreeTest {

    private static final String GREETER_FQN = "com.example.Greeter";
    private static final String PROXY_FQN = "com.example.Greeter$AopProxy";

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
     * A single-{@code @Inject}-ctor bean whose {@code Future<String>} method carries both an aspect
     * trigger ({@code @TestTimed}) and a runtime-retained non-aspect marker ({@code @TestMarker}).
     */
    private static JavaFileObject markedGreeterBean() {
        return SourceFiles.inline(GREETER_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestMarker;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Greeter {
                    @Inject
                    public Greeter() {}
                    @TestTimed
                    @TestMarker("hello")
                    public Future<String> greet(String name) {
                        return Future.succeededFuture("hi " + name);
                    }
                }
                """);
    }

    @Test
    @DisplayName(
            "the generated MethodMetadataImpl's findAnnotation returns a generated Marker literal (no Optional.empty stub)")
    void findAnnotationReturnsGeneratedMarkerLiteral() {
        var result = ProcessorTestHarness.run(new AopProcessor(), markedGreeterBean())
                .assertSuccess();

        String proxy = generatedSource(result, PROXY_FQN);

        // The reflection-free lookup must reference the materialized TestMarker literal — either the
        // generated TestMarker$Literal type or a TestMarker-typed literal constant the metadata impl
        // returns. The current stub returns Optional.empty() and references no Marker literal.
        boolean referencesMarkerLiteral = proxy.contains("TestMarker$Literal") || proxy.contains("TestMarker.class");
        assertTrue(
                referencesMarkerLiteral,
                "The generated proxy's MethodMetadataImpl must materialize a TestMarker literal for the "
                        + "reflection-free findAnnotation lookup (e.g. reference TestMarker$Literal / "
                        + "TestMarker.class), not the Optional.empty() stub; generated source:\n" + proxy);

        // findAnnotation must no longer be the Optional.empty() stub.
        assertFalse(
                proxy.contains("return Optional.empty()") || proxy.contains("return java.util.Optional.empty()"),
                "findAnnotation must resolve a generated annotation literal, not return Optional.empty(); "
                        + "generated source:\n" + proxy);
    }

    @Test
    @DisplayName(
            "the reflection-free findAnnotation lookup uses no Method.getAnnotation / getDeclaredMethod reflection")
    void findAnnotationLookupIsReflectionFree() {
        var result = ProcessorTestHarness.run(new AopProcessor(), markedGreeterBean())
                .assertSuccess();

        String proxy = generatedSource(result, PROXY_FQN);

        assertFalse(
                proxy.contains(".getAnnotation("),
                "The reflection-free findAnnotation must not call getAnnotation(...); generated source:\n" + proxy);
        assertFalse(
                proxy.contains("getDeclaredMethod"),
                "The reflection-free findAnnotation must not call getDeclaredMethod(...); generated source:\n" + proxy);
    }
}
