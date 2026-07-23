// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @Inject}-only binding-origin rule (FR-013-03a, plan §3): method-AOP applies
 * only to a bean bound by exactly one {@code @Inject} constructor. A bean with no {@code @Inject}
 * ctor, with more than one {@code @Inject} ctor, or that is also user-{@code @Provides}-supplied in
 * the same compilation is a compile error — never a silent or fabricated binding.
 *
 * <p>Cases (a) and (b) reuse {@code InjectConstructorValidator}'s message wording (0 →
 * "has no @Inject constructor"; &gt;1 → {@code Diagnostics.duplicateInjectConstructor}).
 *
 * <p><strong>Red:</strong> the no-op scaffold processor raises no diagnostic, so each compilation
 * succeeds and {@code assertFailed()} fails.
 */
class BindingOriginTest {

    @Test
    @DisplayName("(a) a bean with no @Inject constructor and an aspect method is rejected")
    void noInjectConstructorIsRejected() {
        JavaFileObject bean = SourceFiles.inline("com.example.NoInjectBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                public class NoInjectBean {
                    public NoInjectBean() {}
                    @TestTimed
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);

        ProcessorTestHarness.run(new AopProcessor(), bean).assertFailed().assertErrorMessage("no @Inject constructor");
    }

    @Test
    @DisplayName("(b) a bean with more than one @Inject constructor and an aspect method is rejected")
    void multipleInjectConstructorsRejected() {
        JavaFileObject bean = SourceFiles.inline("com.example.DoubleInjectBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import jakarta.inject.Named;
                public class DoubleInjectBean {
                    @Inject
                    public DoubleInjectBean(@Named("a") String a) {}
                    @Inject
                    public DoubleInjectBean(@Named("a") String a, @Named("b") String b) {}
                    @TestTimed
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);

        ProcessorTestHarness.run(new AopProcessor(), bean)
                .assertFailed()
                .assertErrorMessage("multiple @Inject constructors");
    }

    @Test
    @DisplayName("(c) an aspect bean also supplied by a user @Provides in the same compilation is a loud error")
    void aspectBeanAlsoUserProvidedIsRejected() {
        JavaFileObject bean = SourceFiles.inline("com.example.ProvidedBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class ProvidedBean {
                    @Inject
                    public ProvidedBean() {}
                    @TestTimed
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
        JavaFileObject userModule = SourceFiles.inline("com.example.UserModule", """
                package com.example;
                import dagger.Module;
                import dagger.Provides;
                @Module
                public class UserModule {
                    @Provides
                    ProvidedBean provideBean() {
                        return new ProvidedBean();
                    }
                }
                """);

        ProcessorTestHarness.run(new AopProcessor(), bean, userModule)
                .assertFailed()
                .assertErrorMessage("@Provides");
    }
}
