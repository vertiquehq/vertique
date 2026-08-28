// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-W3 test: a runtime-retained method annotation carrying a <strong>nested-annotation</strong>
 * member ({@link TestNestedAnnotationAttr#nested()}) on an intercepted method must compile through
 * the recursive literal emitter.
 *
 * <p>{@code AnnotationLiteralEmitter.isUnsupportedScalarKind}/{@code firstUnsupportedAttribute}
 * rejected only {@code char}/{@code float}/{@code double} (and arrays of those); they did not reject
 * nested-annotation members or arrays of nested annotations (also legal annotation member types). In
 * {@code valueLiteral} such a value (an {@code AnnotationMirror}) matched none of the
 * String/Class/enum/primitive/array branches and fell through to {@code String.valueOf(raw)}, emitting
 * the mirror's {@code toString()} as uncompilable generated code.
 *
 * <p>The generated anonymous nested literal preserves the annotation's runtime shape without using
 * reflection at invocation time.
 */
class NestedAnnotationMemberIsCompileErrorTest {

    /**
     * A single-{@code @Inject}-ctor bean whose intercepted method carries an aspect trigger
     * ({@link TestTimed}) plus a runtime-retained annotation ({@link TestNestedAnnotationAttr}) with a
     * nested-annotation member — a kind the literal emitter cannot render.
     */
    private static JavaFileObject nestedAnnotationBean() {
        return SourceFiles.inline("com.example.Nested", """
                package com.example;
                import dev.vertique.codegen.aop.TestNestedAnnotationAttr;
                import dev.vertique.codegen.aop.TestNestedMember;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Nested {
                    @Inject
                    public Nested() {}
                    @TestTimed
                    @TestNestedAnnotationAttr(nested = @TestNestedMember("x"))
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    @Test
    @DisplayName("a nested-annotation member on an intercepted method compiles")
    void nestedAnnotationMemberCompiles() {
        ProcessorTestHarness.run(new AopProcessor(), nestedAnnotationBean()).assertSuccess();
    }
}
