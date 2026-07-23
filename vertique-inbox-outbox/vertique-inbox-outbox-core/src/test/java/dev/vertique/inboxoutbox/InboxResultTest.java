// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InboxResult} sealed hierarchy — {@link InboxResult.Processed} and
 * {@link InboxResult.Duplicate} variants, convenience methods, and pattern matching.
 */
@DisplayName("InboxResult")
class InboxResultTest {

    @Nested
    @DisplayName("Processed(value)")
    class ProcessedVariant {

        @Test
        @DisplayName("isProcessed() returns true")
        void isProcessedReturnsTrue() {
            InboxResult<String> result = new InboxResult.Processed<>("hello");
            assertTrue(result.isProcessed());
        }

        @Test
        @DisplayName("isDuplicate() returns false")
        void isDuplicateReturnsFalse() {
            InboxResult<String> result = new InboxResult.Processed<>("hello");
            assertFalse(result.isDuplicate());
        }

        @Test
        @DisplayName("value() returns the processed value")
        void valueReturnsProcessedValue() {
            InboxResult<String> result = new InboxResult.Processed<>("hello");
            assertEquals("hello", result.value());
        }

        @Test
        @DisplayName("value() returns null when value is null")
        void valueCanBeNull() {
            InboxResult<String> result = new InboxResult.Processed<>(null);
            assertEquals(null, result.value());
        }

        @Test
        @DisplayName("works with non-String type parameter")
        void worksWithIntegerType() {
            InboxResult<Integer> result = new InboxResult.Processed<>(42);
            assertEquals(42, result.value());
        }
    }

    @Nested
    @DisplayName("Duplicate()")
    class DuplicateVariant {

        @Test
        @DisplayName("isProcessed() returns false")
        void isProcessedReturnsFalse() {
            InboxResult<String> result = new InboxResult.Duplicate<>();
            assertFalse(result.isProcessed());
        }

        @Test
        @DisplayName("isDuplicate() returns true")
        void isDuplicateReturnsTrue() {
            InboxResult<String> result = new InboxResult.Duplicate<>();
            assertTrue(result.isDuplicate());
        }

        @Test
        @DisplayName("value() throws IllegalStateException")
        void valueThrowsIllegalStateException() {
            InboxResult<String> result = new InboxResult.Duplicate<>();
            assertThrows(IllegalStateException.class, result::value);
        }

        @Test
        @DisplayName("value() exception message mentions duplicate")
        void valueExceptionMessageMentionsDuplicate() {
            InboxResult<String> result = new InboxResult.Duplicate<>();
            IllegalStateException ex = assertThrows(IllegalStateException.class, result::value);
            assertTrue(ex.getMessage().toLowerCase().contains("duplicate"));
        }
    }

    @Nested
    @DisplayName("pattern matching with switch")
    class PatternMatching {

        @Test
        @DisplayName("switch routes Processed to first branch")
        void switchRoutesProcessedToFirstBranch() {
            InboxResult<String> result = new InboxResult.Processed<>("response");
            String outcome =
                    switch (result) {
                        case InboxResult.Processed<String> p -> "processed:" + p.value();
                        case InboxResult.Duplicate<String> ignored -> "duplicate";
                    };
            assertEquals("processed:response", outcome);
        }

        @Test
        @DisplayName("switch routes Duplicate to second branch")
        void switchRoutesDuplicateToBranch() {
            InboxResult<String> result = new InboxResult.Duplicate<>();
            String outcome =
                    switch (result) {
                        case InboxResult.Processed<String> ignored -> "processed";
                        case InboxResult.Duplicate<String> ignored -> "duplicate";
                    };
            assertEquals("duplicate", outcome);
        }
    }
}
