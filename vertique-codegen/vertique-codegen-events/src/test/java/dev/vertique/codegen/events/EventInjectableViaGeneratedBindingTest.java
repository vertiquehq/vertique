// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link EventsProcessor} makes {@code Event<X>} injectable for an <em>observed</em> event
 * type by generating a concrete {@code X$Event extends Event<X>} publisher and a {@code @Binds
 * Event<X>} in the generated {@code GeneratedEventsModule} (plan §3 / §3a, FR-013-11).
 *
 * <p>Fixture: a bean injects {@code Event<OrderCreated>} through its {@code @Inject} constructor, and a
 * separate observer bean declares {@code onOrder(@Observes OrderCreated e)}. The event type therefore
 * enters the inventory from <em>both</em> the {@code @Observes} scan and the {@code Event<T>} injection
 * scan. The generated module must bind {@code Event<OrderCreated>} to the generated
 * {@code OrderCreated$Event} publisher.
 *
 * <p><strong>Red:</strong> the slice-3.2 scaffold {@link EventsProcessor} is a no-op, so neither the
 * {@code OrderCreated$Event} publisher nor the {@code GeneratedEventsModule} is generated and every
 * assertion below fails.
 */
class EventInjectableViaGeneratedBindingTest {

    private static final String MODULE_FQN = "com.example.GeneratedEventsModule";
    private static final String PUBLISHER_FQN = "com.example.OrderCreated$Event";

    /** The event type fired and injected. */
    private static JavaFileObject orderCreatedEvent() {
        return SourceFiles.inline("com.example.OrderCreated", """
                package com.example;
                public class OrderCreated {
                    public final String id;
                    public OrderCreated(String id) {
                        this.id = id;
                    }
                }
                """);
    }

    /** A bean that injects the {@code Event<OrderCreated>} publisher via its constructor. */
    private static JavaFileObject publishingBean() {
        return SourceFiles.inline("com.example.OrderService", """
                package com.example;
                import dev.vertique.events.Event;
                import jakarta.inject.Inject;
                public class OrderService {
                    private final Event<OrderCreated> events;
                    @Inject
                    public OrderService(Event<OrderCreated> events) {
                        this.events = events;
                    }
                }
                """);
    }

    /** A bean declaring an observer method for {@code OrderCreated}. */
    private static JavaFileObject observerBean() {
        return SourceFiles.inline("com.example.OrderObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class OrderObserver {
                    public void onOrder(@Observes OrderCreated e) {
                        // observe
                    }
                }
                """);
    }

    @Test
    @DisplayName("an observed, constructor-injected Event<OrderCreated> yields an OrderCreated$Event publisher")
    void generatesPublisherForObservedInjectedType() {
        ProcessorTestHarness.run(new EventsProcessor(), orderCreatedEvent(), publishingBean(), observerBean())
                .assertSuccess()
                .assertGeneratedSourceContains(PUBLISHER_FQN, "extends Event<OrderCreated>")
                .assertGeneratedSourceContains(PUBLISHER_FQN, "@Inject");
    }

    @Test
    @DisplayName("GeneratedEventsModule binds Event<OrderCreated> to the generated publisher")
    void generatedModuleBindsEventForObservedType() {
        ProcessorTestHarness.run(new EventsProcessor(), orderCreatedEvent(), publishingBean(), observerBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "Event<OrderCreated>")
                .assertGeneratedSourceContains(MODULE_FQN, "OrderCreated$Event");
    }
}
