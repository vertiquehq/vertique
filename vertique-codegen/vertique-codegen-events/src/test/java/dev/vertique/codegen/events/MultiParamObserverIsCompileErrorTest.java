// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the single-parameter rule for {@code @Observes} observer methods (P3-W2).
 *
 * <p>The event-dispatch engine invokes observers through a single-argument lambda
 * {@code e -> bean.method((EventType) e)}. A method with two parameters — e.g.
 * {@code void on(@Observes A a, Context c)} — cannot be expressed by that lambda and would
 * produce uncompilable generated source. {@link EventsProcessor} must reject such methods at
 * compile time with a {@link dev.vertique.codegen.Diagnostics#observerMustHaveSingleParameter}
 * error and skip emission for the offending method.
 *
 * <p><strong>Red failure mode:</strong> before the fix the processor emits a broken one-arg lambda
 * call and the compilation of the generated source fails (or the error is not surfaced with the
 * expected diagnostic message). After the fix, the processor emits an explicit compile error
 * containing {@code "single"} and does not attempt to generate a registration provider for the
 * invalid method.
 */
class MultiParamObserverIsCompileErrorTest {

    /** An event type. */
    private static JavaFileObject event() {
        return SourceFiles.inline("com.example.Signal", """
                package com.example;
                public class Signal {
                    public Signal() {}
                }
                """);
    }

    /** A context type used as the second parameter. */
    private static JavaFileObject contextType() {
        return SourceFiles.inline("com.example.Ctx", """
                package com.example;
                public class Ctx {
                    public Ctx() {}
                }
                """);
    }

    @Test
    @DisplayName("void on(@Observes A, Context) is rejected — single-parameter diagnostic emitted")
    void twoParamObserverWithObservesFirstIsRejected() {
        // Given: a method with @Observes on the first param and a second context param
        // When: EventsProcessor runs
        // Then: compilation fails with an error message containing "single"
        JavaFileObject observer = SourceFiles.inline("com.example.MultiParamObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class MultiParamObserver {
                    public void on(@Observes Signal s, Ctx ctx) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), contextType(), observer)
                .assertFailed()
                .assertErrorMessage("single");
    }

    @Test
    @DisplayName("two @Observes params on one method is rejected — single-parameter diagnostic emitted")
    void twoObservesParamObserverIsRejected() {
        // Given: a method with @Observes on both parameters (two @Observes annotations)
        // When: EventsProcessor runs
        // Then: compilation fails with an error message containing "single"
        JavaFileObject observer = SourceFiles.inline("com.example.DoubleObservesObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class DoubleObservesObserver {
                    public void on(@Observes Signal s, @Observes Signal s2) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), contextType(), observer)
                .assertFailed()
                .assertErrorMessage("single");
    }
}
