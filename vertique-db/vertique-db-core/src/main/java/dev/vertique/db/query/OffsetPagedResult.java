// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Result of an offset-based paginated query. Contains the items for the current page along with
 * metadata about the total result set, enabling consumers to render pagination controls.
 *
 * <p>Unlike {@link PagedResult} (keyset/cursor-based), this result supports random-access navigation
 * because the total item count and page number are known. This makes it suitable for traditional
 * pagination UIs with page number controls.
 *
 * <pre>{@code
 * OffsetPagedResult<Item> result = repository.findItems(0, 20).await();
 *
 * // Navigate
 * if (!result.last()) {
 *     OffsetPagedResult<Item> nextPage = repository.findItems(result.page() + 1, 20).await();
 * }
 * }</pre>
 *
 * @param items      the items on the current page (never null, may be empty)
 * @param totalItems the total number of items across all pages
 * @param page       the zero-based page number of this result
 * @param pageSize   the maximum number of items per page (at least 1)
 * @param <T>        the domain type
 * @see OffsetPagedQuery
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OffsetPagedResult<T>(List<T> items, long totalItems, int page, int pageSize) {

    /**
     * Compact constructor. Makes a defensive copy of the items list and normalises a {@code null}
     * input to an empty list.
     *
     * @throws IllegalArgumentException if {@code page} is negative or {@code pageSize} is less than 1
     */
    public OffsetPagedResult {
        items = items != null ? List.copyOf(items) : List.of();
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must be >= 0, got: " + totalItems);
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0, got: " + page);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1, got: " + pageSize);
        }
    }

    // --- Derived navigation methods ---

    /**
     * Returns the total number of pages. Computed as {@code ceil(totalItems / pageSize)}, returning
     * 0 when {@code totalItems == 0}. Clamped to {@link Integer#MAX_VALUE} for extremely large
     * total item counts.
     *
     * @return the total number of pages
     */
    public int totalPages() {
        if (totalItems == 0) {
            return 0;
        }
        // Overflow-safe ceiling division: avoids (totalItems + pageSize - 1) which can wrap long
        long pages = totalItems / pageSize + (totalItems % pageSize == 0 ? 0 : 1);
        return (int) Math.min(pages, Integer.MAX_VALUE);
    }

    /**
     * Returns {@code true} if this is the first page (page index 0).
     *
     * @return true when page is 0
     */
    public boolean first() {
        return page == 0;
    }

    /**
     * Returns {@code true} if this is the last page (no further pages exist).
     *
     * @return true when page &gt;= totalPages - 1, or when totalItems is 0
     */
    public boolean last() {
        int tp = totalPages();
        return tp == 0 || page >= tp - 1;
    }

    // --- Convenience methods ---

    /**
     * Returns the number of items on this page.
     *
     * @return the item count
     */
    public int size() {
        return items.size();
    }

    /**
     * Returns {@code true} if this page contains no items.
     *
     * @return true if the items list is empty
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    // --- Factory methods ---

    /**
     * Creates an empty result for the given page and page size (no items, total 0).
     *
     * @param page     the zero-based page number
     * @param pageSize the page size
     * @param <T>      the domain type
     * @return an empty paged result
     */
    public static <T> OffsetPagedResult<T> empty(int page, int pageSize) {
        return new OffsetPagedResult<>(List.of(), 0, page, pageSize);
    }
}
