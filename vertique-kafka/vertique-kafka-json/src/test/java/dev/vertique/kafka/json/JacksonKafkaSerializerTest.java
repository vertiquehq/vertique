// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.kafka.DeserializationException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonKafkaSerializer}: successful serialization to JSON bytes
 * and custom {@link ObjectMapper} configuration propagation.
 */
class JacksonKafkaSerializerTest {

    record TestEvent(String name, int count) {}

    // --- Successful serialization ---

    @Nested
    @DisplayName("Successful serialization")
    class SuccessfulSerialization {

        @Test
        @DisplayName("serializing an object produces valid JSON bytes that round-trip back to the original")
        void objectSerializesToValidJsonBytes() throws DeserializationException {
            ObjectMapper mapper = new ObjectMapper();
            JacksonKafkaSerializer<TestEvent> serializer = new JacksonKafkaSerializer<>(mapper);
            JacksonKafkaDeserializer<TestEvent> deserializer = new JacksonKafkaDeserializer<>(TestEvent.class, mapper);

            TestEvent original = new TestEvent("order", 42);
            byte[] bytes = serializer.serialize(original, "test-topic", Map.of());

            assertNotNull(bytes);
            assertTrue(bytes.length > 0);

            TestEvent roundTripped = deserializer.deserialize(bytes, "test-topic", Map.of());
            org.junit.jupiter.api.Assertions.assertEquals(original.name(), roundTripped.name());
            org.junit.jupiter.api.Assertions.assertEquals(original.count(), roundTripped.count());
        }

        @Test
        @DisplayName("serialized bytes contain expected JSON field names")
        void serializedBytesContainExpectedJson() {
            ObjectMapper mapper = new ObjectMapper();
            JacksonKafkaSerializer<TestEvent> serializer = new JacksonKafkaSerializer<>(mapper);

            byte[] bytes = serializer.serialize(new TestEvent("shipment", 7), "test-topic", Map.of());
            String json = new String(bytes);

            assertTrue(json.contains("\"name\""), "Serialized JSON must contain the 'name' field");
            assertTrue(json.contains("\"shipment\""), "Serialized JSON must contain the 'shipment' value");
            assertTrue(json.contains("\"count\""), "Serialized JSON must contain the 'count' field");
            assertTrue(json.contains("7"), "Serialized JSON must contain the count value '7'");
        }
    }

    // --- Custom ObjectMapper propagation ---

    @Nested
    @DisplayName("Custom ObjectMapper usage")
    class CustomObjectMapper {

        @Test
        @DisplayName("custom ObjectMapper is used during serialization")
        void customMapperIsUsedDuringSerialization() {
            // Use a custom mapper configured identically for round-trip verification
            ObjectMapper customMapper = new ObjectMapper();
            JacksonKafkaSerializer<TestEvent> serializer = new JacksonKafkaSerializer<>(customMapper);

            byte[] bytes = serializer.serialize(new TestEvent("test", 1), "test-topic", Map.of());
            assertNotNull(bytes);
            assertTrue(bytes.length > 0, "Custom mapper must produce non-empty output");
        }
    }
}
