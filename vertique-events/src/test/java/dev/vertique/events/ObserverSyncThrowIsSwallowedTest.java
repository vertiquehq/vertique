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
 * Behavior test proving a single synchronously-throwing observer is isolated from the others and
 * does not fail {@link Event#fire(Object)}.
 *
 * <p>RED in slice 3.1: {@link Event#fire(Object)} is a failing stub. The green step turns this test
 * green by catching the sync throw per observer, logging it, and continuing the fan-out.
 */
@ExtendWith(VertxExtension.class)
class ObserverSyncThrowIsSwallowedTest {

    /** A hand-written concrete publisher standing in for the generated {@code String$Event}. */
    private static final class StringEvent extends Event<String> {
        StringEvent(ObserverRegistry registry) {
            super(registry, String.class);
        }
    }

    /**
     * Given one observer that throws synchronously and one well-behaved observer, when {@code fire}
     * is awaited, then the future completes successfully, the throw is swallowed, and the good
     * observer still runs (one bad observer is isolated from the rest).
     */
    @Test
    void syncThrowingObserverDoesNotFailFire() {
        List<String> ran = new ArrayList<>();
        ObserverRegistration bad = new ObserverRegistration(String.class, 100, event -> {
            throw new IllegalStateException("sync boom");
        });
        ObserverRegistration good = new ObserverRegistration(String.class, 200, event -> ran.add("good"));
        ObserverRegistry registry = new ObserverRegistry(Set.of(bad, good));
        StringEvent event = new StringEvent(registry);

        Future<Void> result = event.fire("hello");

        assertTrue(result.succeeded(), "a sync-throwing observer must not fail fire()");
        assertEquals(List.of("good"), ran, "the good observer must still run after the bad one throws");
    }
}
