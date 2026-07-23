// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Generic cursor-paginated response wrapper for collection endpoints.
 *
 * <p>Encapsulates a single page of results together with opaque cursor tokens that clients
 * use to navigate to the next and previous pages. Cursor tokens are encoded through a
 * {@link CursorCodec}, making the underlying pagination mechanism transparent to clients.
 *
 * <p>Only {@code nextCursor} and {@code previousCursor} fields that are non-null are
 * serialized to JSON (via {@link JsonInclude#NON_NULL}). Clients determine navigation
 * availability from the presence of these fields.
 *
 * <p>Typical usage in a JAX-RS resource:
 *
 * <pre>{@code
 * @GET
 * @Path("/items")
 * @Operation(operationId = "listItems")
 * public Future<CursorPage<Item>> listItems(CursorPageRequest page) {
 *     int size = page.pageSize(20);
 *     PageCursor cursor = page.decodeCursor(cursorCodec)
 *         .map(raw -> PageCursor.fromToken(raw).withPageSize(size))
 *         .orElseGet(() -> PageCursor.first(size));
 *     return repository.findItems(cursor)
 *         .map(result -> CursorPage.of(
 *             result.items(), result.nextCursorToken(), result.previousCursorToken(), cursorCodec));
 * }
 * }</pre>
 *
 * @param <T>            the element type of the page items
 * @param items          the items on this page (defensively copied; never {@code null})
 * @param nextCursor     opaque cursor token for the next page, encoded via the {@link CursorCodec};
 *                       {@code null} when this is the last page
 * @param previousCursor opaque cursor token for the previous page, encoded via the {@link CursorCodec};
 *                       {@code null} when this is the first page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(
        List<T> items,
        @Nullable String nextCursor,
        @Nullable String previousCursor) {

    /**
     * Compact constructor that defensively copies {@code items}.
     *
     * @param items          the items for this page
     * @param nextCursor     the encoded next-page cursor, or {@code null}
     * @param previousCursor the encoded previous-page cursor, or {@code null}
     */
    public CursorPage {
        items = items != null ? List.copyOf(items) : List.of();
    }

    /**
     * Returns {@code true} if there are more items after this page (i.e. {@code nextCursor} is non-null).
     *
     * <p>Annotated {@code @JsonIgnore} — clients should determine navigation availability from
     * the presence of the {@code nextCursor} field in the JSON response.
     *
     * @return {@code true} when a next page exists
     */
    @JsonIgnore
    public boolean hasMore() {
        return nextCursor != null;
    }

    /**
     * Returns {@code true} if there are items before this page (i.e. {@code previousCursor} is non-null).
     *
     * <p>Annotated {@code @JsonIgnore} — clients should determine navigation availability from
     * the presence of the {@code previousCursor} field in the JSON response.
     *
     * @return {@code true} when a previous page exists
     */
    @JsonIgnore
    public boolean hasPrevious() {
        return previousCursor != null;
    }

    /**
     * Creates a {@code CursorPage} from a list of items and raw backend cursor tokens,
     * encoding the tokens through the given {@link CursorCodec}.
     *
     * <p><strong>Invariant:</strong> {@code nextRawCursor} and {@code prevRawCursor} must be
     * raw (unsigned) cursor tokens from the backend — not already codec-encoded strings.
     * {@code CursorPage.of()} is the only place where codec encoding is applied.
     *
     * @param <T>           the element type
     * @param items         the items for the current page (defensively copied)
     * @param nextRawCursor the raw backend cursor for the next page, or {@code null} if last page
     * @param prevRawCursor the raw backend cursor for the previous page, or {@code null} if first page
     * @param codec         the codec used to encode raw tokens before returning to the client
     * @return a fully populated {@code CursorPage}
     */
    public static <T> CursorPage<T> of(
            List<T> items, @Nullable String nextRawCursor, @Nullable String prevRawCursor, CursorCodec codec) {
        return new CursorPage<>(
                items,
                nextRawCursor != null ? codec.encode(nextRawCursor) : null,
                prevRawCursor != null ? codec.encode(prevRawCursor) : null);
    }

    /**
     * Convenience factory that uses {@link PlainCursorCodec} (no signing).
     *
     * <p><strong>Warning:</strong> Prefer {@link #of(List, String, String, CursorCodec)} when
     * a signing codec is configured in the application's Dagger module.
     *
     * @param <T>           the element type
     * @param items         the items for the current page
     * @param nextRawCursor the raw backend cursor for the next page, or {@code null} if last page
     * @param prevRawCursor the raw backend cursor for the previous page, or {@code null} if first page
     * @return a fully populated {@code CursorPage}
     */
    public static <T> CursorPage<T> of(List<T> items, @Nullable String nextRawCursor, @Nullable String prevRawCursor) {
        return of(items, nextRawCursor, prevRawCursor, PlainCursorCodec.INSTANCE);
    }
}
