// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AopProcessor} rejects a non-public (package-private) aspect-carrying bean
 * with a clear compile error naming the public-visibility requirement.
 *
 * <p>The generated {@code GeneratedAopModule} lives in the resolved output package — the option
 * override, or the longest common package prefix of the aspect beans. Its {@code @Binds} method
 * returns the original bean type by name. If the bean class is package-private and the module is
 * relocated to a different package, the reference is inaccessible, producing uncompilable generated
 * source. The processor must catch this condition up front and emit a clear diagnostic so the build
 * fails with the AOP message rather than a confusing generated-source {@code javac} error.
 *
 * <p><strong>RED now:</strong> the current processor does not check bean visibility — a
 * package-private bean generates a proxy and a {@code @Binds} without complaint. The
 * {@code nonPublicBeanIsCompileError} test will fail because no diagnostic containing
 * {@code "must be public"} is emitted. The positive case ({@code publicBeanCompiles}) already
 * passes.
 */
class NonPublicBeanIsCompileErrorTest {

    /**
     * A package-private (no {@code public} modifier) bean class with a {@code @TestTimed}
     * {@code Future}-returning intercepted method. This shape should be rejected because the
     * generated {@code @Binds} in {@code GeneratedAopModule} references this bean type from a
     * possibly-different-package module, making it inaccessible when the class is not public.
     */
    private static JavaFileObject packagePrivateBean() {
        return SourceFiles.inline("com.example.PackagePrivateGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                class PackagePrivateGreeter {
                    @Inject
                    public PackagePrivateGreeter() {}
                    @TestTimed
                    public Future<String> greet() {
                        return Future.succeededFuture("hi");
                    }
                }
                """);
    }

    /**
     * A {@code public} bean class with the same {@code @TestTimed} method. This positive case must
     * remain green — public beans are the normal case and must not be affected by the new check.
     */
    private static JavaFileObject publicBean() {
        return SourceFiles.inline("com.example.PublicGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class PublicGreeter {
                    @Inject
                    public PublicGreeter() {}
                    @TestTimed
                    public Future<String> greet() {
                        return Future.succeededFuture("hi");
                    }
                }
                """);
    }

    @Test
    @DisplayName("a package-private aspect-carrying bean is rejected with a clear public-visibility diagnostic")
    void nonPublicBeanIsCompileError() {
        ProcessorTestHarness.run(new AopProcessor(), packagePrivateBean())
                .assertFailed()
                // "must be public" is the distinguishing substring that only the new diagnostic
                // carries — absent from any current generated-source javac error. Genuinely RED
                // until the visibility check is added to isNotProxyable.
                .assertErrorMessage("must be public");
    }

    @Test
    @DisplayName("a public aspect-carrying bean compiles successfully and generates a proxy")
    void publicBeanCompiles() {
        ProcessorTestHarness.run(new AopProcessor(), publicBean())
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.PublicGreeter$AopProxy", "extends PublicGreeter");
    }
}
