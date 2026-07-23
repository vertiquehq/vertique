// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Verifies {@link PayloadCodec} round-trip for POJO, scalar, and null payloads. */
class PayloadCodecTest {

    @Nested
    class encode {

        @Test
        @DisplayName("null returns null")
        void nullPayload() {
            assertNull(PayloadCodec.encode(null));
        }

        @Test
        @DisplayName("JsonObject passes through unchanged")
        void jsonObjectPassthrough() {
            JsonObject input = new JsonObject().put("key", "value");
            assertEquals(input, PayloadCodec.encode(input));
        }

        @Test
        @DisplayName("POJO is serialized as JsonObject via mapFrom")
        void pojoPayload() {
            record TestPayload(String name, int age) {}
            JsonObject result = PayloadCodec.encode(new TestPayload("Alice", 30));
            assertEquals("Alice", result.getString("name"));
            assertEquals(30, result.getInteger("age"));
        }

        @Test
        @DisplayName("String scalar is wrapped in _v envelope")
        void stringScalar() {
            JsonObject result = PayloadCodec.encode("hello");
            assertEquals("hello", result.getString("_v"));
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Integer scalar is wrapped in _v envelope")
        void integerScalar() {
            JsonObject result = PayloadCodec.encode(42);
            assertEquals(42, result.getInteger("_v"));
            assertEquals(1, result.size());
        }
    }

    @Nested
    class decode {

        @Test
        @DisplayName("null returns null")
        void nullPayload() {
            assertNull(PayloadCodec.decode(null));
        }

        @Test
        @DisplayName("regular JsonObject returns unchanged")
        void objectPayload() {
            JsonObject input = new JsonObject().put("key", "value");
            Object result = PayloadCodec.decode(input);
            assertInstanceOf(JsonObject.class, result);
            assertEquals(input, result);
        }

        @Test
        @DisplayName("scalar envelope is unwrapped to original value")
        void scalarUnwrap() {
            JsonObject wrapped = new JsonObject().put("_v", "hello");
            Object result = PayloadCodec.decode(wrapped);
            assertEquals("hello", result);
        }

        @Test
        @DisplayName("integer scalar envelope is unwrapped")
        void integerScalarUnwrap() {
            JsonObject wrapped = new JsonObject().put("_v", 42);
            Object result = PayloadCodec.decode(wrapped);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("JsonObject with _v key among others is NOT unwrapped")
        void nonScalarWithVKey() {
            JsonObject input = new JsonObject().put("_v", "test").put("other", "data");
            Object result = PayloadCodec.decode(input);
            assertInstanceOf(JsonObject.class, result);
        }
    }

    @Nested
    class roundTrip {

        @Test
        @DisplayName("String round-trips through encode/decode")
        void stringRoundTrip() {
            String original = "hello world";
            JsonObject encoded = PayloadCodec.encode(original);
            Object decoded = PayloadCodec.decode(encoded);
            assertEquals(original, decoded);
        }

        @Test
        @DisplayName("Integer round-trips through encode/decode")
        void integerRoundTrip() {
            int original = 42;
            JsonObject encoded = PayloadCodec.encode(original);
            Object decoded = PayloadCodec.decode(encoded);
            assertEquals(original, decoded);
        }

        @Test
        @DisplayName("POJO round-trips via typed decode")
        void pojoRoundTrip() {
            record TestPayload(String name, int age) {}
            TestPayload original = new TestPayload("Bob", 25);
            JsonObject encoded = PayloadCodec.encode(original);
            TestPayload decoded = PayloadCodec.decode(encoded, TestPayload.class);
            assertEquals(original.name(), decoded.name());
            assertEquals(original.age(), decoded.age());
        }
    }
}
