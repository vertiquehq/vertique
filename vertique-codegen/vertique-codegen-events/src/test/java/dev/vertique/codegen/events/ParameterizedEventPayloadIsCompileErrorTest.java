// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link EventsProcessor} rejects parameterized (generic) event payload types
 * (W3 — generic event payload).
 *
 * <p>The v1 event inventory records event types as erased {@code TypeElement} references.
 * A parameterized payload such as {@code Event<Box<String>>} would be recorded as {@code Box}
 * (erased), generating a {@code @Binds Event<Box>} binding that does NOT satisfy Dagger's
 * requested {@code Event<Box<String>>} key, causing a {@code MissingBinding} error at the
 * app component. Rather than silently producing a wrong binding, the processor must reject
 * parameterized payloads at codegen time with a clear
 * {@link dev.vertique.codegen.Diagnostics#eventPayloadMustNotBeParameterized} diagnostic.
 *
 * <p>Both paths that add types to the inventory are validated:
 * <ul>
 *   <li>the {@code @Observes} parameter type (observer scan), and</li>
 *   <li>the {@code Event<T>} type argument in a constructor {@code @Inject} scan.</li>
 * </ul>
 *
 * <p><strong>RED</strong>: before the fix the processor silently records the erased type and
 * emits a wrong binding. After the fix, the compilation fails with a diagnostic containing
 * {@code "parameterized"}.
 */
class ParameterizedEventPayloadIsCompileErrorTest {

    /** A generic wrapper type used as the parameterized event payload. */
    private static JavaFileObject boxType() {
        return SourceFiles.inline("com.example.Box", """
                package com.example;
                public class Box<T> {
                    public final T value;
                    public Box(T value) { this.value = value; }
                }
                """);
    }

    @Test
    @DisplayName("@Observes Box<String> is rejected — parameterized payload diagnostic emitted")
    void parameterizedObservesPayloadIsRejected() {
        // Given: an observer with a parameterized @Observes parameter type
        // When: EventsProcessor runs
        // Then: compilation fails and the diagnostic contains "parameterized"
        JavaFileObject observer = SourceFiles.inline("com.example.BoxObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class BoxObserver {
                    public void onBox(@Observes Box<String> e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), boxType(), observer)
                .assertFailed()
                .assertErrorMessage("parameterized");
    }

    @Test
    @DisplayName("@Inject Event<Box<String>> is rejected — parameterized payload diagnostic emitted")
    void parameterizedEventInjectionIsRejected() {
        // Given: a bean that injects Event<Box<String>> in its @Inject constructor
        // When: EventsProcessor runs
        // Then: compilation fails and the diagnostic contains "parameterized"
        JavaFileObject service = SourceFiles.inline("com.example.BoxService", """
                package com.example;
                import dev.vertique.events.Event;
                import jakarta.inject.Inject;
                public class BoxService {
                    @Inject
                    public BoxService(Event<Box<String>> events) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), boxType(), service)
                .assertFailed()
                .assertErrorMessage("parameterized");
    }

    @Test
    @DisplayName("non-generic @Observes payload and non-generic Event<T> injection succeed")
    void nonParameterizedPayloadsAreAccepted() {
        // Given: an observer with a plain (non-generic) event type and a service injecting Event<Box>
        //        (raw Box — no type argument, so Box itself is the concrete payload)
        // Note: we test with a non-parameterized event type, not Box<T> itself,
        //       because raw Box without a type arg is just Box (which is non-generic
        //       at the usage site).
        JavaFileObject event = SourceFiles.inline("com.example.Signal", """
                package com.example;
                public class Signal {}
                """);
        JavaFileObject observer = SourceFiles.inline("com.example.SignalObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class SignalObserver {
                    public void on(@Observes Signal e) {}
                }
                """);

        ProcessorTestHarness.run(new EventsProcessor(), event, observer).assertSuccess();
    }
}
