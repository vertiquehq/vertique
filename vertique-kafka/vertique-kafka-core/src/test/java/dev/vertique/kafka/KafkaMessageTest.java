// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaMessage} immutability, defensive copying of the headers map,
 * and {@link KafkaMessage#header(String)} lookup semantics.
 */
class KafkaMessageTest {

    // --- Defensive copy ---

    @Nested
    @DisplayName("Defensive header copying")
    class DefensiveCopy {

        @Test
        @DisplayName("the headers map exposed by the record is unmodifiable")
        void exposedHeadersMapIsUnmodifiable() {
            Map<String, String> headers = new HashMap<>();
            headers.put("key", "value");

            KafkaMessage<String> message = new KafkaMessage<>("val", "k", "topic", 0, 0L, 0L, headers);

            assertThrows(
                    UnsupportedOperationException.class, () -> message.headers().put("new-key", "new-value"));
        }

        @Test
        @DisplayName("null headers map is converted to an empty map")
        void nullHeadersProducesEmptyMap() {
            KafkaMessage<String> message = new KafkaMessage<>("value", null, "topic", 0, 0L, 0L, null);

            assertNotNull(message.headers());
            assertTrue(message.headers().isEmpty());
        }
    }

    // --- header() lookup ---

    @Nested
    @DisplayName("header() lookup")
    class HeaderLookup {

        @Test
        @DisplayName("header() returns Optional.of for a header that is present")
        void returnsOptionalOfForPresentHeader() {
            KafkaMessage<String> message =
                    new KafkaMessage<>("value", null, "topic", 0, 0L, 0L, Map.of("content-type", "application/json"));

            Optional<String> result = message.header("content-type");
            assertTrue(result.isPresent());
            assertEquals("application/json", result.get());
        }

        @Test
        @DisplayName("header() returns Optional.empty for a header that is missing")
        void returnsOptionalEmptyForMissingHeader() {
            KafkaMessage<String> message =
                    new KafkaMessage<>("value", null, "topic", 0, 0L, 0L, Map.of("other-header", "value"));

            Optional<String> result = message.header("content-type");
            assertFalse(result.isPresent());
        }
    }

    // --- Record accessors ---

    @Nested
    @DisplayName("Record component accessors")
    class Accessors {

        @Test
        @DisplayName("all component accessors return the values passed at construction")
        void componentAccessorsReturnConstructedValues() {
            Map<String, String> headers = Map.of("h", "v");
            KafkaMessage<Integer> message =
                    new KafkaMessage<>(42, "my-key", "my-topic", 3, 99L, 1_700_000_000_000L, headers);

            assertEquals(42, message.value());
            assertEquals("my-key", message.key());
            assertEquals("my-topic", message.topic());
            assertEquals(3, message.partition());
            assertEquals(99L, message.offset());
            assertEquals(1_700_000_000_000L, message.timestamp());
            assertEquals("v", message.headers().get("h"));
        }
    }
}
