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
 * A bounded {@code Class<? extends Number> value()} annotation member yields a compilable literal:
 * {@code AnnotationLiteralEmitter.memberFieldType} preserves the member's declared type on the
 * generated {@code <Ann>$AopLiteral} field and accessor (erasing only the {@code .class} value
 * expression), so the accessor overrides the annotation interface method with a compatible return
 * type. Normalizing the member to {@code Class<?>} would be a non-covariant return type and the
 * generated literal source would not compile.
 *
 * <p>The test asserts the overall compilation succeeds.
 */
class BoundedClassMemberLiteralCompilesTest {

    private static final String BOUNDED_LITERAL_FQN = "dev.vertique.codegen.aop.TestBoundedClass$AopLiteral";

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
