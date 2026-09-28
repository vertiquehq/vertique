// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code operation} component of {@link RestRequestCompletedEvent} (rest-025 T003).
 *
 * <p>Verifies:
 * <ul>
 *   <li>TP-008: the compact constructor rejects a {@code null} operation with a
 *       {@link NullPointerException} whose message is {@code operation}, and {@code operation()} returns
 *       the descriptor instance the event was built with.</li>
 *   <li>TP-009: {@code toString()} renders the operation as {@code <operationId> <routeTemplate>}, beside
 *       the other components' {@code name=value} pairs, without calling the descriptor's own
 *       {@code toString}. The descriptor is a {@link TestOperation.Hostile}, whose {@code toString},
 *       {@code equals} and {@code hashCode} count their calls and throw.</li>
 * </ul>
 *
 * <p>Every event is built by {@link #eventWith(RestOperationDescriptor)}, which fills the twelve other
 * components with valid values: {@code GET /users/7}, status 200, no failure, no snapshots, no origin
 * and no attributes.
 */
class RestRequestCompletedEventTest {

    /** Start time of every event built here. */
    private static final Instant START_TIME = Instant.parse("2026-01-01T00:00:00Z");

    /** End time of every event built here. */
    private static final Instant END_TIME = START_TIME.plusMillis(5);

    /** TP-008: a {@code null} operation is rejected; a non-null one is kept by identity. */
    @Test
    @DisplayName("The event rejects a null operation and keeps the descriptor instance it was built with")
    void rejectsNullOperation() {
        RestOperationDescriptor stub = new TestOperation("getUser", "GET", "/users/{id}");

        assertAll(
                "operation component",
                () -> {
                    NullPointerException thrown = assertThrows(
                            NullPointerException.class,
                            () -> eventWith(null),
                            "an event with a null operation must be rejected");
                    assertEquals("operation", thrown.getMessage(), "the exception names the operation component");
                },
                () -> assertSame(
                        stub,
                        eventWith(stub).operation(),
                        "operation() returns the instance the event was built with"));
    }

    /** TP-009: the rendered event names its operation without ever calling the descriptor's toString. */
    @Test
    @DisplayName("toString renders the operation as '<operationId> <routeTemplate>' without calling the descriptor")
    void toStringRendersOperationIdentityWithoutCallingDescriptor() {
        TestOperation.Hostile hostile = new TestOperation.Hostile("getUser", "GET", "/users/{id}");
        RestRequestCompletedEvent event = eventWith(hostile);

        String rendered = assertDoesNotThrow(
                () -> event.toString(), "the event's toString must not call the descriptor's toString");

        assertAll(
                "rendered event: " + rendered,
                () -> assertTrue(
                        rendered.contains("getUser /users/{id}"),
                        "renders the operation as '<operationId> <routeTemplate>'"),
                () -> assertTrue(rendered.contains("method=GET"), "renders the method component"),
                () -> assertTrue(rendered.contains("statusCode=200"), "renders the statusCode component"),
                () -> assertEquals(0, hostile.toStringCalls(), "the descriptor's toString is never called"));
    }

    /**
     * Builds an event carrying {@code operation}, with every other component valid: method {@code GET},
     * path {@code /users/7}, status 200, no failure, no security or correlation snapshot, no origin and
     * no attributes.
     *
     * @param operation the operation component, possibly {@code null}
     * @return the event
     */
    private static RestRequestCompletedEvent eventWith(RestOperationDescriptor operation) {
        return new RestRequestCompletedEvent(
                START_TIME,
                END_TIME,
                "GET",
                "/users/7",
                operation,
                200,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Map.of());
    }
}
