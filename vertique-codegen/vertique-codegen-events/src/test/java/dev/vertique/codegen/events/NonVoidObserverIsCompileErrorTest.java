// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the frozen observer-signature rule (plan §3: v1 observer methods MUST return {@code void}).
 * An observer method whose {@code @Observes} parameter is declared on a non-{@code void} method —
 * whether it returns a {@code Future} or a plain value — is a compile error, because dispatch invokes
 * the observer through a synchronous {@code Consumer<Object>} that cannot consume a return value.
 *
 * <p><strong>Red:</strong> the slice-3.2 scaffold {@link EventsProcessor} raises no diagnostic, so the
 * compilation succeeds and {@code assertFailed()} fails.
 */
class NonVoidObserverIsCompileErrorTest {

    /** An event type with an observer that wrongly returns a value. */
    private static JavaFileObject event() {
        return SourceFiles.inline("com.example.Signal", """
                package com.example;
                public class Signal {
                    public Signal() {}
                }
                """);
    }

    @Test
    @DisplayName("an observer returning Future<Void> is rejected with a void-only diagnostic")
    void futureReturnObserverIsRejected() {
        JavaFileObject observer = SourceFiles.inline("com.example.FutureObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                import io.vertx.core.Future;
                public class FutureObserver {
                    public Future<Void> onSignal(@Observes Signal s) {
                        return Future.succeededFuture();
                    }
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer)
                .assertFailed()
                .assertErrorMessage("void");
    }

    @Test
    @DisplayName("an observer returning a plain value (String) is rejected with a void-only diagnostic")
    void nonVoidReturnObserverIsRejected() {
        JavaFileObject observer = SourceFiles.inline("com.example.StringObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class StringObserver {
                    public String onSignal(@Observes Signal s) {
                        return "handled";
                    }
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer)
                .assertFailed()
                .assertErrorMessage("void");
    }
}
