// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@link Event#fire(Object)} rejects a {@code null} event argument synchronously with
 * an explicit, programmer-friendly {@link NullPointerException} whose message names the parameter.
 *
 * <p>The "never fails" guarantee in {@link Event}'s javadoc applies to <em>observer failures</em>,
 * not to a {@code null} event argument. Passing {@code null} is a programming error and must be
 * rejected eagerly — before any observer is called — with a {@link NullPointerException} whose
 * message comes from an explicit {@code Objects.requireNonNull} guard, so the call site has an
 * unambiguous diagnosis.
 *
 * <p>RED: without an explicit {@code requireNonNull} guard, {@link Event#fire(Object)} currently
 * throws an NPE from the JVM (via {@code event.getClass()}), but its message is a JVM-generated
 * string such as {@code "Cannot invoke ... because 'event' is null"} rather than the explicit
 * contract message {@code "event must not be null"}. GREEN: once {@link Event#fire(Object)} calls
 * {@code Objects.requireNonNull(event, "event must not be null")} at the top of the method, the
 * message assertion below passes.
 */
@ExtendWith(VertxExtension.class)
class FireRejectsNullEventTest {

    /** A hand-written concrete publisher standing in for the generated {@code String$Event}. */
    private static final class StringEvent extends Event<String> {
        StringEvent(ObserverRegistry registry) {
            super(registry, String.class);
        }
    }

    private final StringEvent event = new StringEvent(new ObserverRegistry(Collections.emptySet()));

    /**
     * Given a {@code null} event argument, when {@code fire(null)} is called, then a
     * {@link NullPointerException} is thrown synchronously (not wrapped in a failed {@link Future})
     * with the explicit message {@code "event must not be null"}.
     *
     * <p>RED: without {@code requireNonNull}, the NPE message is a JVM-synthesized description
     * (e.g. {@code "Cannot invoke ... because 'event' is null"}), not {@code "event must not be null"}.
     */
    @Test
    @DisplayName(
            "fireNull: fire(null) throws NullPointerException synchronously with the explicit message 'event must not be null'")
    void fireNull() {
        NullPointerException npe = assertThrows(
                NullPointerException.class,
                () -> event.fire(null),
                "fire(null) must throw NullPointerException synchronously");

        assertNotNull(npe.getMessage(), "the NPE message must not be null");
        assertTrue(
                npe.getMessage().contains("event must not be null"),
                "the NPE message must contain 'event must not be null' (the explicit requireNonNull message)"
                        + " but was: "
                        + npe.getMessage());
    }

    /**
     * Sanity check: the "never fails" guarantee still holds for a non-null event even when an
     * observer throws.
     */
    @Test
    @DisplayName("fireNonNull: fire with a non-null event that has a throwing observer still succeeds")
    void fireNonNull() {
        ObserverRegistration throwingObserver = new ObserverRegistration(String.class, 100, e -> {
            throw new RuntimeException("observer boom");
        });
        StringEvent eventWithObserver = new StringEvent(new ObserverRegistry(Set.of(throwingObserver)));

        Future<Void> result = eventWithObserver.fire("hello");

        assertTrue(
                result.succeeded(),
                "fire() with a non-null event must complete successfully despite a throwing observer");
    }
}
