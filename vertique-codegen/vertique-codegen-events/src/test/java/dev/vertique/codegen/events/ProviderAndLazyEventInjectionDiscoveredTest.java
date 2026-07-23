// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the inventory scan unwraps the standard Dagger wrappers {@code Provider<Event<T>>} and
 * {@code Lazy<Event<T>>} at constructor {@code @Inject} sites (plan §3 / §3a frozen decision: the
 * inventory includes {@code Event<T>} constructor-injection type args <em>incl. Provider/Lazy
 * wrappers</em>).
 *
 * <p>Fixture: a single bean injects {@code Provider<Event<X>>} and {@code Lazy<Event<Y>>} through its
 * {@code @Inject} constructor (neither {@code X} nor {@code Y} is observed). Both {@code X} and
 * {@code Y} must enter the inventory and receive a generated publisher + binding.
 *
 * <p><strong>Red:</strong> the slice-3.2 scaffold {@link EventsProcessor} is a no-op, so neither
 * {@code X$Event} nor {@code Y$Event} is generated.
 */
class ProviderAndLazyEventInjectionDiscoveredTest {

    private static final String MODULE_FQN = "com.example.GeneratedEventsModule";
    private static final String X_PUBLISHER_FQN = "com.example.WrappedX$Event";
    private static final String Y_PUBLISHER_FQN = "com.example.WrappedY$Event";

    /** Two unobserved event types injected only through Provider/Lazy wrappers. */
    private static JavaFileObject eventX() {
        return SourceFiles.inline("com.example.WrappedX", """
                package com.example;
                public class WrappedX {
                    public WrappedX() {}
                }
                """);
    }

    private static JavaFileObject eventY() {
        return SourceFiles.inline("com.example.WrappedY", """
                package com.example;
                public class WrappedY {
                    public WrappedY() {}
                }
                """);
    }

    /** A bean injecting {@code Provider<Event<WrappedX>>} and {@code Lazy<Event<WrappedY>>}. */
    private static JavaFileObject wrappingBean() {
        return SourceFiles.inline("com.example.WrapperConsumer", """
                package com.example;
                import dagger.Lazy;
                import dev.vertique.events.Event;
                import jakarta.inject.Inject;
                import jakarta.inject.Provider;
                public class WrapperConsumer {
                    private final Provider<Event<WrappedX>> x;
                    private final Lazy<Event<WrappedY>> y;
                    @Inject
                    public WrapperConsumer(Provider<Event<WrappedX>> x, Lazy<Event<WrappedY>> y) {
                        this.x = x;
                        this.y = y;
                    }
                }
                """);
    }

    @Test
    @DisplayName("Provider<Event<WrappedX>> is unwrapped — a WrappedX$Event publisher is generated")
    void providerWrapperIsUnwrapped() {
        ProcessorTestHarness.run(new EventsProcessor(), eventX(), eventY(), wrappingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(X_PUBLISHER_FQN, "extends Event<WrappedX>")
                .assertGeneratedSourceContains(MODULE_FQN, "Event<WrappedX>");
    }

    @Test
    @DisplayName("Lazy<Event<WrappedY>> is unwrapped — a WrappedY$Event publisher is generated")
    void lazyWrapperIsUnwrapped() {
        ProcessorTestHarness.run(new EventsProcessor(), eventX(), eventY(), wrappingBean())
                .assertSuccess()
                .assertGeneratedSourceContains(Y_PUBLISHER_FQN, "extends Event<WrappedY>")
                .assertGeneratedSourceContains(MODULE_FQN, "Event<WrappedY>");
    }
}
