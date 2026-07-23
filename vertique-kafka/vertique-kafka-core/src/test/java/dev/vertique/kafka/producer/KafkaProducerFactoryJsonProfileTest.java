// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link KafkaProducerFactory} reads the {@link JsonProfile @JsonProfile} annotation on a
 * {@code @KafkaProducer} interface as the sole per-binding profile selector, applying the
 * FR-JSON-066 method-level reject at <em>producer-build time</em>.
 *
 * <p>Build time for a producer is {@link KafkaProducerFactory#resolveMethodSerializers}: the static
 * seam the sibling {@code ProducerValueJsonProfilePrecedenceTest} already drives to observe the
 * resolved profile (via the {@code "jsonProfile"} key the producer threads into the serde bag
 * handed to the JSON provider) and to surface build failures (the method throws
 * {@link IllegalStateException} for invalid annotation configuration — matching the rest-client
 * sibling guard {@code RestClientJsonProfileAnnotationTest}).
 */
@DisplayName("KafkaProducerFactory @JsonProfile annotation reading")
class KafkaProducerFactoryJsonProfileTest {

    // --- Fixtures ---

    record Payload(String field) {}

    /** {@code @JsonProfile("orders-v2")} on the producer interface. */
    @KafkaProducer(name = "p")
    @JsonProfile("orders-v2")
    interface JsonProfileOnlyProducer {
        @Topic("the.topic")
        Future<RecordMetadata> publish(Payload value);
    }

    /** Method-level {@code @JsonProfile} on a {@code @KafkaProducer} interface (FR-066 reject). */
    @KafkaProducer(name = "p")
    interface MethodLevelJsonProfileProducer {
        @Topic("the.topic")
        @JsonProfile("v2")
        Future<RecordMetadata> publish(Payload value);
    }

    /**
     * A JSON-format provider that records the {@code jsonProfile} seen on the merged serde bag
     * when its {@link #serializer} is built, exposing the resolution outcome to assertions.
     */
    static final class CapturingJsonProvider implements KafkaSerdeProvider {

        final AtomicReference<String> capturedProfile = new AtomicReference<>();

        @Override
        public String format() {
            return "json";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return false;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            capturedProfile.set(endpointConfig.getString("jsonProfile"));
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            throw new UnsupportedOperationException("not needed for producer tests");
        }
    }

    // --- Helpers (mirror ProducerValueJsonProfilePrecedenceTest) ---

    /** Builds the typed {@link KafkaConfig} for producer {@code "p"} from a raw producer block. */
    private static KafkaConfig typedConfig(JsonObject producerBlock) {
        JsonObject kafka = new JsonObject().put("producers", new JsonObject().put("p", producerBlock));
        return KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafka), new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    /** Drives {@link KafkaProducerFactory#resolveMethodSerializers} — the producer-build seam. */
    private static void resolve(Class<?> producerInterface, KafkaSerdeRegistry registry) {
        KafkaConfig typed = typedConfig(new JsonObject());
        KafkaProducerFactory.resolveMethodSerializers(
                producerInterface, "p", typed.producerIndex().get("p"), typed, registry);
    }

    // --- 1. interface @JsonProfile is read (FR-JSON-060/062) ---

    @Test
    @DisplayName("producerInterfaceWithJsonProfileAnnotation_usesProfileForSerialization — @JsonProfile(\"orders-v2\")"
            + " reaches the serde bag")
    void producerInterfaceWithJsonProfileAnnotation_usesProfileForSerialization() {
        // GIVEN a @KafkaProducer interface annotated @JsonProfile("orders-v2").
        CapturingJsonProvider json = new CapturingJsonProvider();

        // WHEN the producer factory builds the serializers.
        resolve(JsonProfileOnlyProducer.class, new KafkaSerdeRegistry(Set.of(json)));

        // THEN the "orders-v2" profile reaches the serde bag (no exception).
        assertEquals(
                "orders-v2",
                json.capturedProfile.get(),
                "interface-level @JsonProfile(\"orders-v2\") must reach the producer serde bag");
    }

    // --- 2. method-level @JsonProfile on a TYPE-only boundary fails fast (FR-JSON-066) ---

    @Test
    @DisplayName(
            "methodLevelJsonProfileOnKafkaProducerInterface_failsProducerBuild — names the method + TYPE placement")
    void methodLevelJsonProfileOnKafkaProducerInterface_failsProducerBuild() {
        // GIVEN a @KafkaProducer interface whose producer method carries @JsonProfile("v2").
        CapturingJsonProvider json = new CapturingJsonProvider();

        // WHEN building the producer, THEN an IllegalStateException naming the method + TYPE-level placement.
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> resolve(MethodLevelJsonProfileProducer.class, new KafkaSerdeRegistry(Set.of(json))));

        String message = ex.getMessage();
        assertTrue(message.contains("publish"), "message should name the offending method: " + message);
        assertTrue(message.contains("TYPE"), "message should name the required TYPE-level placement: " + message);
    }

    // --- 3. legacy attribute removal (Phase 3 contract) ---

    @Test
    @DisplayName("kafkaProducerValueJsonProfileAttribute_noLongerExists — the legacy attribute method is gone")
    void kafkaProducerValueJsonProfileAttribute_noLongerExists() {
        // Proves the legacy attribute was removed from the @KafkaProducer annotation class.
        assertThrows(NoSuchMethodException.class, () -> KafkaProducer.class.getDeclaredMethod("valueJsonProfile"));
    }
}
