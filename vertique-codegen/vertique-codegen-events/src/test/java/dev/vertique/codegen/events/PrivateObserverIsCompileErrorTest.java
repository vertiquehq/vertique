// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link EventsProcessor} rejects observer methods that are not {@code public}
 * (W2 — observer visibility).
 *
 * <p>The generated {@code GeneratedEventsModule} contains a static {@code @Provides @IntoSet}
 * method whose body is {@code e -> bean.method((EventType) e)}. That lambda lives in the
 * module's package. A {@code private} observer method can never be called from there
 * (always causes a generated-source compile error). A package-private observer also fails when
 * {@code GeneratedEventsModule} is in a different package from the declaring bean.
 *
 * <p>The v1 rule: the observer method must be {@code public}. The processor must emit a
 * {@link dev.vertique.codegen.Diagnostics#observerMustBeAccessible} error and skip emission
 * for the offending method.
 *
 * <p><strong>RED</strong>: before the fix the processor silently generates the lambda without
 * checking visibility, producing an uncompilable or incorrectly empty module. After the fix,
 * the compilation fails with a diagnostic containing {@code "accessible"}.
 */
class PrivateObserverIsCompileErrorTest {

    /** An event type that can be observed. */
    private static JavaFileObject event() {
        return SourceFiles.inline("com.example.Signal", """
                package com.example;
                public class Signal {
                    public Signal() {}
                }
                """);
    }

    @Test
    @DisplayName("private void on(@Observes X e) is rejected with an accessibility diagnostic")
    void privateObserverIsRejected() {
        // Given: a bean with a private observer method
        // When: EventsProcessor runs
        // Then: compilation fails and the diagnostic contains "accessible"
        JavaFileObject observer = SourceFiles.inline("com.example.PrivateObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class PrivateObserver {
                    private void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer)
                .assertFailed()
                .assertErrorMessage("accessible");
    }

    @Test
    @DisplayName("protected void on(@Observes X e) is rejected with an accessibility diagnostic")
    void protectedObserverIsRejected() {
        // Given: a bean with a protected observer method
        // When: EventsProcessor runs
        // Then: compilation fails and the diagnostic contains "accessible"
        JavaFileObject observer = SourceFiles.inline("com.example.ProtectedObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class ProtectedObserver {
                    protected void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer)
                .assertFailed()
                .assertErrorMessage("accessible");
    }

    @Test
    @DisplayName("package-private void on(@Observes X e) is rejected with an accessibility diagnostic")
    void packagePrivateObserverIsRejected() {
        // Given: a bean with a package-private observer method (no modifier)
        // When: EventsProcessor runs
        // Then: compilation fails and the diagnostic contains "accessible"
        JavaFileObject observer = SourceFiles.inline("com.example.PackagePrivateObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class PackagePrivateObserver {
                    void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer)
                .assertFailed()
                .assertErrorMessage("accessible");
    }

    @Test
    @DisplayName("public void on(@Observes X e) — public observer — succeeds without accessibility error")
    void publicObserverIsAccepted() {
        // Given: a bean with a public observer method
        // When: EventsProcessor runs
        // Then: compilation succeeds (no accessibility diagnostic)
        JavaFileObject observer = SourceFiles.inline("com.example.PublicObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class PublicObserver {
                    public void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer).assertSuccess();
    }
}
