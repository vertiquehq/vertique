// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Stateless cursor for keyset pagination. Encodes the keyset column values from the boundary row,
 * page size, and navigation direction into an opaque Base64URL token that survives HTTP
 * round-trips. Sort direction is owned by the {@link PagedQuery}, not the cursor.
 *
 * <p>Cursors are created in three ways:
 *
 * <ul>
 *   <li>{@link #first(int)} — for the first page (no keyset values)
 *   <li>{@link #fromToken(String)} — decode a cursor token returned in {@link PagedResult}
 *   <li>{@link #of(List, int, boolean)} — construct directly (used internally by {@link
 *       PagedQuery})
 * </ul>
 *
 * <h3>Type-safe serialization</h3>
 *
 * <p>Keyset values are serialized with type prefixes to preserve their Java types across
 * encode/decode cycles. Built-in types are handled by {@link CursorCodecs#defaults()}. Custom
 * types can be registered globally via {@link #registerCodec(CursorValueCodec)}, or provided
 * per-cursor via {@link #of(List, int, boolean, CursorCodecs)} and
 * {@link #fromToken(String, CursorCodecs)}.
 *
 * <table>
 *   <tr><th>Java type</th><th>Prefix</th><th>Example</th></tr>
 *   <tr><td>{@code null}</td><td>{@code null:}</td><td>{@code null:}</td></tr>
 *   <tr><td>{@link java.util.UUID}</td><td>{@code uuid:}</td><td>{@code uuid:550e8400-e29b-41d4-a716-446655440000}</td></tr>
 *   <tr><td>{@link java.time.Instant}</td><td>{@code instant:}</td><td>{@code instant:2024-01-15T10:30:00Z}</td></tr>
 *   <tr><td>{@link java.time.OffsetDateTime}</td><td>{@code odt:}</td><td>{@code odt:2024-01-15T10:30:00+01:00}</td></tr>
 *   <tr><td>{@link java.time.LocalDateTime}</td><td>{@code ldt:}</td><td>{@code ldt:2024-01-15T10:30:00}</td></tr>
 *   <tr><td>{@link LocalDate}</td><td>{@code date:}</td><td>{@code date:2024-01-15}</td></tr>
 *   <tr><td>{@link String}</td><td>{@code str:}</td><td>{@code str:hello}</td></tr>
 *   <tr><td>{@link Integer}</td><td>{@code int:}</td><td>{@code int:42}</td></tr>
 *   <tr><td>{@link Long}</td><td>{@code long:}</td><td>{@code long:9876543210}</td></tr>
 *   <tr><td>{@link Double}</td><td>{@code double:}</td><td>{@code double:3.14}</td></tr>
 *   <tr><td>{@link Float}</td><td>{@code float:}</td><td>{@code float:1.5}</td></tr>
 *   <tr><td>{@link Short}</td><td>{@code short:}</td><td>{@code short:32767}</td></tr>
 *   <tr><td>{@link Boolean}</td><td>{@code bool:}</td><td>{@code bool:true}</td></tr>
 *   <tr><td>{@link BigDecimal}</td><td>{@code bigdec:}</td><td>{@code bigdec:123456.789}</td></tr>
 * </table>
 *
 * @see PagedQuery
 * @see PagedResult
 * @see OrderDirection
 * @see CursorCodecs
 * @see CursorValueCodec
 */
public final class PageCursor {

    // --- Global codec state ---

    /** Global codec registry used by all factory methods that do not accept explicit codecs. */
    private static volatile CursorCodecs globalCodecs = CursorCodecs.defaults();

    // --- Instance fields ---

    private final List<Object> keysetValues;
    private final int pageSize;
    private final boolean backward;
    private final CursorCodecs codecs;

    private PageCursor(List<Object> keysetValues, int pageSize, boolean backward, CursorCodecs codecs) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive, got: " + pageSize);
        }
        this.keysetValues =
                keysetValues != null ? Collections.unmodifiableList(new ArrayList<>(keysetValues)) : List.of();
        this.pageSize = pageSize;
        this.backward = backward;
        this.codecs = codecs;
    }

    // --- Global codec registration ---

    /**
     * Registers a custom codec globally. Registered codecs are available to all future
     * {@link #fromToken(String)} and {@link #toToken()} calls that use the global codec set.
     *
     * <p>This method is thread-safe. For scoped codec use, prefer the overloaded factory methods
     * that accept a {@link CursorCodecs} instance.
     *
     * @param codec the codec to register globally
     * @throws NullPointerException     if {@code codec} is null
     * @throws IllegalArgumentException if the codec's prefix or type is already registered
     */
    public static synchronized void registerCodec(CursorValueCodec<?> codec) {
        globalCodecs = globalCodecs.with(codec);
    }

    // --- Factory methods ---

    /**
     * Creates a cursor for the first page with the given page size, using the global codec set.
     *
     * @param pageSize the number of items per page (must be positive)
     * @return a first-page cursor
     * @throws IllegalArgumentException if {@code pageSize} is not positive
     */
    public static PageCursor first(int pageSize) {
        return new PageCursor(List.of(), pageSize, false, globalCodecs);
    }

    /**
     * Creates a cursor with all fields specified, using the global codec set. Used internally by
     * {@link PagedQuery} to construct next/previous cursors from result rows.
     *
     * @param keysetValues the keyset column values from the boundary row
     * @param pageSize     the number of items per page (must be positive)
     * @param backward     {@code true} for backward (previous page) navigation
     * @return a new cursor
     * @throws IllegalArgumentException if {@code pageSize} is not positive
     */
    public static PageCursor of(List<Object> keysetValues, int pageSize, boolean backward) {
        return new PageCursor(keysetValues, pageSize, backward, globalCodecs);
    }

    /**
     * Creates a cursor with all fields specified and an explicit codec set. Use this overload when
     * working with custom keyset value types that are not registered globally.
     *
     * @param keysetValues the keyset column values from the boundary row
     * @param pageSize     the number of items per page (must be positive)
     * @param backward     {@code true} for backward (previous page) navigation
     * @param codecs       the codec set to use for token serialization
     * @return a new cursor
     * @throws IllegalArgumentException if {@code pageSize} is not positive
     */
    public static PageCursor of(List<Object> keysetValues, int pageSize, boolean backward, CursorCodecs codecs) {
        return new PageCursor(keysetValues, pageSize, backward, codecs);
    }

    /**
     * Decodes a cursor from a Base64URL-encoded token string previously produced by
     * {@link #toToken()}, using the global codec set.
     *
     * @param token the opaque cursor token
     * @return the decoded cursor
     * @throws IllegalArgumentException if the token is null, empty, malformed, or contains
     *     unsupported types
     */
    public static PageCursor fromToken(String token) {
        return fromToken(token, globalCodecs);
    }

    /**
     * Decodes a cursor from a Base64URL-encoded token string previously produced by
     * {@link #toToken(CursorCodecs)}, using the provided codec set. Use this overload when
     * working with custom keyset value types.
     *
     * @param token  the opaque cursor token
     * @param codecs the codec set to use for deserialization
     * @return the decoded cursor
     * @throws IllegalArgumentException if the token is null, empty, malformed, or contains
     *     type prefixes unknown to the provided codecs
     */
    public static PageCursor fromToken(String token, CursorCodecs codecs) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Cursor token must not be null or empty");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            var json = new JsonObject(new String(decoded, StandardCharsets.UTF_8));

            int pageSize = json.getInteger("s");
            boolean backward = json.getBoolean("b", false);

            JsonArray valuesArray = json.getJsonArray("v", new JsonArray());
            List<Object> keysetValues = new ArrayList<>(valuesArray.size());
            for (int i = 0; i < valuesArray.size(); i++) {
                keysetValues.add(codecs.deserialize(valuesArray.getString(i)));
            }

            return new PageCursor(keysetValues, pageSize, backward, codecs);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor token", e);
        }
    }

    // --- Token encoding ---

    /**
     * Encodes this cursor as a Base64URL token string using the codec set this cursor was created
     * with. The token is stateless and can be passed to clients for subsequent pagination requests.
     *
     * @return the opaque cursor token
     */
    public String toToken() {
        return toToken(this.codecs);
    }

    /**
     * Encodes this cursor as a Base64URL token string using the provided codec set. Use this
     * overload to serialize a cursor with a custom codec set different from the one it was
     * created with.
     *
     * @param codecs the codec set to use for serialization
     * @return the opaque cursor token
     */
    public String toToken(CursorCodecs codecs) {
        var valuesArray = new JsonArray();
        for (Object value : keysetValues) {
            valuesArray.add(codecs.serialize(value));
        }
        var json = new JsonObject().put("v", valuesArray).put("s", pageSize).put("b", backward);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.encode().getBytes(StandardCharsets.UTF_8));
    }

    // --- Accessors ---

    /**
     * Returns {@code true} if this is a first-page cursor (no keyset values).
     *
     * @return {@code true} if this cursor has no keyset values
     */
    public boolean isFirstPage() {
        return keysetValues.isEmpty();
    }

    /**
     * Returns the keyset column values from the boundary row. Empty for first-page cursors.
     *
     * @return an unmodifiable list of keyset values
     */
    public List<Object> keysetValues() {
        return keysetValues;
    }

    /**
     * Returns the page size.
     *
     * @return the number of items per page
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     * Returns {@code true} if this cursor navigates backward (previous page).
     *
     * @return {@code true} for backward navigation
     */
    public boolean backward() {
        return backward;
    }

    // --- Fluent page-size methods ---

    /**
     * Returns a new cursor with the given page size, carrying forward the same codec set.
     *
     * @param pageSize the new page size (must be positive)
     * @return a new cursor with the updated page size
     * @throws IllegalArgumentException if {@code pageSize} is not positive
     */
    public PageCursor withPageSize(int pageSize) {
        return new PageCursor(keysetValues, pageSize, backward, codecs);
    }

    /**
     * Returns a new cursor with the given page size, validated against min/max bounds. Throws
     * {@link PageSizeConstraintViolationException} if the page size is out of range.
     *
     * @param pageSize    the new page size
     * @param minPageSize the minimum allowed page size (inclusive)
     * @param maxPageSize the maximum allowed page size (inclusive)
     * @return a new cursor with the updated page size
     * @throws PageSizeConstraintViolationException if {@code pageSize} is outside the allowed range
     */
    public PageCursor withPageSize(int pageSize, int minPageSize, int maxPageSize) {
        if (pageSize < minPageSize || pageSize > maxPageSize) {
            throw new PageSizeConstraintViolationException(pageSize, minPageSize, maxPageSize);
        }
        return new PageCursor(keysetValues, pageSize, backward, codecs);
    }

    /**
     * Validates the current page size against min/max bounds. Returns this cursor if valid, throws
     * {@link PageSizeConstraintViolationException} if the current page size is out of range.
     *
     * @param minPageSize the minimum allowed page size (inclusive)
     * @param maxPageSize the maximum allowed page size (inclusive)
     * @return this cursor if the page size is within range
     * @throws PageSizeConstraintViolationException if the current page size is outside the range
     */
    public PageCursor validatePageSize(int minPageSize, int maxPageSize) {
        if (pageSize < minPageSize || pageSize > maxPageSize) {
            throw new PageSizeConstraintViolationException(pageSize, minPageSize, maxPageSize);
        }
        return this;
    }
}
