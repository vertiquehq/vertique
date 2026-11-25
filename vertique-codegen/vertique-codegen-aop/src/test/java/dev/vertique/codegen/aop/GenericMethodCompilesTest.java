// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A generic intercepted method yields a compilable proxy.
 *
 * <p>For a generic method {@code <T> io.vertx.core.Future<T> echo(T value)},
 * {@code AopProxyEmitter#buildOverride} copies the {@code T}-referencing parameter and return types
 * into the override and must also emit the method's own type parameters (the {@code <T>}
 * declaration). Without it the override references an undeclared type variable {@code T} and the
 * proxy source fails with "cannot find symbol: class T".
 *
 * <p>The test asserts compilation succeeds.
 */
class GenericMethodCompilesTest {

    /** A bean with a generic {@code <T> Future<T> echo(T value)} method carrying {@code @TestTimed}. */
    private static JavaFileObject echoBean() {
        return SourceFiles.inline("com.example.Echoer", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Echoer {
                    @Inject
                    public Echoer() {}
                    @TestTimed
                    public <T> Future<T> echo(T value) {
                        return Future.succeededFuture(value);
                    }
                }
                """);
    }

    @Test
    @DisplayName("a generic @TestTimed method generates a proxy that declares its type parameters and compiles")
    void genericMethodCompiles() {
        ProcessorTestHarness.run(new AopProcessor(), echoBean()).assertSuccess();
    }
}
