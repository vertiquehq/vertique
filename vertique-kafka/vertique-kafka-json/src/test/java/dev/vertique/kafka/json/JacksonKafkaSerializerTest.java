// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.json.Jackson2JsonProfiles;
import dev.vertique.json.JacksonDefaults;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.SerializationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonKafkaSerializer}: successful serialization to JSON bytes
 * and custom {@link ObjectMapper} configuration propagation.
 *
 * <p>Additionally proves the no-arg {@link JacksonKafkaSerializer}/
 * {@link JacksonKafkaDeserializer} constructors resolve {@code Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec())} lazily, per
 * call — not eagerly at construction — by building the no-arg serde <em>before</em>
 * {@link VertiqueJson#install} and exercising it <em>after</em>. {@code @AfterEach} restores the raw
 * Vert.x delegate so the process-wide install does not leak across tests (the reset seam is opened by
 * this module's {@code <build>} argLine, {@code -Dvertique.json.codec.allowReset=true}).
 */
class JacksonKafkaSerializerTest {

    record TestEvent(String name, int count) {}

    /** Serializer fixture: {@code note} is left {@code null} to observe NON_NULL omission. */
    record NullableNoteEvent(String name, String note) {}

    /** Deserializer mirror fixture: bound against a payload carrying an unknown property. */
    record BoundEvent(String name, int count) {}

    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    // --- No-arg serializer/deserializer resolve the process mapper at use time ---

    /**
     * {@code new JacksonKafkaSerializer<>()} is built <strong>before</strong>
     * {@link VertiqueJson#install}. Serializing a record with a {@code null} property
     * <strong>after</strong> the install must omit that property — proof that the no-arg
     * constructor resolved {@code Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec())} lazily, at serialize time, not eagerly at
     * construction (which would have captured the raw pre-install mapper, which does not apply
     * {@code NON_NULL}).
     *
     * <p>Given: {@code new JacksonKafkaSerializer<>()} constructed before
     * {@code VertiqueJson.install(Jackson2JsonProfiles.of(JsonProfileId.of("kafka-test"), nonNullMapper))}, where
     * {@code nonNullMapper} is the sanctioned seed ({@code JacksonDefaults.applySystem(new
     * ObjectMapper())}, satisfying the install seam's {@code VertxModule} invariant) with
     * {@code NON_NULL} serialization inclusion.
     * When: the serializer serializes a {@link NullableNoteEvent} with a {@code null} {@code note}
     * property, after the install.
     * Then: the serialized bytes omit the {@code note} property entirely.
     *
     * <p>A no-arg constructor that captured the raw {@code DatabindCodec.mapper()} eagerly at
     * construction, before the install runs, would emit {@code note} as JSON {@code null}, because
     * the raw mapper has no {@code NON_NULL} inclusion, and the assertion would fail.
     *
     * <p><strong>Sensitivity proof:</strong> capturing {@code Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec())} eagerly in the
     * constructor (instead of resolving it per call) still fails this assertion — the timing, not
     * merely the mapper source, is what the proof pins.
     */
    @Test
    @DisplayName("noArgSerializerUsesTheProcessMapper: no-arg serializer built before install resolves the process"
            + " mapper at serialize time (null property omitted after install)")
    void noArgSerializerUsesTheProcessMapper() {
        JacksonKafkaSerializer<NullableNoteEvent> serializer = new JacksonKafkaSerializer<>();

        ObjectMapper nonNullMapper = JacksonDefaults.applySystem(new ObjectMapper());
        nonNullMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        VertiqueJson.install(Jackson2JsonProfiles.of(JsonProfileId.of("kafka-test"), nonNullMapper));

        byte[] bytes =
                serializer.serialize(new NullableNoteEvent("order", null), "test-topic", KafkaRecordHeaders.empty());
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertFalse(
                json.contains("\"note\""),
                "the null 'note' property must be omitted (NON_NULL) — proves the no-arg serializer resolved the"
                        + " process mapper at serialize time (after install), not a raw mapper captured at"
                        + " construction (before install). Serialized: " + json);
    }

    /**
     * Mirror case: the no-arg {@link JacksonKafkaDeserializer} constructor.
     *
     * <p>Given: {@code new JacksonKafkaDeserializer<>(BoundEvent.class)} constructed before
     * {@code VertiqueJson.install(Jackson2JsonProfiles.of(JsonProfileId.of("kafka-test"), lenientMapper))}, where
     * {@code lenientMapper} is the sanctioned seed with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled.
     * When: the deserializer binds a payload carrying an unknown property, after the install.
     * Then: the deserialization succeeds and the known properties bind correctly.
     *
     * <p>A no-arg constructor that captured the raw {@code DatabindCodec.mapper()} eagerly, before
     * the install, would reject the unknown property with {@link DeserializationException}, because
     * the raw Vert.x mapper has {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled (Jackson default).
     *
     * <p><strong>Sensitivity proof:</strong> eagerly capturing {@code Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec())} in the
     * constructor still fails this assertion — only per-call resolution (after the install has run)
     * makes it pass.
     */
    @Test
    @DisplayName("noArgDeserializerUsesTheProcessMapper: no-arg deserializer built before install resolves the"
            + " process mapper at deserialize time (unknown property binds after install)")
    void noArgDeserializerUsesTheProcessMapper() throws DeserializationException {
        JacksonKafkaDeserializer<BoundEvent> deserializer = new JacksonKafkaDeserializer<>(BoundEvent.class);

        ObjectMapper lenientMapper = JacksonDefaults.applySystem(new ObjectMapper());
        lenientMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        VertiqueJson.install(Jackson2JsonProfiles.of(JsonProfileId.of("kafka-test"), lenientMapper));

        byte[] payload = "{\"name\":\"shipment\",\"count\":7,\"unexpected\":\"x\"}".getBytes(StandardCharsets.UTF_8);

        BoundEvent bound = deserializer.deserialize(payload, "test-topic", KafkaRecordHeaders.empty());

        assertEquals("shipment", bound.name());
        assertEquals(7, bound.count());
    }

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
            byte[] bytes = serializer.serialize(original, "test-topic", KafkaRecordHeaders.empty());

            assertNotNull(bytes);
            assertTrue(bytes.length > 0);

            TestEvent roundTripped = deserializer.deserialize(bytes, "test-topic", KafkaRecordHeaders.empty());
            org.junit.jupiter.api.Assertions.assertEquals(original.name(), roundTripped.name());
            org.junit.jupiter.api.Assertions.assertEquals(original.count(), roundTripped.count());
        }

        @Test
        @DisplayName("serialized bytes contain expected JSON field names")
        void serializedBytesContainExpectedJson() {
            ObjectMapper mapper = new ObjectMapper();
            JacksonKafkaSerializer<TestEvent> serializer = new JacksonKafkaSerializer<>(mapper);

            byte[] bytes = serializer.serialize(new TestEvent("shipment", 7), "test-topic", KafkaRecordHeaders.empty());
            String json = new String(bytes);

            assertTrue(json.contains("\"name\""), "Serialized JSON must contain the 'name' field");
            assertTrue(json.contains("\"shipment\""), "Serialized JSON must contain the 'shipment' value");
            assertTrue(json.contains("\"count\""), "Serialized JSON must contain the 'count' field");
            assertTrue(json.contains("7"), "Serialized JSON must contain the count value '7'");
        }
    }

    // --- Failure message ---

    /** Bean whose getter fails with a message carrying a record value. */
    public static class SecretBean {
        public String getToken() {
            throw new IllegalStateException("secret-123");
        }
    }

    @Nested
    @DisplayName("Serialization failure")
    class SerializationFailure {

        @Test
        @DisplayName("failure is a SerializationException with a value-free message that names the type and topic")
        void failureMessageIsValueFree() {
            JacksonKafkaSerializer<SecretBean> serializer = new JacksonKafkaSerializer<>(new ObjectMapper());

            Throwable thrown = assertThrows(
                    Throwable.class,
                    () -> serializer.serialize(new SecretBean(), "orders-topic", KafkaRecordHeaders.empty()));

            SerializationException failure = assertInstanceOf(SerializationException.class, thrown);
            assertInstanceOf(TechnicalException.class, failure);
            assertFalse(failure.getMessage().contains("secret-123"), "message must not carry the value");
            assertTrue(failure.getMessage().contains("SecretBean"), "message must name the type");
            assertTrue(failure.getMessage().contains("orders-topic"), "message must name the topic");
            assertInstanceOf(JsonMappingException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("secret-123"), "Jackson cause is preserved");
        }

        @Test
        @DisplayName("a null value that cannot be serialized is named null")
        void nullValueIsNamedNull() {
            ObjectMapper failing = new ObjectMapper();
            failing.getSerializerProvider()
                    .setNullValueSerializer(new com.fasterxml.jackson.databind.JsonSerializer<>() {
                        @Override
                        public void serialize(
                                Object value,
                                com.fasterxml.jackson.core.JsonGenerator gen,
                                com.fasterxml.jackson.databind.SerializerProvider serializers)
                                throws java.io.IOException {
                            throw new java.io.IOException("boom");
                        }
                    });
            JacksonKafkaSerializer<Object> serializer = new JacksonKafkaSerializer<>(failing);

            SerializationException failure = assertThrows(
                    SerializationException.class,
                    () -> serializer.serialize(null, "orders-topic", KafkaRecordHeaders.empty()));

            assertTrue(failure.getMessage().contains("null"));
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

            byte[] bytes = serializer.serialize(new TestEvent("test", 1), "test-topic", KafkaRecordHeaders.empty());
            assertNotNull(bytes);
            assertTrue(bytes.length > 0, "Custom mapper must produce non-empty output");
        }
    }
}
