// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link EventsProcessor} rejects a public observer method whose declaring class the
 * generated module cannot reach.
 *
 * <p>The generated {@code GeneratedEventsModule} lives in the longest common prefix of the event
 * packages, which need not be the bean's package, so a package-private bean is unreachable from it.
 */
class ObserverOnInaccessibleClassIsCompileErrorTest {

    private static JavaFileObject event() {
        return SourceFiles.inline("com.example.events.Signal", """
                package com.example.events;
                public class Signal {
                    public Signal() {}
                }
                """);
    }

    @Test
    @DisplayName("public observer method on a package-private bean in another package is rejected at the observer")
    void publicObserverOnPackagePrivateBeanIsRejected() {
        JavaFileObject observer = SourceFiles.inline("com.example.beans.HiddenObserver", """
                package com.example.beans;
                import com.example.events.Signal;
                import dev.vertique.events.Observes;
                class HiddenObserver {
                    public void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer)
                .assertFailed()
                .assertErrorMessage("accessible");
    }

    @Test
    @DisplayName("public observer method on a package-private bean in the generated module's package is accepted")
    void publicObserverOnPackagePrivateBeanInModulePackageIsAccepted() {
        JavaFileObject observer = SourceFiles.inline("com.example.events.SamePackageObserver", """
                package com.example.events;
                import dev.vertique.events.Observes;
                class SamePackageObserver {
                    public void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event(), observer).assertSuccess();
    }
}
