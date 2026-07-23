// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Behavior test proving {@link Event#fire(Object)} never fails even when every observer throws.
 *
 * <p>RED in slice 3.1: {@link Event#fire(Object)} is a failing stub. The green step turns this test
 * green by swallowing-and-logging each observer throw so the returned future still completes.
 */
@ExtendWith(VertxExtension.class)
class FireNeverFailsWhenObserverThrowsTest {

    /** A hand-written concrete publisher standing in for the generated {@code String$Event}. */
    private static final class StringEvent extends Event<String> {
        StringEvent(ObserverRegistry registry) {
            super(registry, String.class);
        }
    }

    /**
     * Given a {@code String} event with two observers that both throw a {@link RuntimeException},
     * when {@code fire} is awaited, then the returned future completes successfully (not failed) —
     * the throws are swallowed-and-logged.
     */
    @Test
    void fireCompletesEvenWhenAllObserversThrow() {
        ObserverRegistration first = new ObserverRegistration(String.class, 100, event -> {
            throw new RuntimeException("observer one boom");
        });
        ObserverRegistration second = new ObserverRegistration(String.class, 200, event -> {
            throw new RuntimeException("observer two boom");
        });
        ObserverRegistry registry = new ObserverRegistry(Set.of(first, second));
        StringEvent event = new StringEvent(registry);

        Future<Void> result = event.fire("hello");

        assertTrue(result.succeeded(), "fire() should complete successfully despite throwing observers");
        assertFalse(result.failed(), "fire() must never surface an observer throw as a failed future");
    }
}
