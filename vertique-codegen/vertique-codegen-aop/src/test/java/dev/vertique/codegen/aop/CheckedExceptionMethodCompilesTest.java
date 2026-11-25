// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A SYNC intercepted method declaring a checked exception yields a compilable proxy.
 *
 * <p>For a sync method {@code String read() throws java.io.IOException}, {@code
 * AopProxyEmitter#buildOverride} emits the terminal as a lambda whose body invokes {@code
 * super.read()}. That call declares {@code throws IOException}, but the {@code Supplier}-shaped
 * terminal cannot throw a checked exception, so the emitted terminal must handle it; otherwise the
 * proxy source fails with "unreported exception IOException; must be caught or declared to be
 * thrown".
 *
 * <p>The test asserts compilation succeeds.
 */
class CheckedExceptionMethodCompilesTest {

    /** A bean with a SYNC {@code String read() throws IOException} method carrying {@code @TestTimed}. */
    private static JavaFileObject readerBean() {
        return SourceFiles.inline("com.example.Reader", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import java.io.IOException;
                import jakarta.inject.Inject;
                public class Reader {
                    @Inject
                    public Reader() {}
                    @TestTimed
                    public String read() throws IOException {
                        return "data";
                    }
                }
                """);
    }

    @Test
    @DisplayName("a sync @TestTimed method declaring a checked exception generates a proxy that compiles")
    void checkedExceptionSyncMethodCompiles() {
        ProcessorTestHarness.run(new AopProcessor(), readerBean()).assertSuccess();
    }
}
