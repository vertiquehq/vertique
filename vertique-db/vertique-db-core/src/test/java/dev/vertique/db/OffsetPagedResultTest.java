// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.OffsetPagedResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link OffsetPagedResult} derived navigation methods ({@code totalPages}, {@code first},
 * {@code last}), convenience methods ({@code size}, {@code isEmpty}), validation, and defensive
 * copying behaviour.
 */
class OffsetPagedResultTest {

    // --- totalPages ---

    @Nested
    @DisplayName("totalPages")
    class TotalPages {

        @Test
        @DisplayName("ceiling division: 25 items / pageSize 10 = 3 pages")
        void totalPages_ceilingDivision() {
            var result = new OffsetPagedResult<>(List.of("a"), 25L, 0, 10);
            assertEquals(3, result.totalPages());
        }

        @Test
        @DisplayName("exact division: 20 items / pageSize 10 = 2 pages")
        void totalPages_exactDivision() {
            var result = new OffsetPagedResult<>(List.of("a"), 20L, 0, 10);
            assertEquals(2, result.totalPages());
        }

        @Test
        @DisplayName("zero items returns 0 pages")
        void totalPages_zeroItems() {
            var result = new OffsetPagedResult<>(List.of(), 0L, 0, 10);
            assertEquals(0, result.totalPages());
        }

        @Test
        @DisplayName("fewer items than pageSize returns 1 page")
        void totalPages_fewerItemsThanPageSize() {
            var result = new OffsetPagedResult<>(List.of("a", "b"), 3L, 0, 10);
            assertEquals(1, result.totalPages());
        }

        @Test
        @DisplayName("single item with pageSize 1 returns 1 page")
        void totalPages_singleItemPageSizeOne() {
            var result = new OffsetPagedResult<>(List.of("a"), 1L, 0, 1);
            assertEquals(1, result.totalPages());
        }

        @Test
        @DisplayName("large total: 101 items / pageSize 10 = 11 pages")
        void totalPages_largeTotalCeiling() {
            var result = new OffsetPagedResult<>(List.of("a"), 101L, 0, 10);
            assertEquals(11, result.totalPages());
        }
    }

    // --- first and last flags ---

    @Nested
    @DisplayName("first and last flags")
    class FirstLastFlags {

        @Test
        @DisplayName("first() is true when page is 0")
        void first_trueWhenPageIsZero() {
            var result = new OffsetPagedResult<>(List.of("a"), 30L, 0, 10);
            assertTrue(result.first());
        }

        @Test
        @DisplayName("first() is false when page is 1")
        void first_falseWhenPageIsOne() {
            var result = new OffsetPagedResult<>(List.of("a"), 30L, 1, 10);
            assertFalse(result.first());
        }

        @Test
        @DisplayName("first() is false when page is 2")
        void first_falseWhenPageIsTwo() {
            var result = new OffsetPagedResult<>(List.of("a"), 30L, 2, 10);
            assertFalse(result.first());
        }

        @Test
        @DisplayName("last() is true when page equals totalPages - 1")
        void last_trueOnLastPage() {
            // 30 items / 10 per page = 3 pages (0, 1, 2). Page 2 is the last.
            var result = new OffsetPagedResult<>(List.of("a"), 30L, 2, 10);
            assertTrue(result.last());
        }

        @Test
        @DisplayName("last() is false when page is less than totalPages - 1")
        void last_falseWhenNotLastPage() {
            // 30 items / 10 per page = 3 pages. Page 0 is not the last.
            var result = new OffsetPagedResult<>(List.of("a"), 30L, 0, 10);
            assertFalse(result.last());
        }

        @Test
        @DisplayName("both first() and last() are true for a single page result")
        void bothFirstAndLast_singlePage() {
            // 1 item, pageSize 10 → totalPages = 1, page 0 is both first and last
            var result = new OffsetPagedResult<>(List.of("a"), 1L, 0, 10);
            assertTrue(result.first());
            assertTrue(result.last());
        }

        @Test
        @DisplayName("both first() and last() are true for an empty result (totalItems=0)")
        void bothFirstAndLast_emptyResult() {
            // 0 items → totalPages = 0, page 0 is considered both first and last
            var result = new OffsetPagedResult<>(List.of(), 0L, 0, 10);
            assertTrue(result.first());
            assertTrue(result.last());
        }

        @Test
        @DisplayName("last() is true when page is past totalPages (guard against over-fetch)")
        void last_trueWhenPageBeyondTotal() {
            // Edge case: page 5 with only 3 pages worth of data
            var result = new OffsetPagedResult<>(List.of(), 25L, 5, 10);
            assertTrue(result.last());
        }
    }

    // --- convenience methods ---

    @Nested
    @DisplayName("convenience methods")
    class ConvenienceMethods {

