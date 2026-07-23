// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the generated {@code Bean$AopProxy} is always placed in the <em>bean's own
 * package</em>, regardless of the {@code -Avertique.codegen.package} option.
 *
 * <p>The proxy {@code extends Bean} and calls {@code super(...)}/{@code super.method(...)}. A proxy
 * generated outside the bean's package cannot access package-private constructors or
 * package-private intercepted methods — those pass the proxyability check (which does not reject
 * package-private) and then fail compilation in the generated source. The proxy must co-locate with
 * the bean so the {@code super} calls are legal.
 *
 * <p>The {@code GeneratedAopModule} (the {@code @Binds} module) is a different matter: it does
 * <em>not</em> subclass anything and may legally live in the output-package override. Only the
 * proxy is constrained to the bean's package.
 */
class ProxyIsGeneratedInBeanPackageTest {

    private static final String BEAN_PACKAGE = "com.foo";
    private static final String BEAN_FQN = "com.foo.MyBean";
    private static final String PROXY_FQN = "com.foo.MyBean$AopProxy";
    private static final String OUTPUT_PACKAGE = "com.generated";

    /**
     * A simple public bean in {@code com.foo} with a package-private intercepted method. The
     * package-private visibility is the critical detail: if the proxy lands outside {@code com.foo},
     * the {@code super.work()} call in the generated override is inaccessible, and the generated
     * source fails to compile.
     */
    private static JavaFileObject packagePrivateMethodBean() {
        return SourceFiles.inline(BEAN_FQN, """
                package com.foo;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class MyBean {
                    @Inject
                    public MyBean() {}
                    @TestTimed
                    Future<String> work() {
                        return Future.succeededFuture("ok");
                    }
                }
                """);
    }

    /**
     * A simple public bean in {@code com.foo} with a public intercepted method, used to verify
     * the proxy package even when the output-package option is active.
     */
    private static JavaFileObject publicMethodBean() {
        return SourceFiles.inline(BEAN_FQN, """
                package com.foo;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class MyBean {
                    @Inject
                    public MyBean() {}
                    @TestTimed
                    public Future<String> work() {
                        return Future.succeededFuture("ok");
                    }
                }
                """);
    }

    // --- Proxy package tests ---

    @Nested
    @DisplayName("proxy package is always the bean's package")
    class ProxyPackage {

        @Test
        @DisplayName("without the output-package option, proxy lands in the bean's package")
        void withoutOptionProxyIsInBeanPackage() {
            ProcessorTestHarness.run(new AopProcessor(), publicMethodBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(PROXY_FQN, "package com.foo");
        }

        @Test
        @DisplayName("WITH the output-package option set, proxy still lands in the bean's package (not the override)")
        void withOptionProxyIsStillInBeanPackage() {
            ProcessorTestHarness.run(
                            new AopProcessor(),
                            Map.of(CodegenContext.OPTION_OUTPUT_PACKAGE, OUTPUT_PACKAGE),
                            publicMethodBean())
                    .assertSuccess()
                    // The proxy file must exist at the bean-package FQN (com.foo.MyBean$AopProxy)
                    // and contain the correct package declaration — if the proxy were generated in
                    // com.generated instead, the assertGeneratedSourceContains below would fail with
                    // "no generated source file for com.foo.MyBean$AopProxy".
                    .assertGeneratedSourceContains(PROXY_FQN, "package com.foo")
                    .assertGeneratedSourceContains(PROXY_FQN, "extends MyBean");
        }

        @Test
        @DisplayName("WITH the output-package option, GeneratedAopModule lands in the override package")
        void withOptionModuleIsInOverridePackage() {
            String moduleFqn = OUTPUT_PACKAGE + ".GeneratedAopModule";
            ProcessorTestHarness.run(
                            new AopProcessor(),
                            Map.of(CodegenContext.OPTION_OUTPUT_PACKAGE, OUTPUT_PACKAGE),
                            publicMethodBean())
                    .assertSuccess()
                    // The module must be in the override package
                    .assertGeneratedSourceContains(moduleFqn, "package " + OUTPUT_PACKAGE)
                    // The module must have the @Binds for MyBean (the bound type)
                    .assertGeneratedSourceContains(moduleFqn, "@Binds")
                    .assertGeneratedSourceContains(moduleFqn, "MyBean")
                    // The module must reference the proxy type (MyBean$AopProxy simple name)
                    .assertGeneratedSourceContains(moduleFqn, "MyBean$AopProxy");
        }
    }

    // --- Package-private member compilation test ---

    @Nested
    @DisplayName("proxy can call super on package-private members")
    class PackagePrivateAccess {

        @Test
        @DisplayName("a package-private intercepted method with the proxy co-located in the bean's package compiles")
        void packagePrivateInterceptedMethodCompiles() {
            // Without the output-package option — the proxy always lands in the bean's own
            // package where the package-private method is accessible.
            ProcessorTestHarness.run(new AopProcessor(), packagePrivateMethodBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(PROXY_FQN, "package com.foo")
                    .assertGeneratedSourceContains(PROXY_FQN, "super.work(");
        }
    }
}
