// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies a bean using {@code @AssistedInject}/{@code @Assisted} carrying an aspect method is refused
 * with a clear diagnostic — such beans cannot be subclass-proxied (FR-013-03).
 *
 * <p><strong>Red:</strong> the no-op scaffold processor raises no diagnostic, so the compilation
 * succeeds and {@code assertFailed()} fails.
 */
class AssistedInjectRefusedTest {

    @Test
    @DisplayName("an @AssistedInject bean with an aspect method fails compilation citing @AssistedInject")
    void assistedBeanIsRejectedWithDiagnostic() {
        JavaFileObject bean = SourceFiles.inline("com.example.AssistedGreeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import dagger.assisted.Assisted;
                import dagger.assisted.AssistedInject;
                import io.vertx.core.Future;
                public class AssistedGreeter {
                    private final String greeting;
                    @AssistedInject
                    public AssistedGreeter(@Assisted String greeting) {
                        this.greeting = greeting;
                    }
                    @TestTimed
                    public Future<String> greet(String name) {
                        return Future.succeededFuture(greeting + " " + name);
                    }
                }
                """);

        ProcessorTestHarness.run(new AopProcessor(), bean).assertFailed().assertErrorMessage("@AssistedInject");
    }
}