        @Test
        @DisplayName("size() returns the number of items in the list")
        void size_returnsItemCount() {
            var result = new OffsetPagedResult<>(List.of("a", "b", "c"), 30L, 0, 10);
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("isEmpty() is true when the items list is empty")
        void isEmpty_trueWhenEmpty() {
            var result = new OffsetPagedResult<>(List.of(), 0L, 0, 10);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("isEmpty() is false when the items list is non-empty")
        void isEmpty_falseWhenNonEmpty() {
            var result = new OffsetPagedResult<>(List.of("x"), 5L, 0, 10);
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("empty() factory returns correct structure")
        void empty_returnsCorrectStructure() {
            OffsetPagedResult<String> result = OffsetPagedResult.empty(2, 15);
            assertTrue(result.items().isEmpty());
            assertEquals(0L, result.totalItems());
            assertEquals(2, result.page());
            assertEquals(15, result.pageSize());
        }

        @Test
        @DisplayName("empty() result has size 0 and isEmpty true")
        void empty_sizeAndIsEmpty() {
            OffsetPagedResult<String> result = OffsetPagedResult.empty(0, 10);
            assertEquals(0, result.size());
            assertTrue(result.isEmpty());
        }
    }

    // --- defensive copying ---

    @Nested
    @DisplayName("defensive copying")
    class DefensiveCopy {

        @Test
        @DisplayName("modifying the source list does not affect the result items")
        void defensiveCopy_modifyingSourceListDoesNotAffectResult() {
            List<String> source = new ArrayList<>(List.of("a", "b", "c"));
            var result = new OffsetPagedResult<>(source, 3L, 0, 10);
            source.add("d");
            assertEquals(3, result.items().size(), "result items must not reflect mutation of source list");
        }

        @Test
        @DisplayName("the items list returned from the result is unmodifiable")
        void defensiveCopy_itemsListIsUnmodifiable() {
            var result = new OffsetPagedResult<>(List.of("a", "b"), 2L, 0, 10);
            assertThrows(
                    UnsupportedOperationException.class, () -> result.items().add("c"));
        }

        @Test
        @DisplayName("null items list is treated as empty")
        void defensiveCopy_nullItemsListTreatedAsEmpty() {
            var result = new OffsetPagedResult<String>(null, 0L, 0, 10);
            assertNotNull(result.items());
            assertTrue(result.items().isEmpty());
        }
    }

    // --- validation ---

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("negative page throws IllegalArgumentException")
        void validation_negativePageThrows() {
            assertThrows(IllegalArgumentException.class, () -> new OffsetPagedResult<>(List.of(), 0L, -1, 10));
        }

        @Test
        @DisplayName("pageSize of zero throws IllegalArgumentException")
        void validation_zeroPageSizeThrows() {
            assertThrows(IllegalArgumentException.class, () -> new OffsetPagedResult<>(List.of(), 0L, 0, 0));
        }

        @Test
        @DisplayName("negative pageSize throws IllegalArgumentException")
        void validation_negativePageSizeThrows() {
            assertThrows(IllegalArgumentException.class, () -> new OffsetPagedResult<>(List.of(), 0L, 0, -5));
        }

        @Test
        @DisplayName("negative totalItems throws IllegalArgumentException")
        void validation_negativeTotalItemsThrows() {
            assertThrows(IllegalArgumentException.class, () -> new OffsetPagedResult<>(List.of(), -1L, 0, 10));
        }

        @Test
        @DisplayName("page 0 and pageSize 1 are the minimum valid values")
        void validation_minimumValidValues() {
            assertDoesNotThrow(() -> new OffsetPagedResult<>(List.of(), 0L, 0, 1));
        }
    }

    @Nested
    @DisplayName("totalPages overflow protection")
    class TotalPagesOverflow {

        @Test
        @DisplayName("totalPages clamps to Integer.MAX_VALUE for huge totalItems with pageSize 1")
        void totalPages_clampsToMaxIntForHugeTotalItems_pageSize1() {
            var result = new OffsetPagedResult<>(List.of(), Long.MAX_VALUE, 0, 1);
            assertEquals(Integer.MAX_VALUE, result.totalPages());
        }

        @Test
        @DisplayName("totalPages clamps to Integer.MAX_VALUE for huge totalItems with pageSize > 1")
        void totalPages_clampsToMaxIntForHugeTotalItems_pageSizeGreaterThan1() {
            // This exercises the overflow-safe ceiling division: totalItems / pageSize
            // would overflow if using (totalItems + pageSize - 1) / pageSize
            var result = new OffsetPagedResult<>(List.of(), Long.MAX_VALUE, 0, 2);
            assertEquals(Integer.MAX_VALUE, result.totalPages());
        }

        @Test
        @DisplayName("totalPages uses overflow-safe ceiling division for large counts")
        void totalPages_overflowSafeCeilingDivision() {
            var result = new OffsetPagedResult<>(List.of(), 100_000_000_001L, 0, 10);
            // Clamped to int range
            assertEquals(Integer.MAX_VALUE, result.totalPages());
        }
    }
}
