// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Result of a keyset-paginated query. Contains the items for the current page along with opaque
 * cursor tokens for navigating to the next and previous pages.
 *
 * <p>Cursor tokens are stateless Base64URL-encoded strings that encode the keyset column values,
 * page size, and navigation direction. Pass a cursor token to {@link
 * PageCursor#fromToken(String)} and then to {@link PagedQuery.Builder#page(PageCursor)} to fetch
 * the corresponding page.
 *
 * <pre>{@code
 * PagedResult<Item> result = repository.findItems(PageCursor.first(20)).await();
 *
 * // Check navigation
 * if (result.hasMore()) {
 *     PageCursor next = PageCursor.fromToken(result.nextCursorToken());
 *     PagedResult<Item> nextPage = repository.findItems(next).await();
 * }
 *
 * if (result.hasPrevious()) {
 *     PageCursor prev = PageCursor.fromToken(result.previousCursorToken());
 *     PagedResult<Item> prevPage = repository.findItems(prev).await();
 * }
 * }</pre>
 *
 * @param items               the items on the current page (never null, may be empty)
 * @param nextCursorToken     opaque cursor token for the next page, or {@code null} if this is the
 *                            last page
 * @param previousCursorToken opaque cursor token for the previous page, or {@code null} if this is
 *                            the first page
 * @param <T>                 the domain type
 * @see PagedQuery
 * @see PageCursor
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedResult<T>(List<T> items, String nextCursorToken, String previousCursorToken) {

    /**
     * Returns {@code true} if there are more items after this page.
     *
     * @return true if a next page exists
     */
    public boolean hasMore() {
        return nextCursorToken != null;
    }

    /**
     * Returns {@code true} if there are items before this page.
     *
     * @return true if a previous page exists
     */
    public boolean hasPrevious() {
        return previousCursorToken != null;
    }

    /**
     * Returns the number of items on this page.
     *
     * @return the item count
     */
    public int size() {
        return items.size();
    }

    /**
     * Returns an empty result with no cursor tokens.
     *
     * @param <T> the domain type
     * @return an empty paged result
     */
    public static <T> PagedResult<T> empty() {
        return new PagedResult<>(List.of(), null, null);
    }
}
