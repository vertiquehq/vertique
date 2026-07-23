// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a constructor-injected {@code Event<Unobserved>} with <em>no</em> matching observer
 * still gets a generated publisher and a {@code @Binds Event<Unobserved>}, so the injection resolves
 * (plan §3 frozen decision: an unobserved fired type still gets a valid no-op publisher —
 * {@code fire()} matches zero observers and completes immediately).
 *
 * <p>Fixture: a bean injects {@code Event<Unobserved>} through its {@code @Inject} constructor, but no
 * {@code @Observes Unobserved} observer exists anywhere in the compilation. The inventory must
 * therefore include {@code Unobserved} <em>solely</em> from the {@code Event<T>} injection scan, and
 * the generated module must still bind {@code Event<Unobserved>}.
 *
 * <p><strong>Red:</strong> the slice-3.2 scaffold {@link EventsProcessor} is a no-op, so no
 * {@code Unobserved$Event} publisher and no {@code GeneratedEventsModule} are generated.
 */
class UnobservedEventTypeInjectsNoOpPublisherTest {

    private static final String MODULE_FQN = "com.example.GeneratedEventsModule";
    private static final String PUBLISHER_FQN = "com.example.Unobserved$Event";

    /** An event type that is injected but never observed. */
    private static JavaFileObject unobservedEvent() {
        return SourceFiles.inline("com.example.Unobserved", """
                package com.example;
                public class Unobserved {
                    public Unobserved() {}
                }
                """);
    }

    /** A bean that injects {@code Event<Unobserved>} but for which no observer exists. */
    private static JavaFileObject injectingBean() {
        return SourceFiles.inline("com.example.UnobservedPublisher", """
                package com.example;
                import dev.vertique.events.Event;
                import jakarta.inject.Inject;
                public class UnobservedPublisher {
                    private final Event<Unobserved> events;
                    @Inject
                    public UnobservedPublisher(Event<Unobserved> events) {
                        this.events = events;
                    }
                }
                """);
    }

    @Test
    @DisplayName("an unobserved but injected Event<Unobserved> still yields an Unobserved$Event publisher")
    void generatesPublisherForUnobservedInjectedType() {
        ProcessorTestHarness.run(new EventsProcessor(), unobservedEvent(), injectingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(PUBLISHER_FQN, "extends Event<Unobserved>");
    }

    @Test
    @DisplayName("GeneratedEventsModule still binds Event<Unobserved> so injection resolves (no-op at runtime)")
    void generatedModuleBindsUnobservedEvent() {
        ProcessorTestHarness.run(new EventsProcessor(), unobservedEvent(), injectingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "Event<Unobserved>")
                .assertGeneratedSourceContains(MODULE_FQN, "Unobserved$Event");
    }
}
