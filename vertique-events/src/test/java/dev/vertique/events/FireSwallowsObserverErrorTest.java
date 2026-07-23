// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Behavior test proving that {@link Event#fire(Object)} isolates an observer that throws an
 * {@link Error} (or any {@link Throwable} that is not a {@link RuntimeException}).
 *
 * <p>RED: with the current {@code catch (RuntimeException)} swallow step in {@link Event#fire}, an
 * observer throwing an {@code Error} escapes the catch block. {@code Combinators.foldSequential}
 * propagates the uncaught {@code Error} as a failed future, and the remaining observers are
 * short-circuited — violating the documented "never fails" contract.
 *
 * <p>GREEN: once the swallow step is widened to {@code catch (Throwable)}, the {@code Error} is
 * caught, logged at warn, and swallowed, so the future completes successfully and the sibling
 * observer still runs.
 */
@ExtendWith(VertxExtension.class)
class FireSwallowsObserverErrorTest {

    /** A hand-written concrete publisher standing in for the generated {@code String$Event}. */
    private static final class StringEvent extends Event<String> {
        StringEvent(ObserverRegistry registry) {
            super(registry, String.class);
        }
    }

    /**
     * Given an observer that throws an {@link AssertionError} (a non-{@link RuntimeException}
     * {@link Error}) and a sibling observer registered at higher priority (lower fires first), when
     * {@code fire} is awaited, then:
     * <ul>
     *   <li>the returned future completes successfully (never fails), and
     *   <li>the sibling observer still runs (the throwing observer is isolated).
     * </ul>
     */
    @Test
    void fireCompletesSuccessfullyWhenObserverThrowsError() {
        List<String> ran = new ArrayList<>();
        // priority 100 fires first — throws an Error
        ObserverRegistration bad = new ObserverRegistration(String.class, 100, event -> {
            throw new AssertionError("observer threw an Error — not a RuntimeException");
        });
        // priority 200 fires second — must still run despite the preceding Error
        ObserverRegistration sibling = new ObserverRegistration(String.class, 200, event -> ran.add("sibling"));
        ObserverRegistry registry = new ObserverRegistry(Set.of(bad, sibling));
        StringEvent event = new StringEvent(registry);

        Future<Void> result = event.fire("hello");

        assertTrue(result.succeeded(), "fire() must complete successfully even when an observer throws an Error");
        assertFalse(result.failed(), "fire() must never surface an observer Error as a failed future");
        assertEquals(List.of("sibling"), ran, "sibling observer must still run after the Error-throwing observer");
    }
}
