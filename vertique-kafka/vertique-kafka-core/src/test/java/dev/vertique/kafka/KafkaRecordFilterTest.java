// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all {@link KafkaRecordFilter} static factory methods: {@code headerEquals},
 * {@code headerExists}, {@code headerIn}, {@code headerMatches}, {@code allOf}, and {@code anyOf}.
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
            assertTrue(filter.accept(KEY, Map.of("event-type", "order.created")));
        }

        @Test
        @DisplayName("rejects record when header value does not match")
        void rejectsWhenHeaderValueDiffers() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerEquals("event-type", "order.created");
            assertFalse(filter.accept(KEY, Map.of("event-type", "order.updated")));
        }

        @Test
        @DisplayName("rejects record when header is missing")
        void rejectsWhenHeaderMissing() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerEquals("event-type", "order.created");
            assertFalse(filter.accept(KEY, Map.of("other-header", "value")));
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
            assertTrue(filter.accept(KEY, Map.of("trace-id", "abc123")));
        }

        @Test
        @DisplayName("rejects record when header is absent")
        void rejectsWhenHeaderAbsent() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerExists("trace-id");
            assertFalse(filter.accept(KEY, Map.of("other-header", "value")));
        }

        @Test
        @DisplayName("accepts record when header is present with empty value")
        void acceptsWhenHeaderPresentWithEmptyValue() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerExists("trace-id");
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("trace-id", "");
            assertTrue(filter.accept(KEY, headers));
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
            assertTrue(filter.accept(KEY, Map.of("status", "CREATED")));
        }

        @Test
        @DisplayName("accepts record when header matches second value in set")
        void acceptsWhenValueIsSecondInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            assertTrue(filter.accept(KEY, Map.of("status", "UPDATED")));
        }

        @Test
        @DisplayName("rejects record when header value is not in the allowed set")
        void rejectsWhenValueNotInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            assertFalse(filter.accept(KEY, Map.of("status", "DELETED")));
        }

        @Test
        @DisplayName("rejects record when header is present but value is not in set")
        void rejectsWhenValueNotPresentInSet() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerIn("status", "CREATED", "UPDATED");
            // Use a header that is present but with a value not in the set (avoids null lookup)
            assertFalse(filter.accept(KEY, Map.of("status", "DELETED")));
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
            assertTrue(filter.accept(KEY, Map.of("version", "v1.0")));
        }

        @Test
        @DisplayName("rejects record when header value does not match the regex pattern")
        void rejectsWhenPatternDoesNotMatch() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerMatches("version", Pattern.compile("v\\d+\\.\\d+"));
            assertFalse(filter.accept(KEY, Map.of("version", "1.0")));
        }

        @Test
        @DisplayName("rejects record when header is missing")
        void rejectsWhenHeaderMissing() {
            KafkaRecordFilter filter = KafkaRecordFilter.headerMatches("version", Pattern.compile("v\\d+\\.\\d+"));
            assertFalse(filter.accept(KEY, Map.of("other-header", "v1.0")));
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
            assertTrue(filter.accept(KEY, Map.of("type", "order", "trace-id", "abc")));
        }

        @Test
        @DisplayName("rejects record when any sub-filter rejects")
        void rejectsWhenOneFilterFails() {
            KafkaRecordFilter filter = KafkaRecordFilter.allOf(
                    KafkaRecordFilter.headerEquals("type", "order"), KafkaRecordFilter.headerExists("trace-id"));
            // type matches but trace-id missing
            assertFalse(filter.accept(KEY, Map.of("type", "order")));
        }

        @Test
        @DisplayName("accepts record when no filters are provided (vacuously true)")
        void acceptsWhenNoFiltersProvided() {
            KafkaRecordFilter filter = KafkaRecordFilter.allOf();
            assertTrue(filter.accept(KEY, Map.of()));
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
            assertTrue(filter.accept(KEY, Map.of("type", "payment")));
        }

        @Test
        @DisplayName("rejects record when all sub-filters reject")
        void rejectsWhenAllFiltersFail() {
            KafkaRecordFilter filter = KafkaRecordFilter.anyOf(
                    KafkaRecordFilter.headerEquals("type", "order"), KafkaRecordFilter.headerEquals("type", "payment"));
            assertFalse(filter.accept(KEY, Map.of("type", "shipment")));
        }

        @Test
        @DisplayName("rejects record when no filters are provided (vacuously false)")
        void rejectsWhenNoFiltersProvided() {
            KafkaRecordFilter filter = KafkaRecordFilter.anyOf();
            assertFalse(filter.accept(KEY, Map.of()));
        }
    }
}
