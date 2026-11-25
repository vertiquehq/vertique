// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-test verifying that {@link EventsProcessor} emits a {@code @Provides @IntoSet
 * ObserverRegistration} method in {@code GeneratedEventsModule} for each {@code @Observes} observer
 * method.
 *
 * <p>Besides the publisher bindings ({@code @Binds Event<X>}) and the concrete {@code X$Event}
 * publisher, the {@link EventsProcessor} must emit an {@code ObserverRegistration} provider:
 * {@link #generatedEventsModuleEmitsIntoSetObserverRegistration()} fails if the generated module
 * source does not contain {@code @IntoSet} or {@code ObserverRegistration}.
 */
class EventsProcessorEmitTest {

    private static final String MODULE_FQN = "com.example.GeneratedEventsModule";

    /** The event type that is observed. */
    private static JavaFileObject orderPlacedEvent() {
        return SourceFiles.inline("com.example.OrderPlaced", """
                        package com.example;
                        public class OrderPlaced {
                            public final String orderId;
                            public OrderPlaced(String orderId) {
                                this.orderId = orderId;
                            }
                        }
                        """);
    }

    /**
     * A bean that observes {@code OrderPlaced} events. The parameter carries {@code @Observes} and the
     * method returns {@code void} — the valid v1 observer shape.
     */
    private static JavaFileObject orderObserverBean() {
        return SourceFiles.inline("com.example.OrderObserver", """
                        package com.example;
                        import dev.vertique.events.Observes;
                        public class OrderObserver {
                            public void onOrder(@Observes OrderPlaced event) {
                                // observer method
                            }
                        }
                        """);
    }

    /**
     * A bean whose {@code @Inject} constructor injects {@code Event<OrderPlaced>}, placing the event
     * type in the inventory via the constructor-injection scan path.
     */
    private static JavaFileObject publishingBean() {
        return SourceFiles.inline("com.example.OrderService", """
                        package com.example;
                        import dev.vertique.events.Event;
                        import jakarta.inject.Inject;
                        public class OrderService {
                            private final Event<OrderPlaced> events;
                            @Inject
                            public OrderService(Event<OrderPlaced> events) {
                                this.events = events;
                            }
                        }
                        """);
    }

    @Test
    @DisplayName("GeneratedEventsModule contains @IntoSet and ObserverRegistration for an @Observes observer method")
    void generatedEventsModuleEmitsIntoSetObserverRegistration() {
        // Given: an observer bean with onOrder(@Observes OrderPlaced e)
        // When: the EventsProcessor runs
        // Then: the GeneratedEventsModule source contains @IntoSet, ObserverRegistration,
        //       and references the observer bean + method (proving a registration provider was emitted).
        ProcessorTestHarness.run(new EventsProcessor(), orderPlacedEvent(), orderObserverBean(), publishingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "@IntoSet")
                .assertGeneratedSourceContains(MODULE_FQN, "ObserverRegistration")
                .assertGeneratedSourceContains(MODULE_FQN, "OrderObserver")
                .assertGeneratedSourceContains(MODULE_FQN, "onOrder");
    }

    @Test
    @DisplayName("GeneratedEventsModule @IntoSet provider is a @Provides method returning ObserverRegistration")
    void generatedEventsModuleIntoSetProviderIsProvides() {
        // Given: an observer bean
        // When: the EventsProcessor runs
        // Then: the module source contains both @Provides and @IntoSet (not abstract @Binds)
        //       because an ObserverRegistration must be constructed with the bean instance,
        //       priority, and a lambda — requiring a concrete @Provides method.
        ProcessorTestHarness.run(new EventsProcessor(), orderPlacedEvent(), orderObserverBean(), publishingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "@Provides")
                .assertGeneratedSourceContains(MODULE_FQN, "@IntoSet")
                .assertGeneratedSourceContains(MODULE_FQN, "ObserverRegistration");
    }

    @Test
    @DisplayName("generated ObserverRegistration provider references the event type class literal (reflection-free)")
    void generatedObserverRegistrationReferencesEventTypeClassLiteral() {
        // Given: an observer method for OrderPlaced
        // When: the EventsProcessor runs
        // Then: the generated module source references OrderPlaced.class (the event type is baked as
        //       a constant, not discovered via reflection at runtime).
        ProcessorTestHarness.run(new EventsProcessor(), orderPlacedEvent(), orderObserverBean(), publishingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "OrderPlaced.class");
    }
}
