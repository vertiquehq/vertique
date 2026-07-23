// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED reproduction for P2R2-W1: {@code AnnotationLiteralEmitter.memberFieldType} normalizes every
 * {@code Class} member to {@code Class<?>}. For a bounded {@code Class<? extends Number> value()}
 * member the generated {@code <Ann>$Literal} accessor {@code public Class<?> value()} does NOT
 * override the annotation interface method {@code Class<? extends Number> value()} — an incompatible
 * (non-covariant) return type — so the generated literal source fails to compile.
 *
 * <p>{@code firstUnsupportedAttribute} treats {@code Class} as supported, so the shape is neither
 * supported nor cleanly rejected: it silently emits non-compiling code.
 *
 * <p><strong>Expected RED behavior:</strong> the overall compilation FAILS (the emitted
 * {@code TestBoundedClass$Literal} accessor does not override the bounded interface method), so
 * {@code assertSuccess()} throws. Once W1 is fixed, the emitter must preserve the member's declared
 * type on the field/accessor (erasing only the {@code .class} value expression), so the accessor
 * overrides correctly and compilation succeeds.
 */
class BoundedClassMemberLiteralCompilesTest {

    private static final String BOUNDED_LITERAL_FQN = "dev.vertique.codegen.aop.TestBoundedClass$Literal";

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

    /** A bean with a {@code Future}-returning method carrying {@code @TestBoundedClass(value=Long.class)}. */
    private static JavaFileObject boundedBean() {
        return SourceFiles.inline("com.example.Bounded", """
                package com.example;
                import dev.vertique.codegen.aop.TestBoundedClass;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Bounded {
                    @Inject
                    public Bounded() {}
                    @TestBoundedClass(Long.class)
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    @Test
    @DisplayName("a bounded Class<? extends Number> attribute yields a literal whose accessor overrides and compiles")
    void boundedClassMemberLiteralCompiles() {
        var result = ProcessorTestHarness.run(new AopProcessor(), boundedBean()).assertSuccess();

        String literal = generatedSource(result, BOUNDED_LITERAL_FQN);
        // The accessor must declare the bounded return type so it overrides the interface method.
        boolean hasBoundedAccessor = literal.contains("Class<? extends Number> value()")
                || literal.contains("Class<? extends Number>value()");
        assertTrue(
                hasBoundedAccessor,
                "The generated literal must declare value() with the bounded return type "
                        + "Class<? extends Number> so it overrides the annotation interface method; "
                        + "generated source:\n" + literal);
    }
}
