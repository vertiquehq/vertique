// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that two event types sharing the same simple name but living in distinct packages
 * (P3-W3) do not produce colliding publisher class names or duplicate {@code @Binds}/{@code @Provides}
 * method names in {@code GeneratedEventsModule}.
 *
 * <p>Before the fix, both {@code com.acme.OrderCreated} and {@code com.shop.OrderCreated} mapped to
 * the same publisher simple name {@code OrderCreated$Event} (in the LCP package) and the same bind
 * method name {@code bindOrderCreatedEvent}. The fix emits each publisher into its event type's
 * own package, making the FQNs distinct.
 *
 * <p><strong>Red failure mode:</strong> the harness raises a "duplicate class" error or the
 * generated module contains two methods with the same name. After the fix, both publishers are
 * emitted in their respective event packages and the module bind method names are disambiguated.
 */
class SameSimpleNameEventTypesTest {

    // Module package = LCP of the two event-type packages ("com.acme" ∩ "com.shop" = "com")
    private static final String MODULE_FQN = "com.GeneratedEventsModule";
    // Publishers are emitted in the event type's own package
    private static final String ACME_PUBLISHER_FQN = "com.acme.OrderCreated$Event";
    private static final String SHOP_PUBLISHER_FQN = "com.shop.OrderCreated$Event";

    /** First OrderCreated — in package com.acme. */
    private static JavaFileObject acmeOrderCreated() {
        return SourceFiles.inline("com.acme.OrderCreated", """
                package com.acme;
                public class OrderCreated {
                    public OrderCreated() {}
                }
                """);
    }

    /** Second OrderCreated — distinct FQN, in package com.shop. */
    private static JavaFileObject shopOrderCreated() {
        return SourceFiles.inline("com.shop.OrderCreated", """
                package com.shop;
                public class OrderCreated {
                    public OrderCreated() {}
                }
                """);
    }

    /** A bean that injects both publishers. */
    private static JavaFileObject publishingBean() {
        return SourceFiles.inline("com.example.OrderPublisher", """
                package com.example;
                import dev.vertique.events.Event;
                import jakarta.inject.Inject;
                public class OrderPublisher {
                    private final Event<com.acme.OrderCreated> acme;
                    private final Event<com.shop.OrderCreated> shop;
                    @Inject
                    public OrderPublisher(Event<com.acme.OrderCreated> acme, Event<com.shop.OrderCreated> shop) {
                        this.acme = acme;
                        this.shop = shop;
                    }
                }
                """);
    }

    /** A bean that observes both event types. */
    private static JavaFileObject observerBean() {
        return SourceFiles.inline("com.example.OrderObserver", """
                package com.example;
                import dev.vertique.events.Observes;
                public class OrderObserver {
                    public void onAcme(@Observes com.acme.OrderCreated event) {}
                    public void onShop(@Observes com.shop.OrderCreated event) {}
                }
                """);
    }

    @Test
    @DisplayName("two event types with the same simple name in different packages compile — distinct publishers")
    void sameSimpleNameEventsProduceDistinctPublishers() {
        // Given: com.acme.OrderCreated and com.shop.OrderCreated (same simple name)
        // When: EventsProcessor runs
        // Then: compilation succeeds, both publishers are emitted in their own package
        // JavaPoet emits the simple name when the event type is in the same package as the publisher.
        // com.acme.OrderCreated$Event is in package com.acme — the extends clause uses "OrderCreated".
        // com.shop.OrderCreated$Event is in package com.shop — similarly "OrderCreated".
        ProcessorTestHarness.run(new EventsProcessor(), acmeOrderCreated(), shopOrderCreated(), publishingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(ACME_PUBLISHER_FQN, "extends Event<OrderCreated>")
                .assertGeneratedSourceContains(SHOP_PUBLISHER_FQN, "extends Event<OrderCreated>");
    }

    @Test
    @DisplayName("two same-simple-name event observers compile — distinct provider method names in module")
    void sameSimpleNameObservedEventsProduceDistinctBinds() {
        // Given: both event types are also observed
        // When: EventsProcessor runs
        // Then: compilation succeeds, the module references both Event<com.acme.OrderCreated>
        //       and Event<com.shop.OrderCreated> with no duplicate method names
        ProcessorTestHarness.run(
                        new EventsProcessor(), acmeOrderCreated(), shopOrderCreated(), publishingBean(), observerBean())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "com.acme.OrderCreated")
                .assertGeneratedSourceContains(MODULE_FQN, "com.shop.OrderCreated");
    }
}
