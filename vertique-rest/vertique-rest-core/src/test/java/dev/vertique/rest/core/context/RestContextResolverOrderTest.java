// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RestContextResolver} participates in the {@link OrderedExtension} ordering
 * contract — phase dominates priority, lower priority sorts first within a phase, and tie-breaks
 * use {@link OrderedExtension#orderKey()} (the fully-qualified class name by default) rather than
 * insertion order.
 */
class RestContextResolverOrderTest {

    /**
     * Minimal test double for {@link RestContextResolver} with configurable phase and priority.
     *
     * <p>{@link #orderKey()} is not overridden, so it defaults to this class's fully-qualified
     * name. The {@link #equalPriorityFqcnTie()} test uses a second distinct nested class
     * ({@link AnotherTestResolver}) to produce two different default order keys.
     */
    private static final class TestResolver implements RestContextResolver {

        private final int priority;
        private final ExtensionPhase phase;

        TestResolver(int priority, ExtensionPhase phase) {
            this.priority = priority;
            this.phase = phase;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
            return Optional.empty();
        }
    }

    /**
     * Second distinct nested class used to produce a different {@link OrderedExtension#orderKey()}
     * value (defaults to its own FQCN) at equal phase and priority, enabling the FQCN tie-break
     * assertion in {@link #equalPriorityFqcnTie()}.
     *
     * <p>FQCN of this class contains {@code "Another"} which sorts before {@code "Test"}, so an
     * instance of this class must sort before an instance of {@link TestResolver} when phase and
     * priority are equal.
     */
    private static final class AnotherTestResolver implements RestContextResolver {

        @Override
        public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("lower priority sorts first within the same phase")
    void chainOrder() {
        TestResolver low = new TestResolver(50, ExtensionPhase.APPLICATION);
        TestResolver high = new TestResolver(150, ExtensionPhase.APPLICATION);

        List<RestContextResolver> resolvers = new ArrayList<>(List.of(high, low));
        resolvers.sort(OrderedExtension.comparator());

        assertSame(low, resolvers.get(0), "priority 50 must sort before priority 150");
        assertSame(high, resolvers.get(1));
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates priority: Integer.MAX_VALUE beats APPLICATION Integer.MIN_VALUE")
    void phaseDominatesPriority() {
        TestResolver systemFirst = new TestResolver(Integer.MAX_VALUE, ExtensionPhase.SYSTEM_FIRST);
        TestResolver application = new TestResolver(Integer.MIN_VALUE, ExtensionPhase.APPLICATION);

        List<RestContextResolver> resolvers = new ArrayList<>(List.of(application, systemFirst));
        resolvers.sort(OrderedExtension.comparator());

        assertSame(systemFirst, resolvers.get(0), "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        assertSame(application, resolvers.get(1));
    }

    @Test
    @DisplayName("equal phase and priority: tie broken deterministically by fully-qualified class name")
    void equalPriorityFqcnTie() {
        // AnotherTestResolver FQCN ends with "Another..." < "Test..." alphabetically,
        // so AnotherTestResolver must sort before TestResolver at equal phase + priority.
        AnotherTestResolver another = new AnotherTestResolver();
        TestResolver test = new TestResolver(0, ExtensionPhase.APPLICATION);

        List<RestContextResolver> resolvers = new ArrayList<>(List.of(test, another));
        resolvers.sort(OrderedExtension.comparator());

        assertSame(another, resolvers.get(0), "AnotherTestResolver FQCN sorts before TestResolver FQCN");
        assertSame(test, resolvers.get(1));
    }
}
