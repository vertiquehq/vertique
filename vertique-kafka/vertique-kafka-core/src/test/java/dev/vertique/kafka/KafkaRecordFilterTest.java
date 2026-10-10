// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.buffer.Buffer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all {@link KafkaRecordFilter} static factory methods: {@code headerEquals},
 * {@code headerExists}, {@code headerIn}, {@code headerMatches}, {@code allOf}, and {@code anyOf}.
 *
 * <p>The factories compare text: the last value of the key that is not {@code null}, decoded as
 * UTF-8 with malformed input replaced. {@link TextSemantics} pins that rule for repeated keys, for
 * {@code null} values on either side of a value and for a value that is not valid UTF-8.
 */
class KafkaRecordFilterTest {

    private static final String KEY = "some-key";

    // --- headerEquals ---

    @Nested
    @DisplayName("headerEquals()")
    class HeaderEquals {

        @Test
        @DisplayName("accepts record when header value exactly matches expected value")
        void acceptsWhenHeaderMatches() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerEquals("event-type", "order.created");
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("event-type", "order.created"))));
        }

        @Test
        @DisplayName("rejects record when header value does not match")
        void rejectsWhenHeaderValueDiffers() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerEquals("event-type", "order.created");
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("event-type", "order.updated"))));
        }

        @Test
        @DisplayName("rejects record when header is missing")
        void rejectsWhenHeaderMissing() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerEquals("event-type", "order.created");
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("other-header", "value"))));
        }
    }

    // --- headerExists ---

    @Nested
    @DisplayName("headerExists()")
    class HeaderExists {

        @Test
        @DisplayName("accepts record when header is present")
        void acceptsWhenHeaderPresent() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerExists("trace-id");
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("trace-id", "abc123"))));
        }

        @Test
        @DisplayName("rejects record when header is absent")
        void rejectsWhenHeaderAbsent() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerExists("trace-id");
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("other-header", "value"))));
        }

        @Test
        @DisplayName("accepts record when header is present with empty value")
        void acceptsWhenHeaderPresentWithEmptyValue() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerExists("trace-id");
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("trace-id", ""))));
        }
    }

    // --- headerIn ---

    @Nested
    @DisplayName("headerIn()")
    class HeaderIn {

        @Test
        @DisplayName("accepts record when header value is in the allowed set")
        void acceptsWhenValueInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("status", "CREATED"))));
        }

        @Test
        @DisplayName("accepts record when header matches second value in set")
        void acceptsWhenValueIsSecondInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("status", "UPDATED"))));
        }

        @Test
        @DisplayName("rejects record when header value is not in the allowed set")
        void rejectsWhenValueNotInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("status", "DELETED"))));
        }

        @Test
        @DisplayName("rejects record when header is present but value is not in set")
        void rejectsWhenValueNotPresentInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            // Use a header that is present but with a value not in the set (avoids null lookup)
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("status", "DELETED"))));
        }
    }

    // --- headerMatches ---

    @Nested
    @DisplayName("headerMatches()")
    class HeaderMatches {

        @Test
        @DisplayName("accepts record when header value matches the regex pattern")
        void acceptsWhenPatternMatches() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerMatches("version", Pattern.compile("v\\d+\\.\\d+"));
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("version", "v1.0"))));
        }

        @Test
        @DisplayName("rejects record when header value does not match the regex pattern")
        void rejectsWhenPatternDoesNotMatch() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerMatches("version", Pattern.compile("v\\d+\\.\\d+"));
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("version", "1.0"))));
        }

        @Test
        @DisplayName("rejects record when header is missing")
        void rejectsWhenHeaderMissing() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerMatches("version", Pattern.compile("v\\d+\\.\\d+"));
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("other-header", "v1.0"))));
        }
    }

    // --- allOf ---

    @Nested
    @DisplayName("allOf()")
    class AllOf {

        @Test
        @DisplayName("accepts record when all sub-filters accept")
        void acceptsWhenAllFiltersPass() {
            KafkaRecordFilter filter = KafkaRecordFilter.allOf(
                    KafkaRecordFilter.headerEquals("type", "order"), KafkaRecordFilter.headerExists("trace-id"));
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("type", "order", "trace-id", "abc"))));
        }

        @Test
        @DisplayName("rejects record when any sub-filter rejects")
        void rejectsWhenOneFilterFails() {
            KafkaRecordFilter filter = KafkaRecordFilter.allOf(
                    KafkaRecordFilter.headerEquals("type", "order"), KafkaRecordFilter.headerExists("trace-id"));
            // type matches but trace-id missing
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("type", "order"))));
        }

        @Test
        @DisplayName("accepts record when no filters are provided (vacuously true)")
        void acceptsWhenNoFiltersProvided() {
            KafkaRecordFilter filter = KafkaRecordFilter.allOf();
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of())));
        }
    }

    // --- anyOf ---

    @Nested
    @DisplayName("anyOf()")
    class AnyOf {

        @Test
        @DisplayName("accepts record when at least one sub-filter accepts")
        void acceptsWhenOneFilterPasses() {
            KafkaRecordFilter filter = KafkaRecordFilter.anyOf(
                    KafkaRecordFilter.headerEquals("type", "order"), KafkaRecordFilter.headerEquals("type", "payment"));
            assertTrue(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("type", "payment"))));
        }

        @Test
        @DisplayName("rejects record when all sub-filters reject")
        void rejectsWhenAllFiltersFail() {
            KafkaRecordFilter filter = KafkaRecordFilter.anyOf(
                    KafkaRecordFilter.headerEquals("type", "order"), KafkaRecordFilter.headerEquals("type", "payment"));
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of("type", "shipment"))));
        }

        @Test
        @DisplayName("rejects record when no filters are provided (vacuously false)")
        void rejectsWhenNoFiltersProvided() {
            KafkaRecordFilter filter = KafkaRecordFilter.anyOf();
            assertFalse(filter.accept(KEY, KafkaRecordHeaders.of(Map.of())));
        }
    }

    // --- Text semantics on faithful headers ---

    @Nested
    @DisplayName("the factories read the last non-null value as lenient UTF-8")
    class TextSemantics {

        /** What two bytes that are never valid UTF-8 decode to: two replacement characters. */
        private static final String REPLACED = "\uFFFD\uFFFD";

        private static KafkaRecordHeader text(String key, String value) {
            return KafkaRecordHeader.ofUtf8(key, value);
        }

        private static KafkaRecordHeader absent(String key) {
            return new KafkaRecordHeader(key, null);
        }

        /** The key {@code h} twice with another key in between: the last value is {@code second}. */
        private final KafkaRecordHeaders duplicate =
                new KafkaRecordHeaders(List.of(text("h", "first"), text("other", "x"), text("h", "second")));

        private final KafkaRecordHeaders nullThenValue =
                new KafkaRecordHeaders(List.of(absent("h"), text("h", "value")));

        private final KafkaRecordHeaders valueThenNull =
                new KafkaRecordHeaders(List.of(text("h", "value"), absent("h")));

        private final KafkaRecordHeaders onlyNull = new KafkaRecordHeaders(List.of(absent("h")));

        private final KafkaRecordHeaders malformed = new KafkaRecordHeaders(
                List.of(new KafkaRecordHeader("h", Buffer.buffer(new byte[] {(byte) 0xFF, (byte) 0xFE}))));

        @Test
        @DisplayName("headerEquals compares the last non-null value")
        void headerEqualsReadsLastNonNullValue() {
            assertTrue(KafkaRecordFilter.headerEquals("h", "second").accept(KEY, duplicate));
            assertFalse(KafkaRecordFilter.headerEquals("h", "first").accept(KEY, duplicate));
            assertTrue(KafkaRecordFilter.headerEquals("h", "value").accept(KEY, nullThenValue));
            assertTrue(KafkaRecordFilter.headerEquals("h", "value").accept(KEY, valueThenNull));
            assertFalse(KafkaRecordFilter.headerEquals("h", "value").accept(KEY, onlyNull));
            assertFalse(KafkaRecordFilter.headerEquals("h", "").accept(KEY, onlyNull));
            assertTrue(KafkaRecordFilter.headerEquals("h", REPLACED).accept(KEY, malformed));
            assertFalse(KafkaRecordFilter.headerEquals("h", "value").accept(KEY, malformed));
        }

        @Test
        @DisplayName("headerExists is false when the key is absent or only has null values")
        void headerExistsNeedsANonNullValue() {
            assertTrue(KafkaRecordFilter.headerExists("h").accept(KEY, duplicate));
            assertTrue(KafkaRecordFilter.headerExists("h").accept(KEY, nullThenValue));
            assertTrue(KafkaRecordFilter.headerExists("h").accept(KEY, valueThenNull));
            assertFalse(KafkaRecordFilter.headerExists("h").accept(KEY, onlyNull));
            assertTrue(KafkaRecordFilter.headerExists("h").accept(KEY, malformed));
            assertFalse(KafkaRecordFilter.headerExists("missing").accept(KEY, duplicate));
            assertFalse(KafkaRecordFilter.headerExists("h").accept(KEY, KafkaRecordHeaders.empty()));
        }

        @Test
        @DisplayName("headerMatches matches the last non-null value")
        void headerMatchesReadsLastNonNullValue() {
            Pattern secondOnly = Pattern.compile("sec.*");
            Pattern value = Pattern.compile("val.e");
            assertTrue(KafkaRecordFilter.headerMatches("h", secondOnly).accept(KEY, duplicate));
            assertFalse(KafkaRecordFilter.headerMatches("h", Pattern.compile("first"))
                    .accept(KEY, duplicate));
            assertTrue(KafkaRecordFilter.headerMatches("h", value).accept(KEY, nullThenValue));
            assertTrue(KafkaRecordFilter.headerMatches("h", value).accept(KEY, valueThenNull));
            assertFalse(
                    KafkaRecordFilter.headerMatches("h", Pattern.compile(".*")).accept(KEY, onlyNull));
            assertTrue(KafkaRecordFilter.headerMatches("h", Pattern.compile("\uFFFD{2}"))
                    .accept(KEY, malformed));
        }

        @Test
        @DisplayName("headerIn looks up the last non-null value")
        void headerInReadsLastNonNullValue() {
            assertTrue(KafkaRecordFilter.headerIn("h", "second", "zzz").accept(KEY, duplicate));
            assertFalse(KafkaRecordFilter.headerIn("h", "first", "zzz").accept(KEY, duplicate));
            assertTrue(KafkaRecordFilter.headerIn("h", "value").accept(KEY, nullThenValue));
            assertTrue(KafkaRecordFilter.headerIn("h", "value").accept(KEY, valueThenNull));
            // A header without a text value is not one of the values, the empty string included.
            assertFalse(KafkaRecordFilter.headerIn("h", "value", "").accept(KEY, onlyNull));
            assertFalse(KafkaRecordFilter.headerIn("missing", "value").accept(KEY, duplicate));
            assertFalse(KafkaRecordFilter.headerIn("h", "value").accept(KEY, KafkaRecordHeaders.empty()));
            assertTrue(KafkaRecordFilter.headerIn("h", REPLACED).accept(KEY, malformed));
        }

        @Test
        @DisplayName("allOf and anyOf hand the same headers to every filter")
        void combinatorsPassTheSameHeaders() {
            java.util.List<KafkaRecordHeaders> seen = new java.util.ArrayList<>();
            KafkaRecordFilter recording = (key, headers) -> {
                seen.add(headers);
                return true;
            };

            assertTrue(KafkaRecordFilter.allOf(recording, KafkaRecordFilter.headerEquals("h", "second"))
                    .accept(KEY, duplicate));
            assertTrue(KafkaRecordFilter.anyOf(KafkaRecordFilter.headerEquals("h", "first"), recording)
                    .accept(KEY, duplicate));
            assertFalse(KafkaRecordFilter.allOf(
                            KafkaRecordFilter.headerExists("h"), KafkaRecordFilter.headerEquals("h", "value"))
                    .accept(KEY, onlyNull));
            assertTrue(KafkaRecordFilter.anyOf(
                            KafkaRecordFilter.headerExists("missing"), KafkaRecordFilter.headerIn("h", "value"))
                    .accept(KEY, valueThenNull));

            assertEquals(2, seen.size());
            assertSame(duplicate, seen.get(0));
            assertSame(duplicate, seen.get(1));
        }
    }
}
