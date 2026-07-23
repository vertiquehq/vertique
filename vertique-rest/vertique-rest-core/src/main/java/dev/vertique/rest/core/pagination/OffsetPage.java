// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Generic paginated response wrapper returned by collection endpoints using offset-based pagination.
 *
 * <p>Encapsulates a single page of results together with the metadata needed by clients
 * to navigate the full result set: total item count, total page count, current page
 * number, page size, and first/last flags.
 *
 * <p>Typical usage in a JAX-RS resource:
 *
 * <pre>{@code
 * @GET
 * @Path("/items")
 * @Operation(operationId = "listItems")
 * public Future<OffsetPage<Item>> listItems(OffsetPageRequest page) {
 *     int pg = page.page(0);
 *     int ps = page.pageSize(20, 100);
 *     return repository.findAll(pg, ps)
 *         .map(result -> OffsetPage.of(result.items(), result.totalCount(), pg, ps));
 * }
 * }</pre>
 *
 * <p>The {@code first} and {@code last} flags follow 0-based page numbering:
 * {@code first} is {@code true} when {@code page == 0}, and {@code last} is
 * {@code true} when {@code page >= totalPages - 1}.
 *
 * @param <T>        the element type of the page items
 * @param items      the items on this page (defensively copied; never {@code null})
 * @param totalItems the total number of items across all pages
 * @param totalPages the total number of pages computed from {@code totalItems} and {@code pageSize}
 * @param page       the 0-based index of the current page
 * @param pageSize   the number of items per page
 * @param first      whether this is the first page ({@code page == 0})
 * @param last       whether this is the last page ({@code page >= totalPages - 1})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OffsetPage<T>(
        List<T> items, long totalItems, int totalPages, int page, int pageSize, boolean first, boolean last) {

    /**
     * Compact constructor that defensively copies {@code items}.
     *
     * @param items      the items for this page
     * @param totalItems the total item count
     * @param totalPages the total page count
     * @param page       the 0-based page number
     * @param pageSize   the page size
     * @param first      whether this is the first page
     * @param last       whether this is the last page
     */
    public OffsetPage {
        items = items != null ? List.copyOf(items) : List.of();
    }

    /**
     * Creates an {@code OffsetPage} from the given items, total item count, page number,
     * and page size. Derives {@code totalPages}, {@code first}, and {@code last} automatically.
     *
     * <p>Typical usage:
     * <pre>{@code
     * int pg = request.page(0);
     * int ps = request.pageSize(20, 100);
     * return OffsetPage.of(items, totalCount, pg, ps);
     * }</pre>
     *
     * @param <T>        the element type
     * @param items      the items for the current page
     * @param totalItems the total number of items across all pages
     * @param page       the 0-based page number (should be {@code >= 0})
     * @param pageSize   the page size (should be {@code >= 1})
     * @return a fully populated {@code OffsetPage}
     */
    public static <T> OffsetPage<T> of(List<T> items, long totalItems, int page, int pageSize) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalItems / pageSize) : 0;
        return new OffsetPage<>(items, totalItems, totalPages, page, pageSize, page == 0, page >= totalPages - 1);
    }
}
