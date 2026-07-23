// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RequestValue} — the full-parity drop-in for
 * {@code io.vertx.openapi.validation.RequestParameter}. Pins nullable-getter, default-getter, and
 * type-predicate semantics, plus the {@link RequestValue#of(Object)} factory round-trip, so later
 * slices and third-party body decoders can rely on the contract frozen in rest-017 PRD §A.2 (FR-024).
 */
class RequestValueTest {

    @Test
    @DisplayName("nullable getters return null when the wrapped value is null")
    void requestValueNullableGettersReturnNullWhenUnset() {
        RequestValue value = RequestValue.of(null);

        assertNull(value.getString());
        assertNull(value.getInteger());
        assertNull(value.getBoolean());
        assertNull(value.getJsonObject());
        assertNull(value.getBuffer());
    }

    @Test
    @DisplayName("predicates reflect the wrapped type for a String value")
    void requestValuePredicatesReflectType() {
        RequestValue value = RequestValue.of("hello");

        assertTrue(value.isString());
        assertFalse(value.isNumber());
        assertFalse(value.isNull());
        assertFalse(value.isEmpty());
    }

    @Test
    @DisplayName("of() factory round-trips String, Integer, and JsonObject through the typed getter")
    void requestValueOfFactoryRoundTrip() {
        assertEquals("hello", RequestValue.of("hello").getString());
        assertEquals(Integer.valueOf(7), RequestValue.of(7).getInteger());

        JsonObject json = new JsonObject().put("k", "v");
        assertEquals(json, RequestValue.of(json).getJsonObject());
    }

    @Test
    @DisplayName("default getters return the supplied default when the wrapped value is null")
    void requestValueDefaultGettersReturnDefault() {
        RequestValue value = RequestValue.of(null);

        assertEquals("default", value.getString("default"));
        assertEquals(Integer.valueOf(42), value.getInteger(42));
        assertEquals(Boolean.TRUE, value.getBoolean(true));
    }

    @Test
    @DisplayName("numeric getters coerce across number types (stored Long answers getInteger truncating)")
    void requestValueNumericCoercion() {
        assertEquals(Integer.valueOf(5), RequestValue.of(5L).getInteger());
        assertEquals(Long.valueOf(5L), RequestValue.of(5).getLong());
        assertEquals(Long.valueOf(3L), RequestValue.of(3.9d).getLong());
    }

    @Test
    @DisplayName("default getters return the default on type mismatch, not just on null")
    void requestValueDefaultGettersReturnDefaultOnTypeMismatch() {
        RequestValue stringValue = RequestValue.of("not-a-number");

        assertEquals(Integer.valueOf(99), stringValue.getInteger(99));
        assertEquals(Boolean.FALSE, stringValue.getBoolean(false));
    }

    @Test
    @DisplayName("isEmpty is true for null, empty string, empty json, and empty buffer; false for numbers")
    void requestValueIsEmptySemantics() {
        assertTrue(RequestValue.of(null).isEmpty());
        assertTrue(RequestValue.of("").isEmpty());
        assertTrue(RequestValue.of(new JsonObject()).isEmpty());
        assertTrue(RequestValue.of(new JsonArray()).isEmpty());
        assertTrue(RequestValue.of(Buffer.buffer()).isEmpty());

        assertFalse(RequestValue.of(0).isEmpty());
        assertFalse(RequestValue.of(false).isEmpty());
        assertFalse(RequestValue.of("x").isEmpty());
    }

    @Test
    @DisplayName("RequestValue exposes all 19 value methods, 8 predicates, and the of() factory")
    void requestValueHasAllTwentySevenAccessorsCompile() {
        RequestValue v = RequestValue.of("compile-check");

        // --- 10 nullable getters ---
        String ignoreString = v.getString();
        Integer ignoreInteger = v.getInteger();
        Long ignoreLong = v.getLong();
        Float ignoreFloat = v.getFloat();
        Double ignoreDouble = v.getDouble();
        Boolean ignoreBoolean = v.getBoolean();
        JsonObject ignoreJsonObject = v.getJsonObject();
        JsonArray ignoreJsonArray = v.getJsonArray();
        Buffer ignoreBuffer = v.getBuffer();
        Object ignoreObject = v.get();

        // --- 9 default getters ---
        String ignoreStringDef = v.getString("d");
        Integer ignoreIntegerDef = v.getInteger(0);
        Long ignoreLongDef = v.getLong(0L);
        Float ignoreFloatDef = v.getFloat(0f);
        Double ignoreDoubleDef = v.getDouble(0d);
        Boolean ignoreBooleanDef = v.getBoolean(false);
        JsonObject ignoreJsonObjectDef = v.getJsonObject(new JsonObject());
        JsonArray ignoreJsonArrayDef = v.getJsonArray(new JsonArray());
        Buffer ignoreBufferDef = v.getBuffer(Buffer.buffer());

        // --- 8 predicates ---
        boolean ignoreIsString = v.isString();
        boolean ignoreIsNumber = v.isNumber();
        boolean ignoreIsBoolean = v.isBoolean();
        boolean ignoreIsJsonObject = v.isJsonObject();
        boolean ignoreIsJsonArray = v.isJsonArray();
        boolean ignoreIsBuffer = v.isBuffer();
        boolean ignoreIsNull = v.isNull();
        boolean ignoreIsEmpty = v.isEmpty();

        // Reference every captured value so none is flagged unused and all members are exercised.
        assertEquals("compile-check", ignoreString);
        assertNull(ignoreInteger);
        assertNull(ignoreLong);
        assertNull(ignoreFloat);
        assertNull(ignoreDouble);
        assertNull(ignoreBoolean);
        assertNull(ignoreJsonObject);
        assertNull(ignoreJsonArray);
        assertNull(ignoreBuffer);
        assertEquals("compile-check", ignoreObject);
        assertEquals("compile-check", ignoreStringDef);
        assertEquals(Integer.valueOf(0), ignoreIntegerDef);
        assertEquals(Long.valueOf(0L), ignoreLongDef);
        assertEquals(Float.valueOf(0f), ignoreFloatDef);
        assertEquals(Double.valueOf(0d), ignoreDoubleDef);
        assertEquals(Boolean.FALSE, ignoreBooleanDef);
        assertEquals(new JsonObject(), ignoreJsonObjectDef);
        assertEquals(new JsonArray(), ignoreJsonArrayDef);
        assertEquals(Buffer.buffer(), ignoreBufferDef);
        assertTrue(ignoreIsString);
        assertFalse(ignoreIsNumber);
        assertFalse(ignoreIsBoolean);
        assertFalse(ignoreIsJsonObject);
        assertFalse(ignoreIsJsonArray);
        assertFalse(ignoreIsBuffer);
        assertFalse(ignoreIsNull);
        assertFalse(ignoreIsEmpty);
    }
}
