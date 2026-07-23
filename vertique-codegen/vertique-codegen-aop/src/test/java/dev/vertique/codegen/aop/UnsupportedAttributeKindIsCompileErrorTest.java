// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice-2.1 RED test for the unsupported-annotation-attribute-kind compile error (FR-013-13 /
 * FR-013-09c).
 *
 * <p>When the generated proxy's {@code MethodMetadataImpl} must materialize a method's
 * runtime-retained annotation into a {@code <Ann>$Literal} for the reflection-free
 * {@code findAnnotation} surface, an attribute of an unsupported kind ({@code char} / {@code float} /
 * {@code double}, here a {@code float} member) MUST be a hard compile error via {@code Diagnostics},
 * with a clear message naming the unsupported attribute / kind — never a silent reflective
 * {@code asMethod()} fallback (which would violate the reflection-free guarantee).
 *
 * <p>The offending {@link TestUnsupportedAttr} rides on a method that also carries an aspect trigger
 * ({@code @TestTimed}) so the proxy is generated and the metadata materialization is reached.
 *
 * <p><strong>Expected RED behavior:</strong> the current {@code MethodMetadataImpl} stubs
 * {@code findAnnotation}/{@code hasAnnotation} ({@code Optional.empty()}/{@code false}) and never
 * materializes a method annotation into a literal, so the unsupported-kind guard is never reached.
 * Compilation therefore <em>succeeds</em>, so {@code assertFailed()} fails. Once slice 2.1 lands the
 * literal-backed lookup, the materialization must hit the unsupported-kind guard and fail the
 * compilation with a diagnostic naming the {@code weight} member / {@code float} kind.
 */
class UnsupportedAttributeKindIsCompileErrorTest {

    /**
     * A single-{@code @Inject}-ctor bean whose intercepted method carries a runtime-retained
     * annotation with a {@code float} member — an attribute kind the literal emitter cannot render.
     */
    private static JavaFileObject unsupportedAttrBean() {
        return SourceFiles.inline("com.example.Weighted", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import dev.vertique.codegen.aop.TestUnsupportedAttr;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Weighted {
                    @Inject
                    public Weighted() {}
                    @TestTimed
                    @TestUnsupportedAttr(weight = 1.5f)
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    @Test
    @DisplayName("an unsupported (float) annotation attribute kind on an intercepted method is a compile error")
    void unsupportedAttributeKindProducesCompileError() {
        ProcessorTestHarness.run(new AopProcessor(), unsupportedAttrBean())
                .assertFailed()
                // The diagnostic must name the offending member and/or its unsupported kind so the
                // user can locate it. GREEN wording is open, but the message must identify the kind.
                .assertErrorMessage("weight");
    }
}
