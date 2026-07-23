// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Behavior test for ordered fan-out by {@link Observes#priority()}.
 *
 * <p>RED in slice 3.1: both {@link ObserverRegistry#match(Class)} and {@link Event#fire(Object)} are
 * failing stubs. The green step turns this test green by matching observers and dispatching them in
 * ascending-priority order via {@code Combinators.foldSequential}.
 */
@ExtendWith(VertxExtension.class)
class PriorityOrderedDispatchTest {

    /** A hand-written concrete publisher standing in for the generated {@code String$Event}. */
    private static final class StringEvent extends Event<String> {
        StringEvent(ObserverRegistry registry) {
            super(registry, String.class);
        }
    }

    /**
     * Given a {@code String} event with three observers registered at priorities 100, 500 and 50,
     * when {@code fire} is called, then the observers execute in ascending-priority order (50, then
     * 100, then 500) — sequential, visible-effect order via {@code foldSequential}.
     */
    @Test
    void observersFireInPriorityOrder() {
        List<Integer> firingOrder = new ArrayList<>();
        ObserverRegistration p100 = new ObserverRegistration(String.class, 100, event -> firingOrder.add(100));
        ObserverRegistration p500 = new ObserverRegistration(String.class, 500, event -> firingOrder.add(500));
        ObserverRegistration p50 = new ObserverRegistration(String.class, 50, event -> firingOrder.add(50));
        ObserverRegistry registry = new ObserverRegistry(Set.of(p100, p500, p50));
        StringEvent event = new StringEvent(registry);

        Future<Void> result = event.fire("hello");

        assertTrue(result.succeeded(), "fire() should complete successfully");
        assertEquals(List.of(50, 100, 500), firingOrder, "observers should fire in ascending-priority order");
    }
}
