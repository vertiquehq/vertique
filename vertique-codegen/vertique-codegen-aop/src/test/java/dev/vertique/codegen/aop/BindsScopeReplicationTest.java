// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated {@code GeneratedAopModule} replicates the target bean's declared scope on
 * the proxy {@code @Binds} — never hard-codes {@code @Singleton} (FR-013-03 / spike V7).
 *
 * <p>A {@code @Singleton} bean yields a {@code @Singleton}-scoped binding; an unscoped bean yields an
 * unscoped binding.
 *
 * <p><strong>Red:</strong> the no-op scaffold processor emits no module, so no {@code @Binds} exists
 * and both assertions fail.
 */
class BindsScopeReplicationTest {

    private static final String MODULE_FQN = "com.example.GeneratedAopModule";

    /** A scope-bearing or unscoped bean with one {@code @TestTimed} method. */
    private static JavaFileObject bean(String simpleName, String scopeAnnotation, String scopeImport) {
        return SourceFiles.inline(
                "com.example." + simpleName, """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                %s
                %s
                public class %s {
                    @Inject
                    public %s() {}
                    @TestTimed
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """.formatted(scopeImport, scopeAnnotation, simpleName, simpleName));
    }

    @Test
    @DisplayName("an unscoped bean yields a @Binds with no scope annotation")
    void unscopedBeanYieldsUnscopedProxy() {
        ProcessorTestHarness.run(new AopProcessor(), bean("Plain", "", ""))
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "@Binds")
                .assertGeneratedSourceContains(MODULE_FQN, "Plain bind")
                .assertGeneratedSourceDoesNotContain(MODULE_FQN, "@Singleton");
    }

    @Test
    @DisplayName("the generated module carries @Generated(AopProcessor) (FR-013-16)")
    void generatedModuleCarriesGeneratedMarker() {
        ProcessorTestHarness.run(new AopProcessor(), bean("Plain", "", ""))
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "@Generated(\"dev.vertique.codegen.aop.AopProcessor\")");
    }

    @Test
    @DisplayName("a @Singleton bean yields a @Binds carrying @Singleton")
    void singletonBeanYieldsSingletonProxy() {
        ProcessorTestHarness.run(new AopProcessor(), bean("Scoped", "@Singleton", "import jakarta.inject.Singleton;"))
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "@Binds")
                .assertGeneratedSourceContains(MODULE_FQN, "@Singleton");
    }
}
