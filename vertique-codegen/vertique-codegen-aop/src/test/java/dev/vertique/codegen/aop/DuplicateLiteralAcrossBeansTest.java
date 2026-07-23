// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.testing.compile.Compilation;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED reproduction for Bug F2 (second half): the {@code <Ann>$Literal} class is emitted inside
 * {@code AopProxyEmitter.emit}, which the processor calls once PER BEAN. When two beans in the same
 * compilation each carry the same aspect annotation ({@code @TestTagged}), the emitter writes
 * {@code TestTagged$Literal} twice through the {@code Filer}, producing an
 * "Attempt to recreate a file" error and failing the whole compilation.
 *
 * <p>The literal is per-aspect-type, not per-bean — it should be emitted at most once per
 * compilation. This test asserts the compilation SUCCEEDS (no duplicate Filer output) and that no
 * "recreate a file" diagnostic is present. Currently RED: the duplicate {@code TestTagged$Literal}
 * write aborts the compilation.
 */
class DuplicateLiteralAcrossBeansTest {

    /** First bean carrying a {@code Future}-returning {@code @TestTagged} method. */
    private static JavaFileObject beanOne() {
        return SourceFiles.inline("com.example.Alpha", """
                package com.example;
                import dev.vertique.codegen.aop.TestTagged;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Alpha {
                    @Inject
                    public Alpha() {}
                    @TestTagged(tags = {"x"})
                    public Future<String> work() {
                        return Future.succeededFuture("a");
                    }
                }
                """);
    }

    /** Second bean carrying the same aspect annotation on its own {@code Future}-returning method. */
    private static JavaFileObject beanTwo() {
        return SourceFiles.inline("com.example.Beta", """
                package com.example;
                import dev.vertique.codegen.aop.TestTagged;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Beta {
                    @Inject
                    public Beta() {}
                    @TestTagged(tags = {"y"})
                    public Future<String> work() {
                        return Future.succeededFuture("b");
                    }
                }
                """);
    }

    @Test
    @DisplayName("two beans sharing one aspect annotation do not duplicate the TestTagged$Literal Filer output")
    void sharedAspectAcrossBeansEmitsLiteralOnce() {
        var result = ProcessorTestHarness.run(new AopProcessor(), beanOne(), beanTwo());

        Compilation compilation = result.compilation();
        boolean recreateError = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null && msg.contains("recreate a file"));
        assertFalse(
                recreateError,
                "the per-aspect TestTagged$Literal must be emitted at most once per compilation — "
                        + "F2 writes it once per bean, triggering 'Attempt to recreate a file'");

        // The compilation as a whole must succeed (no duplicate-type / recreate-file abort).
        result.assertSuccess();
    }
}
