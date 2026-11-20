// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the shared validation helpers of the query types. */
@DisplayName("QueryChecks")
class QueryChecksTest {

    private static String messageOf(Runnable action) {
        return assertThrows(ValidationException.class, action::run).getMessage();
    }

    @Test
    @DisplayName("required returns the value and rejects null naming only the field")
    void required() {
        Object value = new Object();

        assertSame(value, QueryChecks.required(value, "field"));
        assertEquals("field must not be null", messageOf(() -> QueryChecks.required(null, "field")));
    }

    @Test
    @DisplayName("positive accepts one and rejects zero and negatives")
    void positive() {
        assertEquals(1L, QueryChecks.positive(1, "id"));
        assertEquals(Long.MAX_VALUE, QueryChecks.positive(Long.MAX_VALUE, "id"));
        assertEquals("id must be positive", messageOf(() -> QueryChecks.positive(0, "id")));
        assertEquals("id must be positive", messageOf(() -> QueryChecks.positive(-1, "id")));
    }

    @Test
    @DisplayName("nonNegative accepts zero and rejects negatives")
    void nonNegative() {
        assertEquals(0L, QueryChecks.nonNegative(0, "count"));
        assertEquals(5L, QueryChecks.nonNegative(5, "count"));
        assertEquals("count must not be negative", messageOf(() -> QueryChecks.nonNegative(-1, "count")));
    }

    @Test
    @DisplayName("range accepts 1 up to and including the maximum")
    void range() {
        assertEquals(1L, QueryChecks.range(1, 10, "size"));
        assertEquals(10L, QueryChecks.range(10, 10, "size"));
        assertEquals("size must be positive", messageOf(() -> QueryChecks.range(0, 10, "size")));
        assertEquals("size must be at most 10", messageOf(() -> QueryChecks.range(11, 10, "size")));
    }

    @Test
    @DisplayName("absent text stays absent")
    void optionalTextAbsent() {
        assertNull(QueryChecks.optionalText(null, "text", 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "order.created", "with space", "café", "😀"})
    @DisplayName("ordinary text, spaces and a valid surrogate pair are accepted unchanged")
    void optionalTextAccepted(String text) {
        assertSame(text, QueryChecks.optionalText(text, "text", 255));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", " "})
    @DisplayName("empty and whitespace-only text is blank")
    void optionalTextBlank(String text) {
        assertEquals("text must not be blank", messageOf(() -> QueryChecks.optionalText(text, "text", 255)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\tb", "a\nb", "a\rb", "a\u0000b", "a\u007fb", "a\u0085b", "\u0001"})
    @DisplayName("a control character anywhere is rejected")
    void optionalTextControl(String text) {
        assertEquals(
                "text must not contain control characters",
                messageOf(() -> QueryChecks.optionalText(text, "text", 255)));
    }

    @Test
    @DisplayName("a rejection never echoes the value")
    void rejectionDoesNotEchoValue() {
        assertFalse(messageOf(() -> QueryChecks.optionalText("secret-\u0001", "text", 255))
                .contains("secret"));
        assertFalse(messageOf(() -> QueryChecks.optionalText("secret-".repeat(50), "text", 5))
                .contains("secret"));
        assertFalse(messageOf(() -> QueryChecks.optionalText("secret\uD800", "text", 255))
                .contains("secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "a\uD800", "\uDC00", "\uDC00a", "\uD800\uD800", "\uDC00\uD800"})
    @DisplayName("an unpaired surrogate is rejected")
    void optionalTextUnpairedSurrogate(String text) {
        assertEquals(
                "text must not contain unpaired surrogates",
                messageOf(() -> QueryChecks.optionalText(text, "text", 255)));
    }

    @Test
    @DisplayName("the length is counted in code points, not chars")
    void optionalTextLengthInCodePoints() {
        String atLimit = "😀".repeat(5);
        String overLimit = "😀".repeat(6);

        assertSame(atLimit, QueryChecks.optionalText(atLimit, "text", 5));
        assertEquals(
                "text must be at most 5 characters", messageOf(() -> QueryChecks.optionalText(overLimit, "text", 5)));
        assertSame("abcde", QueryChecks.optionalText("abcde", "text", 5));
        assertEquals(
                "text must be at most 5 characters", messageOf(() -> QueryChecks.optionalText("abcdef", "text", 5)));
    }

    @Test
    @DisplayName("instants are accepted from year 1 to the end of year 9999 and absent ones stay absent")
    void instantRange() {
        Instant min = Instant.parse("0001-01-01T00:00:00Z");
        Instant max = Instant.parse("9999-12-31T23:59:59Z");

        assertNull(QueryChecks.instant(null, "at"));
        assertSame(min, QueryChecks.instant(min, "at"));
        assertSame(max, QueryChecks.instant(max, "at"));
        String expected = "at must be between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z";
        assertEquals(expected, messageOf(() -> QueryChecks.instant(min.minusSeconds(1), "at")));
        assertEquals(expected, messageOf(() -> QueryChecks.instant(max.plusSeconds(1), "at")));
        assertEquals(expected, messageOf(() -> QueryChecks.instant(Instant.MAX, "at")));
        assertEquals(expected, messageOf(() -> QueryChecks.instant(Instant.MIN, "at")));
    }

    @Test
    @DisplayName("a range start must be strictly before its end when both are present")
    void ordered() {
        Instant early = Instant.parse("2026-01-01T00:00:00Z");
        Instant late = early.plusSeconds(1);

        QueryChecks.ordered(early, late, "from", "before");
        QueryChecks.ordered(null, late, "from", "before");
        QueryChecks.ordered(early, null, "from", "before");
        QueryChecks.ordered(null, null, "from", "before");
        assertEquals(
                "from must be before before", messageOf(() -> QueryChecks.ordered(early, early, "from", "before")));
        assertEquals("from must be before before", messageOf(() -> QueryChecks.ordered(late, early, "from", "before")));
    }

    @Test
    @DisplayName("copyOf turns null into an empty map, copies, and rejects a null key or value")
    void copyOf() {
        Map<String, String> source = new HashMap<>(Map.of("a", "1"));

        Map<String, String> copy = QueryChecks.copyOf(source, "headers");
        source.put("b", "2");

        assertEquals(Map.of("a", "1"), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.put("c", "3"));
        assertTrue(QueryChecks.copyOf(null, "headers").isEmpty());
        assertTrue(QueryChecks.copyOf(Map.of(), "headers").isEmpty());
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("a", null);
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "v");
        assertEquals(
                "headers must not contain null keys or values",
                messageOf(() -> QueryChecks.copyOf(nullValue, "headers")));
        assertEquals(
                "headers must not contain null keys or values",
                messageOf(() -> QueryChecks.copyOf(nullKey, "headers")));
    }
}
