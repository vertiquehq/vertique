// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @Observes Object} is rejected at compile time with a clear diagnostic (P3-W5).
 *
 * <p>The {@link dev.vertique.events.ObserverRegistry}'s superclass walk excludes {@code Object.class}
 * from dispatch, so an {@code @Observes Object} observer silently never fires. Allowing it would
 * mislead the author into thinking they have a catch-all observer. {@link EventsProcessor} must
 * detect this pattern at compile time and emit a
 * {@link dev.vertique.codegen.Diagnostics#observerMustNotObserveObject} error.
 *
 * <p><strong>Red failure mode:</strong> before the fix, the processor silently generates an
 * {@code ObserverRegistration} for the {@code Object} type, which never dispatches. After the fix,
 * the processor emits an explicit compile error containing {@code "Object"} and skips emission
 * for the offending method.
 */
class ObservesObjectIsCompileErrorTest {

    @Test
    @DisplayName("@Observes Object is rejected — Object-observer diagnostic emitted")
    void observesObjectIsRejectedWithClearDiagnostic() {
        // Given: a method with @Observes Object (catch-all that silently never fires)
        // When: EventsProcessor runs
        // Then: compilation fails with an error message containing "Object"
        JavaFileObject observer = SourceFiles.inline("com.example.CatchAllObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class CatchAllObserver {
                    public void onAny(@Observes Object event) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), observer).assertFailed().assertErrorMessage("Object");
    }

    @Test
    @DisplayName("a valid observer on a concrete type next to an @Observes Object triggers only the Object error")
    void validObserverNextToObjectObserverOnlyRejectsObject() {
        // Given: one valid observer and one @Observes Object in the same bean
        // When: EventsProcessor runs
        // Then: compilation fails (the Object observer is rejected) and the error mentions "Object"
        JavaFileObject event = SourceFiles.inline("com.example.Signal", """
                package com.example;
                public class Signal {}
                """);
        JavaFileObject mixedObserver = SourceFiles.inline("com.example.MixedObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class MixedObserver {
                    public void onSignal(@Observes Signal s) {}
                    public void onAny(@Observes Object e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event, mixedObserver)
                .assertFailed()
                .assertErrorMessage("Object");
    }
}
