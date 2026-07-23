// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Transport-neutral view of a single decoded request value (a parameter or a request body element).
 *
 * <p>This is a full-parity, drop-in replacement for Vert.x's {@code RequestParameter} surface: 10
 * nullable typed getters, 9 default-returning typed getters, and 8 type predicates. The full surface
 * is preserved so that third-party {@link RequestBodyDecoder} implementations keep every accessor
 * they relied on.
 *
 * <p>Semantics (matching Vert.x's request {@code Parameter} accessors):
 *
 * <ul>
 *   <li>Nullable getters return the value when it is of the matching type, otherwise {@code null}.
 *       Strings are <em>not</em> stringified — {@link #getString()} returns {@code null} for a
 *       non-string value.
 *   <li>The four numeric getters are guarded by {@link #isNumber()} rather than an exact type, so a
 *       stored {@code Long} answers {@link #getInteger()} (truncating), a stored {@code Double}
 *       answers {@link #getLong()}, and so on.
 *   <li>Default getters return the supplied default for both a {@code null} value and a type
 *       mismatch.
 *   <li>{@link #isEmpty()} is {@code true} for {@code null}, an empty string, an empty
 *       {@link JsonObject}, an empty {@link JsonArray}, or an empty {@link Buffer}. Numbers and
 *       booleans are never empty.
 * </ul>
 */
public interface RequestValue {

    // --- Nullable typed getters ---

    /**
     * Returns the value as a {@link String} when it is a string, otherwise {@code null}.
     *
     * @return the string value, or {@code null}
     */
    String getString();

    /**
     * Returns the value as an {@link Integer} when it is a number (truncating), otherwise
     * {@code null}.
     *
     * @return the integer value, or {@code null}
     */
    Integer getInteger();

    /**
     * Returns the value as a {@link Long} when it is a number (truncating), otherwise {@code null}.
     *
     * @return the long value, or {@code null}
     */
    Long getLong();

    /**
     * Returns the value as a {@link Float} when it is a number, otherwise {@code null}.
     *
     * @return the float value, or {@code null}
     */
    Float getFloat();

    /**
     * Returns the value as a {@link Double} when it is a number, otherwise {@code null}.
     *
     * @return the double value, or {@code null}
     */
    Double getDouble();

    /**
     * Returns the value as a {@link Boolean} when it is a boolean, otherwise {@code null}.
     *
     * @return the boolean value, or {@code null}
     */
    Boolean getBoolean();

    /**
     * Returns the value as a {@link JsonObject} when it is one, otherwise {@code null}.
     *
     * @return the JSON object value, or {@code null}
     */
    JsonObject getJsonObject();

    /**
     * Returns the value as a {@link JsonArray} when it is one, otherwise {@code null}.
     *
     * @return the JSON array value, or {@code null}
     */
    JsonArray getJsonArray();

    /**
     * Returns the value as a {@link Buffer} when it is one, otherwise {@code null}.
     *
     * @return the buffer value, or {@code null}
     */
    Buffer getBuffer();

    /**
     * Returns the raw wrapped value, which may be {@code null}.
     *
     * @return the raw value, or {@code null}
     */
    Object get();

    // --- Default-returning typed getters ---

    /**
     * Returns the value as a {@link String}, or the supplied default when the value is not a string.
     *
     * @param def the default to return on null or type mismatch
     * @return the string value, or {@code def}
     */
    String getString(String def);

    /**
     * Returns the value as an {@link Integer}, or the supplied default when the value is not a
     * number.
     *
     * @param def the default to return on null or type mismatch
     * @return the integer value, or {@code def}
     */
    Integer getInteger(Integer def);

    /**
     * Returns the value as a {@link Long}, or the supplied default when the value is not a number.
     *
     * @param def the default to return on null or type mismatch
     * @return the long value, or {@code def}
     */
    Long getLong(Long def);

    /**
     * Returns the value as a {@link Float}, or the supplied default when the value is not a number.
     *
     * @param def the default to return on null or type mismatch
     * @return the float value, or {@code def}
     */
    Float getFloat(Float def);

    /**
     * Returns the value as a {@link Double}, or the supplied default when the value is not a number.
     *
     * @param def the default to return on null or type mismatch
     * @return the double value, or {@code def}
     */
    Double getDouble(Double def);

    /**
     * Returns the value as a {@link Boolean}, or the supplied default when the value is not a
     * boolean.
     *
     * @param def the default to return on null or type mismatch
     * @return the boolean value, or {@code def}
     */
    Boolean getBoolean(Boolean def);

    /**
     * Returns the value as a {@link JsonObject}, or the supplied default when the value is not one.
     *
     * @param def the default to return on null or type mismatch
     * @return the JSON object value, or {@code def}
     */
    JsonObject getJsonObject(JsonObject def);

    /**
     * Returns the value as a {@link JsonArray}, or the supplied default when the value is not one.
     *
     * @param def the default to return on null or type mismatch
     * @return the JSON array value, or {@code def}
     */
    JsonArray getJsonArray(JsonArray def);

    /**
     * Returns the value as a {@link Buffer}, or the supplied default when the value is not one.
     *
     * @param def the default to return on null or type mismatch
     * @return the buffer value, or {@code def}
     */
    Buffer getBuffer(Buffer def);

    // --- Type predicates ---

    /**
     * Returns {@code true} if the wrapped value is a {@link String}.
     *
     * @return {@code true} if the value is a string
     */
    boolean isString();

    /**
     * Returns {@code true} if the wrapped value is a {@link Number}.
     *
     * @return {@code true} if the value is a number
     */
    boolean isNumber();

    /**
     * Returns {@code true} if the wrapped value is a {@link Boolean}.
     *
     * @return {@code true} if the value is a boolean
     */
    boolean isBoolean();

    /**
     * Returns {@code true} if the wrapped value is a {@link JsonObject}.
     *
     * @return {@code true} if the value is a JSON object
     */
    boolean isJsonObject();

    /**
     * Returns {@code true} if the wrapped value is a {@link JsonArray}.
     *
     * @return {@code true} if the value is a JSON array
     */
    boolean isJsonArray();

    /**
     * Returns {@code true} if the wrapped value is a {@link Buffer}.
     *
     * @return {@code true} if the value is a buffer
     */
    boolean isBuffer();

    /**
     * Returns {@code true} if the wrapped value is {@code null}.
     *
     * @return {@code true} if the value is null
     */
    boolean isNull();

    /**
     * Returns {@code true} if the wrapped value is {@code null} or an empty string, JSON object,
     * JSON array, or buffer. Numbers and booleans are never empty.
     *
     * @return {@code true} if the value is empty
     */
    boolean isEmpty();

    // --- Factory ---

    /**
     * Wraps a raw value (which may be {@code null}) as a {@link RequestValue}.
     *
     * @param value the value to wrap, or {@code null}
     * @return a {@link RequestValue} over the given value
     */
    static RequestValue of(Object value) {
        return new DefaultRequestValue(value);
    }
}
