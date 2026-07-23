// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketMessageCodec}, verifying JSON encode/decode round-trips
 * and error handling for invalid JSON input.
 */
@DisplayName("WebSocketMessageCodec")
class WebSocketMessageCodecTest {

    /** Simple test payload used across encode/decode tests. */
    record TestMessage(String text, int count) {}

    private WebSocketMessageCodec codec;

    @BeforeEach
    void setUp() {
        codec = new WebSocketMessageCodec();
    }

    @Nested
    @DisplayName("encode")
    class Encode {

        @Test
        @DisplayName("encodes POJO to JSON string")
        void encodesPojoToJson() throws JsonProcessingException {
            TestMessage msg = new TestMessage("hello", 42);
            String json = codec.encode(msg);
            assertNotNull(json);
            // Verify the JSON contains the expected fields
            assertEquals("{\"text\":\"hello\",\"count\":42}", json);
        }

        @Test
        @DisplayName("encodes null-containing field to JSON with null value")
        void encodesNullField() throws JsonProcessingException {
            TestMessage msg = new TestMessage(null, 0);
            String json = codec.encode(msg);
            assertNotNull(json);
            assertEquals("{\"text\":null,\"count\":0}", json);
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        @DisplayName("decodes JSON string to POJO")
        void decodesJsonToPojo() throws JsonProcessingException {
            String json = "{\"text\":\"world\",\"count\":7}";
            TestMessage msg = codec.decode(json, TestMessage.class);
            assertNotNull(msg);
            assertEquals("world", msg.text());
            assertEquals(7, msg.count());
        }

        @Test
        @DisplayName("decode of invalid JSON throws JsonProcessingException")
        void invalidJsonThrows() {
            assertThrows(JsonProcessingException.class, () -> codec.decode("not-valid-json", TestMessage.class));
        }

        @Test
        @DisplayName("decode of empty object produces default-valued record")
        void emptyObjectProducesDefaults() throws JsonProcessingException {
            String json = "{}";
            TestMessage msg = codec.decode(json, TestMessage.class);
            assertNotNull(msg);
            assertNull(msg.text());
            assertEquals(0, msg.count());
        }

        /** Convenience null assertion — avoids importing Assertions on every use. */
        private void assertNull(Object o) {
            org.junit.jupiter.api.Assertions.assertNull(o);
        }
    }

    @Nested
    @DisplayName("encode → decode round-trip")
    class RoundTrip {

        @Test
        @DisplayName("round-trip preserves POJO equality")
        void roundTripPreservesEquality() throws JsonProcessingException {
            TestMessage original = new TestMessage("round-trip", 99);
            String json = codec.encode(original);
            TestMessage decoded = codec.decode(json, TestMessage.class);
            assertEquals(original, decoded);
        }
    }
}
