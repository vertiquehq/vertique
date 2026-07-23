// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.rest.core.request.RequestParams;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.QueryParam;
import java.util.Optional;

/**
 * {@link RequestParams} record that captures cursor-based pagination query parameters
 * from an HTTP request.
 *
 * <p>Typical usage with a Dagger-injected {@link CursorCodec}:
 *
 * <pre>{@code
 * @GET
 * @Path("/items")
 * public Future<CursorPage<Item>> listItems(CursorPageRequest pageRequest) {
 *     int size = pageRequest.pageSize(20);
 *     PageCursor cursor = pageRequest.decodeCursor(cursorCodec)
 *         .map(raw -> PageCursor.fromToken(raw).withPageSize(size, 1, 100))
 *         .orElseGet(() -> PageCursor.first(size));
 *     return repository.findItems(cursor)
 *         .map(result -> CursorPage.of(
 *             result.items(), result.nextCursorToken(), result.previousCursorToken(), cursorCodec));
 * }
 * }</pre>
 *
 * <p>This record has no dependency on {@code db-core}. Conversion to backend-specific
 * cursor types (e.g., {@code PageCursor}) is the caller's responsibility.
 *
 * <p>Because this type is annotated with {@link RequestParams}, the framework automatically
 * populates the record fields from query parameters — no {@code @BeanParam} is needed on
 * the method parameter.
 *
 * @param cursor   the raw encoded cursor token from the {@code cursor} query parameter,
 *                 or {@code null} for a first-page request
 * @param pageSize the requested page size from the {@code pageSize} query parameter,
 *                 or {@code null} if not specified by the client
 */
@RequestParams
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CursorPageRequest(
        @QueryParam("cursor") @Nullable String cursor,
        @QueryParam("pageSize") @Nullable Integer pageSize) {

    /**
     * Decodes the {@code cursor} query parameter using the provided codec.
     * Returns {@link Optional#empty()} when no cursor was supplied (first page).
     *
     * @param codec the codec to use for decoding
     * @return the decoded raw cursor string, or empty for a first-page request
     * @throws InvalidCursorException if the token is malformed or tampered with
     */
    public Optional<String> decodeCursor(CursorCodec codec) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(codec.decode(cursor));
    }

    /**
     * Convenience: decodes using {@link PlainCursorCodec} (no signing or verification).
     *
     * <p><strong>Warning:</strong> Always uses {@link PlainCursorCodec}, bypassing any codec
     * configured in the application's Dagger module. If you have configured
     * a signing codec, use {@link #decodeCursor(CursorCodec)} instead.
     *
     * @return the raw cursor string, or empty for a first-page request
     */
    public Optional<String> decodeCursor() {
        return decodeCursor(PlainCursorCodec.INSTANCE);
    }

    /**
     * Returns the {@code pageSize} query parameter value, or the given default if not supplied.
     * Clamped to {@code >= 1}.
     *
     * @param defaultValue the page size to return when the client did not specify one
     * @return the requested page size, or {@code defaultValue} if not supplied, clamped to {@code >= 1}
     */
    public int pageSize(int defaultValue) {
        int raw = pageSize != null ? pageSize : defaultValue;
        return Math.max(1, raw);
    }

    /**
     * Returns the {@code pageSize} query parameter value clamped between 1 and {@code maxValue},
     * or {@code defaultValue} (also clamped) if not supplied.
     *
     * @param defaultValue the page size to return when the client did not specify one
     * @param maxValue     the maximum allowed page size
     * @return the page size, clamped to [{@code 1}, {@code maxValue}]
     */
    public int pageSize(int defaultValue, int maxValue) {
        int raw = pageSize != null ? pageSize : defaultValue;
        return Math.min(Math.max(1, raw), maxValue);
    }
}
