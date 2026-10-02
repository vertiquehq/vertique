// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Unit tests for the compact constructor of {@link HttpRequestCompletedEvent} (rest-025 T003, review finding
 * T003-R-03): the safety rules the event shares with {@link RestRequestCompletedEvent} (FR-008).
 *
 * <p>Verifies:
 * <ul>
 *   <li>each required component, {@code method}, {@code path}, {@code startTime}, {@code endTime} and
 *       {@code origin}, is rejected when {@code null} with a {@link NullPointerException} whose message is the
 *       component's name;</li>
 *   <li>{@code safeAttributes} is copied: a change to the caller's map after construction does not reach the
 *       event, and the map {@code safeAttributes()} returns rejects changes;</li>
 *   <li>a {@code null} {@code safeAttributes} becomes the empty map.</li>
 * </ul>
 *
 * <p>Every event is built by {@link #eventWith}, which takes the six components under test and fills the six
 * others with valid values: status 200, no failure, no wire failure, no security or correlation snapshot.
 */
class HttpRequestCompletedEventTest {

    /** Start time of every valid event built here. */
    private static final Instant START_TIME = Instant.parse("2026-01-01T00:00:00Z");

    /** End time of every valid event built here. */
    private static final Instant END_TIME = START_TIME.plusMillis(5);

    /** Method of every valid event built here. */
    private static final String METHOD = "GET";

    /** Path of every valid event built here. */
    private static final String PATH = "/health";

    /** Origin of every valid event built here: none available. */
    private static final Optional<RequestOrigin> NO_ORIGIN = Optional.empty();

    /** Each required component is rejected when {@code null}, by a {@link NullPointerException} naming it. */
    @Test
    @DisplayName("The event rejects a null method, path, startTime, endTime or origin, naming the component")
    void rejectsEachNullRequiredComponentByName() {
        assertAll(
                "required components",
                () -> assertRejectsNull(
                        "method", () -> eventWith(START_TIME, END_TIME, null, PATH, NO_ORIGIN, Map.of())),
                () -> assertRejectsNull(
                        "path", () -> eventWith(START_TIME, END_TIME, METHOD, null, NO_ORIGIN, Map.of())),
                () -> assertRejectsNull(
                        "startTime", () -> eventWith(null, END_TIME, METHOD, PATH, NO_ORIGIN, Map.of())),
                () -> assertRejectsNull(
                        "endTime", () -> eventWith(START_TIME, null, METHOD, PATH, NO_ORIGIN, Map.of())),
                () -> assertRejectsNull("origin", () -> eventWith(START_TIME, END_TIME, METHOD, PATH, null, Map.of())));
    }

    /** The event holds its own unmodifiable copy of the caller's attribute map. */
    @Test
    @DisplayName("safeAttributes is copied: a later change to the caller's map does not reach the event, and the "
            + "event's map rejects changes")
    void copiesSafeAttributesIntoAnUnmodifiableMap() {
        Map<String, Object> source = new HashMap<>();
        source.put("tenant", "acme");
        HttpRequestCompletedEvent event = eventWithAttributes(source);

        source.put("late", "added after construction");

        assertAll(
                "safeAttributes",
                () -> assertEquals(
                        Map.of("tenant", "acme"),
                        event.safeAttributes(),
                        "the event keeps the attributes it was built with, not the caller's later change"),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> event.safeAttributes().put("other", "value"),
                        "safeAttributes() must be unmodifiable"));
    }

    /** A {@code null} attribute map is accepted and read back as the empty map. */
    @Test
    @DisplayName("A null safeAttributes becomes the empty map")
    void defaultsNullSafeAttributesToEmptyMap() {
        HttpRequestCompletedEvent event =
                assertDoesNotThrow(() -> eventWithAttributes(null), "a null safeAttributes must be accepted");

        assertEquals(Map.of(), event.safeAttributes(), "safeAttributes() is the empty map, never null");
    }

    /**
     * Asserts that {@code construction} throws a {@link NullPointerException} whose message is {@code component}.
     *
     * @param component    the name of the component {@code construction} passes as {@code null}
     * @param construction builds an event with {@code component} set to {@code null}
     */
    private static void assertRejectsNull(String component, Executable construction) {
        NullPointerException thrown = assertThrows(
                NullPointerException.class, construction, "an event with a null " + component + " must be rejected");
        assertEquals(component, thrown.getMessage(), "the exception names the " + component + " component");
    }

    /**
     * Builds a valid event carrying {@code safeAttributes}: {@code GET /health}, no origin.
     *
     * @param safeAttributes the attribute map, possibly {@code null}
     * @return the event
     */
    private static HttpRequestCompletedEvent eventWithAttributes(Map<String, Object> safeAttributes) {
        return eventWith(START_TIME, END_TIME, METHOD, PATH, NO_ORIGIN, safeAttributes);
    }

    /**
     * Builds an event from the six components under test, each possibly {@code null}, with every other
     * component valid: status 200, no failure code or message, no wire failure, no security or correlation
     * snapshot.
     *
     * @param startTime      the start time
     * @param endTime        the end time
     * @param method         the request method
     * @param path           the request path
     * @param origin         the origin
     * @param safeAttributes the attribute map
     * @return the event
     */
    private static HttpRequestCompletedEvent eventWith(
            Instant startTime,
            Instant endTime,
            String method,
            String path,
            Optional<RequestOrigin> origin,
            Map<String, Object> safeAttributes) {
        return new HttpRequestCompletedEvent(
                startTime, endTime, method, path, 200, null, null, null, null, null, origin, safeAttributes);
    }
}
