// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Default {@link RequestValue} implementation wrapping a single raw {@link Object} value (which may
 * be {@code null}).
 *
 * <p>Semantics match Vert.x's request {@code Parameter} accessors: nullable getters return the
 * value only when it is of the matching type (no stringification of non-strings); the numeric
 * getters are guarded by {@link #isNumber()} so a stored {@code Long} answers {@link #getInteger()}
 * (truncating), a stored {@code Double} answers {@link #getLong()}, and so on; default getters
 * return the default on both {@code null} and type mismatch.
 */
final class DefaultRequestValue implements RequestValue {

    private final Object value;

    /**
     * Creates a wrapper over the given raw value.
     *
     * @param value the value to wrap, or {@code null}
     */
    DefaultRequestValue(Object value) {
        this.value = value;
    }

    // --- Nullable typed getters ---

    @Override
    public String getString() {
        return isString() ? (String) value : null;
    }

    @Override
    public Integer getInteger() {
        return isNumber() ? ((Number) value).intValue() : null;
    }

    @Override
    public Long getLong() {
        return isNumber() ? ((Number) value).longValue() : null;
    }

    @Override
    public Float getFloat() {
        return isNumber() ? ((Number) value).floatValue() : null;
    }

    @Override
    public Double getDouble() {
        return isNumber() ? ((Number) value).doubleValue() : null;
    }

    @Override
    public Boolean getBoolean() {
        return isBoolean() ? (Boolean) value : null;
    }

    @Override
    public JsonObject getJsonObject() {
        return isJsonObject() ? (JsonObject) value : null;
    }

    @Override
    public JsonArray getJsonArray() {
        return isJsonArray() ? (JsonArray) value : null;
    }

    @Override
    public Buffer getBuffer() {
        return isBuffer() ? (Buffer) value : null;
    }

    @Override
    public Object get() {
        return value;
    }

    // --- Default-returning typed getters ---

    @Override
    public String getString(String def) {
        return isString() ? getString() : def;
    }

    @Override
    public Integer getInteger(Integer def) {
        return isNumber() ? getInteger() : def;
    }

    @Override
    public Long getLong(Long def) {
        return isNumber() ? getLong() : def;
    }

    @Override
    public Float getFloat(Float def) {
        return isNumber() ? getFloat() : def;
    }

    @Override
    public Double getDouble(Double def) {
        return isNumber() ? getDouble() : def;
    }

    @Override
    public Boolean getBoolean(Boolean def) {
        return isBoolean() ? getBoolean() : def;
    }

    @Override
    public JsonObject getJsonObject(JsonObject def) {
        return isJsonObject() ? getJsonObject() : def;
    }

    @Override
    public JsonArray getJsonArray(JsonArray def) {
        return isJsonArray() ? getJsonArray() : def;
    }

    @Override
    public Buffer getBuffer(Buffer def) {
        return isBuffer() ? getBuffer() : def;
    }

    // --- Type predicates ---

    @Override
    public boolean isString() {
        return value instanceof String;
    }

    @Override
    public boolean isNumber() {
        return value instanceof Number;
    }

    @Override
    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    @Override
    public boolean isJsonObject() {
        return value instanceof JsonObject;
    }

    @Override
    public boolean isJsonArray() {
        return value instanceof JsonArray;
    }

    @Override
    public boolean isBuffer() {
        return value instanceof Buffer;
    }

    @Override
    public boolean isNull() {
        return value == null;
    }

    @Override
    public boolean isEmpty() {
        return isNull()
                || (isString() && getString().isEmpty())
                || (isJsonObject() && getJsonObject().isEmpty())
                || (isJsonArray() && getJsonArray().isEmpty())
                || (isBuffer() && getBuffer().length() == 0);
    }
}
