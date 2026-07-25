// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice-2.1 RED test for the unsupported-<em>parameter</em>-annotation-attribute-kind compile error
 * (FR-015-07 / FR-013-13 / FR-013-09c), the parameter-level analogue of
 * {@link UnsupportedAttributeKindIsCompileErrorTest}.
 *
 * <p>When the generated proxy's nested {@code ParameterMetadataImpl} must materialize a parameter's
 * runtime-retained annotation into a {@code <Ann>$AopLiteral} for the reflection-free parameter-level
 * {@code findAnnotation} surface, an attribute of an unsupported kind ({@code char} / {@code float} /
 * {@code double}, here a {@code float} member) MUST be a hard compile error via {@code Diagnostics},
 * with a clear message naming the unsupported attribute / kind — never a silent reflective
 * fallback (which would violate the reflection-free guarantee).
 *
 * <p>The offending {@link TestParamUnsupportedAttr} rides on a parameter of a method that also
 * carries an aspect trigger ({@code @TestTimed}) so the proxy is generated and the parameter-metadata
 * materialization is reached.
 *
 * <p><strong>Expected RED behavior:</strong> the current {@code ParameterMetadataImpl} stubs
 * {@code findAnnotation}/{@code hasAnnotation} ({@code Optional.empty()}/{@code false}) and never
 * materializes a parameter annotation into a literal, so the unsupported-kind guard is never reached.
 * Compilation therefore <em>succeeds</em>, so {@code assertFailed()} fails. Once slice 2.1 lands the
 * parameter-level literal-backed lookup, the materialization must hit the unsupported-kind guard and
 * fail the compilation with a diagnostic naming the {@code weight} member / {@code float} kind.
 */
class ParameterUnsupportedAttributeKindIsCompileErrorTest {

    /**
     * A single-{@code @Inject}-ctor bean whose intercepted method has a parameter carrying a
     * runtime-retained annotation with a {@code float} member — an attribute kind the literal emitter
     * cannot render.
     */
    private static JavaFileObject unsupportedParamAttrBean() {
        return SourceFiles.inline("com.example.ParamWeighted", """
                package com.example;
                import dev.vertique.codegen.aop.TestParamUnsupportedAttr;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class ParamWeighted {
                    @Inject
                    public ParamWeighted() {}
                    @TestTimed
                    public Future<String> work(@TestParamUnsupportedAttr(weight = 1.5f) String value) {
                        return Future.succeededFuture("done " + value);
                    }
                }
                """);
    }

    @Test
    @DisplayName(
            "an unsupported (float) parameter-annotation attribute kind on an intercepted method is a compile error")
    void unsupportedParameterAttributeKindProducesCompileError() {
        ProcessorTestHarness.run(new AopProcessor(), unsupportedParamAttrBean())
                .assertFailed()
                // The diagnostic must name the offending member and/or its unsupported kind so the
                // user can locate it. GREEN wording is open, but the message must identify the kind.
                .assertErrorMessage("weight");
    }
}
