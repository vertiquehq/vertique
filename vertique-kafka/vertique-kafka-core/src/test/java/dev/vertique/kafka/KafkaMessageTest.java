// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.buffer.Buffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaMessage}: the headers it carries are the immutable
 * {@link KafkaRecordHeaders} it was given, and nothing reachable from the message can change them.
 */
class KafkaMessageTest {

    private static KafkaMessage<String> messageWith(KafkaRecordHeaders headers) {
        return new KafkaMessage<>("value", "k", "topic", 0, 0L, 0L, headers);
    }

    // --- Immutability ---

    @Nested
    @DisplayName("Headers are immutable")
    class ImmutableHeaders {

        @Test
        @DisplayName("the message holds the header collection it was given, not a copy")
        void holdsTheGivenInstance() {
            KafkaRecordHeaders headers = KafkaRecordHeaders.of(Map.of("key", "value"));

            assertSame(headers, messageWith(headers).headers());
        }

        @Test
        @DisplayName("neither the entry list nor the text map of the headers accepts a change")
        void exposedHeadersCannotBeChanged() {
            KafkaMessage<String> message = messageWith(KafkaRecordHeaders.of(Map.of("key", "value")));

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> message.headers().entries().add(KafkaRecordHeader.ofUtf8("new-key", "new-value")));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> message.headers().entries().remove(0));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> message.headers().asMap().put("new-key", "new-value"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> message.headers().asMap().remove("key"));
            assertEquals(KafkaRecordHeaders.of(Map.of("key", "value")), message.headers());
        }

        @Test
        @DisplayName("writing to a value buffer read from the message does not change the message")
        void valueBufferIsACopy() {
            KafkaMessage<String> message = messageWith(
                    new KafkaRecordHeaders(List.of(new KafkaRecordHeader("bin", Buffer.buffer(new byte[] {1, 2, 3})))));

            Buffer read = message.headers().entries().get(0).value();
            read.setByte(0, (byte) 9);
            read.appendByte((byte) 4);

            assertEquals(
                    Buffer.buffer(new byte[] {1, 2, 3}),
                    message.headers().entries().get(0).value());
        }

        @Test
        @DisplayName("null headers become the shared empty header collection")
        void nullHeadersBecomeEmpty() {
            KafkaMessage<String> message = messageWith(null);

            assertSame(KafkaRecordHeaders.empty(), message.headers());
        }
    }

    // --- Reading one header ---

    @Nested
    @DisplayName("Reading one header")
    class HeaderLookup {

        private final KafkaMessage<String> message = messageWith(new KafkaRecordHeaders(List.of(
                KafkaRecordHeader.ofUtf8("content-type", "text/plain"),
                KafkaRecordHeader.ofUtf8("content-type", "application/json"),
                new KafkaRecordHeader("cleared", null))));

        @Test
        @DisplayName("lastHeader gives the last header of a key, and empty for a key that is absent")
        void lastHeader() {
            assertEquals(
                    "application/json",
                    message.headers().lastHeader("content-type").orElseThrow().valueAsUtf8());
            assertTrue(message.headers().lastHeader("missing").isEmpty());
            assertNull(message.headers().lastHeader("cleared").orElseThrow().value());
        }

        @Test
        @DisplayName("the text map gives the last non-null value, and nothing for an absent or null-valued key")
        void textMap() {
            assertEquals("application/json", message.headers().asMap().get("content-type"));
            assertNull(message.headers().asMap().get("missing"));
            assertFalse(message.headers().asMap().containsKey("cleared"));
        }
    }

    // --- Record accessors ---

    @Nested
    @DisplayName("Record component accessors")
    class Accessors {

        @Test
        @DisplayName("all component accessors return the values passed at construction")
        void componentAccessorsReturnConstructedValues() {
            KafkaRecordHeaders headers = KafkaRecordHeaders.of(Map.of("h", "v"));
            KafkaMessage<Integer> message =
                    new KafkaMessage<>(42, "my-key", "my-topic", 3, 99L, 1_700_000_000_000L, headers);

            assertEquals(42, message.value());
            assertEquals("my-key", message.key());
            assertEquals("my-topic", message.topic());
            assertEquals(3, message.partition());
            assertEquals(99L, message.offset());
            assertEquals(1_700_000_000_000L, message.timestamp());
            assertSame(headers, message.headers());
        }
    }
}
