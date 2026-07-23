// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.rest.core.request.RequestParams;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RequestParams} record that captures offset-based pagination query parameters
 * from an HTTP request.
 *
 * <p>Typical usage in a JAX-RS resource:
 *
 * <pre>{@code
 * @GET
 * @Path("/items")
 * @Operation(operationId = "listItems")
 * public Future<OffsetPage<Item>> listItems(OffsetPageRequest page) {
 *     return repository.findAll(page.page(0), page.pageSize(20, 100))
 *         .map(result -> OffsetPage.of(result.items(), result.totalCount(), page, 20));
 * }
 * }</pre>
 *
 * <p>Example URL: {@code GET /items?page=2&size=10&sort=name,asc}
 *
 * <p>Because this type is annotated with {@link RequestParams}, the framework automatically
 * populates the record fields from query parameters — no {@code @BeanParam} is needed on
 * the method parameter.
 *
 * @param page     the 0-based page number from the {@code page} query parameter,
 *                 or {@code null} if not specified by the client
 * @param pageSize the requested page size from the {@code size} query parameter,
 *                 or {@code null} if not specified by the client
 * @param sort     the raw sort expression from the {@code sort} query parameter,
 *                 or {@code null} if not specified by the client
 */
@RequestParams
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OffsetPageRequest(
        @QueryParam("page") @Nullable Integer page,
        @QueryParam("size") @Nullable Integer pageSize,
        @QueryParam("sort") @Nullable String sort) {

    /**
     * Returns the page number, or the given default if not supplied. Clamped to {@code >= 0}.
     *
     * @param defaultValue the page number to return when the client did not specify one
     * @return the requested page number, or {@code defaultValue} if not supplied, clamped to {@code >= 0}
     */
    public int page(int defaultValue) {
        int raw = page != null ? page : defaultValue;
        return Math.max(0, raw);
    }

    /**
     * Returns the page size, or the given default if not supplied. Clamped to {@code >= 1}.
     *
     * @param defaultValue the page size to return when the client did not specify one
     * @return the requested page size, or {@code defaultValue} if not supplied, clamped to {@code >= 1}
     */
    public int pageSize(int defaultValue) {
        int raw = pageSize != null ? pageSize : defaultValue;
        return Math.max(1, raw);
    }

    /**
     * Returns the page size clamped between {@code 1} and {@code maxValue}, or the default
     * (also clamped) if not supplied.
     *
     * @param defaultValue the page size to return when the client did not specify one
     * @param maxValue     the maximum allowed page size
     * @return the page size, clamped to [{@code 1}, {@code maxValue}]
     */
    public int pageSize(int defaultValue, int maxValue) {
        int raw = pageSize != null ? pageSize : defaultValue;
        return Math.min(Math.max(1, raw), maxValue);
    }

    /**
     * Parses the {@code sort} query parameter into an ordered list of sort directives.
     *
     * <p>The sort expression has the form {@code "field,direction,field2,direction2,..."},
     * where direction is case-insensitive {@code "asc"} or {@code "desc"}. A field without
     * a following direction keyword defaults to {@link SortOrder.Direction#ASC}.
     *
     * <p><strong>Security:</strong> Field names are taken directly from the query parameter
     * without validation. Callers <strong>must</strong> validate field names against an allowlist
     * before using them in database queries to prevent injection attacks.
     *
     * @return list of parsed sort orders; empty when {@code sort} is null or blank
     */
    public List<SortOrder> sortOrders() {
        return parseSortParam(sort);
    }

    /**
     * A single sort directive combining a field name with a sort direction.
     *
     * @param field     the name of the field to sort by
     * @param direction the sort direction ({@link Direction#ASC} or {@link Direction#DESC})
     */
    public record SortOrder(String field, Direction direction) {

        /**
         * Sort direction for a {@link SortOrder}.
         */
        public enum Direction {
            /** Ascending order (smallest first). */
            ASC,
            /** Descending order (largest first). */
            DESC
        }
    }

    /**
     * Parses a sort expression of the form {@code "field,direction"} or just {@code "field"}.
     * Direction is case-insensitive; anything other than {@code "desc"} defaults to
     * {@link SortOrder.Direction#ASC}.
     *
     * @param sortStr the raw {@code sort} query parameter value, or {@code null}
     * @return list of parsed sort orders; empty when {@code sortStr} is null or blank
     */
    private static List<SortOrder> parseSortParam(String sortStr) {
        if (sortStr == null || sortStr.isBlank()) {
            return List.of();
        }
        List<SortOrder> orders = new ArrayList<>();
        for (String token : sortStr.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // First token is the field; second (optional) is the direction
            if (orders.isEmpty()) {
                orders.add(new SortOrder(trimmed, SortOrder.Direction.ASC));
            } else {
                // Check if this token is a direction keyword for the previous field
                String prev = orders.get(orders.size() - 1).field();
                SortOrder.Direction dir = parseDirection(trimmed);
                if (dir != null) {
                    orders.set(orders.size() - 1, new SortOrder(prev, dir));
                } else {
                    orders.add(new SortOrder(trimmed, SortOrder.Direction.ASC));
                }
            }
        }
        return orders;
    }

    /**
     * Parses a direction keyword ({@code "asc"} or {@code "desc"}, case-insensitive).
     *
     * @param token the token to parse
     * @return the {@link SortOrder.Direction}, or {@code null} if the token is not a direction keyword
     */
    private static SortOrder.Direction parseDirection(String token) {
        if ("asc".equalsIgnoreCase(token)) {
            return SortOrder.Direction.ASC;
        }
        if ("desc".equalsIgnoreCase(token)) {
            return SortOrder.Direction.DESC;
        }
        return null;
    }
}
