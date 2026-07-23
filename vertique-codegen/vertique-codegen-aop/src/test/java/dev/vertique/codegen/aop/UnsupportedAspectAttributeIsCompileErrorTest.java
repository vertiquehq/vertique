// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-W2 test: an {@link dev.vertique.aop.Aspect}-meta-annotated <em>trigger</em> annotation carrying
 * a member of an unsupported attribute kind (here a {@code float} member) on an intercepted method
 * MUST be a clean compile error — never a processor crash.
 *
 * <p>The aspect-literal emission path in {@code AopProxyEmitter} ({@code AnnotationLiteralEmitter.emit}
 * / {@code constructorArgs} for the aspect's own {@code annotationType}) had no unsupported-attribute
 * precheck — only the method-annotation metadata path did. An aspect trigger with a
 * {@code char}/{@code float}/{@code double} member therefore threw {@code UnsupportedOperationException}
 * mid-emission (a processor crash with a stack trace) instead of routing through {@code Diagnostics}.
 *
 * <p><strong>Expected RED behavior:</strong> before the fix, the processor crashes with
 * {@code UnsupportedOperationException} while emitting the aspect literal — the compilation does not
 * fail cleanly with a diagnostic naming the offending member, so {@code assertErrorMessage("weight")}
 * is not satisfied. After the fix, the processor prechecks the aspect's {@code annotationType}, emits
 * {@code Diagnostics.error} naming the {@code weight} member, and skips the bean.
 */
class UnsupportedAspectAttributeIsCompileErrorTest {

    /**
     * A single-{@code @Inject}-ctor bean whose intercepted method carries an aspect trigger
     * ({@link TestUnsupportedAspect}) with a {@code float} member — an attribute kind the literal
     * emitter cannot render.
     */
    private static JavaFileObject unsupportedAspectBean() {
        return SourceFiles.inline("com.example.Weighted", """
                package com.example;
                import dev.vertique.codegen.aop.TestUnsupportedAspect;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Weighted {
                    @Inject
                    public Weighted() {}
                    @TestUnsupportedAspect(weight = 1.5f)
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    @Test
    @DisplayName("an unsupported (float) aspect-trigger attribute kind is a clean compile error, not a processor crash")
    void unsupportedAspectAttributeKindProducesCompileError() {
        ProcessorTestHarness.run(new AopProcessor(), unsupportedAspectBean())
                .assertFailed()
                // The diagnostic must name the offending member so the user can locate it. GREEN
                // wording is open, but the message must identify the unsupported member.
                .assertErrorMessage("weight");
    }
}
