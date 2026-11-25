// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a bean with two {@code @Observes} overloads on the same method name produces
 * distinct {@code @Provides @IntoSet ObserverRegistration} method names in {@code GeneratedEventsModule}.
 *
 * <p>Mapping both overloads to {@code provideMyBeanOnEventObserverRegistration} would put two
 * methods with the same name in the generated module source and make it uncompilable. The two
 * methods must have distinct names (event-simple-name + ordinal disambiguator) and the compilation
 * must succeed.
 */
class OverloadedObserverMethodsTest {

    private static final String MODULE_FQN = "com.example.GeneratedEventsModule";

    /** First event type. */
    private static JavaFileObject eventA() {
        return SourceFiles.inline("com.example.EventA", """
                package com.example;
                public class EventA {
                    public EventA() {}
                }
                """);
    }

    /** Second event type — distinct FQN, same package as EventA. */
    private static JavaFileObject eventB() {
        return SourceFiles.inline("com.example.EventB", """
                package com.example;
                public class EventB {
                    public EventB() {}
                }
                """);
    }

    /**
     * A bean that declares two {@code @Observes} methods with the same simple name {@code on}
     * but observing different event types. Both are valid single-parameter void observers.
     */
    private static JavaFileObject dualObserverBean() {
        return SourceFiles.inline("com.example.MyHandler", """
                package com.example;
                import dev.vertique.events.Observes;
                public class MyHandler {
                    public void on(@Observes EventA event) {}
                    public void on(@Observes EventB event) {}
                }
                """);
    }

    @Test
    @DisplayName("two @Observes overloads in one bean compile — provider method names are distinct")
    void overloadedObserverMethodsProduceDistinctProviderNames() {
        // Given: a bean with two void on(@Observes EventA) and void on(@Observes EventB) methods
        // When: EventsProcessor runs
        // Then: compilation succeeds and the module contains two distinct @IntoSet provider methods
        //       (both reference ObserverRegistration, both reference the bean class MyHandler)
        ProcessorTestHarness.run(new EventsProcessor(), eventA(), eventB(), dualObserverBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "ObserverRegistration")
                .assertGeneratedSourceContains(MODULE_FQN, "MyHandler");
    }

    @Test
    @DisplayName("each overload's provider method references its specific event type")
    void eachOverloadProviderReferencesItsOwnEventType() {
        // Given: one observer for EventA, one for EventB, same method name
        // When: EventsProcessor runs
        // Then: the module source references both EventA.class and EventB.class
        ProcessorTestHarness.run(new EventsProcessor(), eventA(), eventB(), dualObserverBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "EventA.class")
                .assertGeneratedSourceContains(MODULE_FQN, "EventB.class");
    }
}
