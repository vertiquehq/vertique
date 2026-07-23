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
 * member ({@link TestNestedAnnotationAttr#nested()}) on an intercepted method MUST be a clean compile
 * error — never broken generated code.
 *
 * <p>{@code AnnotationLiteralEmitter.isUnsupportedScalarKind}/{@code firstUnsupportedAttribute}
 * rejected only {@code char}/{@code float}/{@code double} (and arrays of those); they did not reject
 * nested-annotation members or arrays of nested annotations (also legal annotation member types). In
 * {@code valueLiteral} such a value (an {@code AnnotationMirror}) matched none of the
 * String/Class/enum/primitive/array branches and fell through to {@code String.valueOf(raw)}, emitting
 * the mirror's {@code toString()} as uncompilable generated code.
 *
 * <p><strong>Expected RED behavior:</strong> before the fix, the method-annotation materialization
 * renders {@code String.valueOf(@TestNestedMember(...))} into the proxy's literal constructor args,
 * producing broken source that fails {@code javac} with a confusing message — not the clean
 * {@code Diagnostics.error} naming the offending member, so {@code assertErrorMessage("nested")} is
 * not satisfied. After the fix, {@code firstUnsupportedAttribute} detects the nested-annotation kind
 * and routes it through {@code Diagnostics.error}, failing the compilation cleanly.
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
    @DisplayName("a nested-annotation member on an intercepted method is a clean compile error, not broken code")
    void nestedAnnotationMemberProducesCompileError() {
        ProcessorTestHarness.run(new AopProcessor(), nestedAnnotationBean())
                .assertFailed()
                // The diagnostic must name the offending member so the user can locate it. GREEN
                // wording is open, but the message must identify the unsupported member.
                .assertErrorMessage("nested");
    }
}
