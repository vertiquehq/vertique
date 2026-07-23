// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.kafka.DeserializationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonKafkaDeserializer}: successful deserialization, failure on invalid
 * JSON, failure on empty bytes, custom {@link ObjectMapper} configuration propagation, and
 * security-critical value-free failure messages (W2: offending record value must not leak into the
 * exception message string).
 */
class JacksonKafkaDeserializerTest {

    record TestEvent(String name, int count) {}

    private static byte[] toBytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    // --- Successful deserialization ---

    @Nested
    @DisplayName("Successful deserialization")
    class SuccessfulDeserialization {

        @Test
        @DisplayName("valid JSON bytes are deserialized into the target type")
        void validJsonDeserializesToTargetType() throws DeserializationException {
            JacksonKafkaDeserializer<TestEvent> deserializer =
                    new JacksonKafkaDeserializer<>(TestEvent.class, new ObjectMapper());
            byte[] bytes = toBytes("{\"name\":\"order\",\"count\":5}");

            TestEvent event = deserializer.deserialize(bytes, "test-topic", Map.of());

            assertEquals("order", event.name());
            assertEquals(5, event.count());
        }
    }

    // --- Failure cases ---

    @Nested
    @DisplayName("Failure cases")
    class FailureCases {

        @Test
        @DisplayName("invalid JSON bytes throw DeserializationException")
        void invalidJsonThrowsDeserializationException() {
            JacksonKafkaDeserializer<TestEvent> deserializer =
                    new JacksonKafkaDeserializer<>(TestEvent.class, new ObjectMapper());
            byte[] bytes = toBytes("this is not json");

            assertThrows(DeserializationException.class, () -> deserializer.deserialize(bytes, "test-topic", Map.of()));
        }

        @Test
        @DisplayName("empty byte array throws DeserializationException")
        void emptyByteArrayThrowsDeserializationException() {
            JacksonKafkaDeserializer<TestEvent> deserializer =
                    new JacksonKafkaDeserializer<>(TestEvent.class, new ObjectMapper());

            assertThrows(
                    DeserializationException.class,
                    () -> deserializer.deserialize(new byte[0], "test-topic", Map.of()));
        }
    }

    // --- Custom ObjectMapper propagation ---

    @Nested
    @DisplayName("Custom ObjectMapper usage")
    class CustomObjectMapper {

        @Test
        @DisplayName(
                "custom ObjectMapper with FAIL_ON_UNKNOWN_PROPERTIES causes DeserializationException on unknown fields")
        void customMapperWithStrictUnknownPropertiesFailsOnUnknownField() {
            ObjectMapper strictMapper =
                    new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            JacksonKafkaDeserializer<TestEvent> deserializer =
                    new JacksonKafkaDeserializer<>(TestEvent.class, strictMapper);

            // JSON has unknown field "extra" that should cause failure with strict mapper
            byte[] bytes = toBytes("{\"name\":\"order\",\"count\":5,\"extra\":\"unknown\"}");

            assertThrows(
                    DeserializationException.class,
                    () -> deserializer.deserialize(bytes, "test-topic", Map.of()),
                    "Strict mapper should reject unknown properties");
        }

        @Test
        @DisplayName("lenient ObjectMapper ignores unknown properties and deserializes successfully")
        void lenientMapperIgnoresUnknownProperties() throws DeserializationException {
            ObjectMapper lenientMapper =
                    new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            JacksonKafkaDeserializer<TestEvent> deserializer =
                    new JacksonKafkaDeserializer<>(TestEvent.class, lenientMapper);

            byte[] bytes = toBytes("{\"name\":\"order\",\"count\":5,\"extra\":\"unknown\"}");

            TestEvent event = deserializer.deserialize(bytes, "test-topic", Map.of());
            assertEquals("order", event.name());
            assertEquals(5, event.count());
        }
    }

    // --- Security: value-free failure messages (W2) ---

    @Nested
    @DisplayName("Security: deserialization-failure messages are value-free (W2)")
    class ValueFreeMessages {

        /**
         * Given a strict ObjectMapper (ALLOW_COERCION_OF_SCALARS disabled) rejecting a string
         * where an int is expected, when deserialization fails, then:
         * (a) {@link DeserializationException#getMessage()} contains the target type simple name
         *     ({@code "TestEvent"}) but does NOT contain the offending scalar value from the record;
         * (b) {@link DeserializationException#getCause()} is non-null (debuggability preserved).
         *
         * <p>Jackson's {@code MismatchedInputException} message embeds the offending scalar
         * (e.g. {@code "SENTINEL_999"}) — this test proves the fix strips it from the
         * {@code DeserializationException} message string while keeping the cause for stacktrace.
         */
        @Test
        @DisplayName("failureMessage_isValueFree: type name present, offending scalar absent, cause preserved")
        void failureMessage_isValueFree() {
            // ALLOW_COERCION_OF_SCALARS disabled makes string→int fail, embedding the value in Jackson's msg
            ObjectMapper strictMapper = JsonMapper.builder()
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();
            JacksonKafkaDeserializer<TestEvent> deserializer =
                    new JacksonKafkaDeserializer<>(TestEvent.class, strictMapper);
            // The scalar "SENTINEL_999" is what Jackson would embed in the exception message
            byte[] bytes = toBytes("{\"name\":\"ok\",\"count\":\"SENTINEL_999\"}");

            DeserializationException ex = assertThrows(
                    DeserializationException.class, () -> deserializer.deserialize(bytes, "test-topic", Map.of()));

            // (a) type name must appear for operator context
            assertTrue(
                    ex.getMessage().contains("TestEvent"),
                    "message should contain the type simple name, got: " + ex.getMessage());
            // (a) offending scalar must NOT appear in the message (value-free contract)
            assertFalse(
                    ex.getMessage().contains("SENTINEL_999"),
                    "message must not contain the offending scalar, got: " + ex.getMessage());
            // (b) cause must be preserved for stacktrace diagnostics
            assertNotNull(ex.getCause(), "cause must be preserved for debugging");
        }
    }
}
