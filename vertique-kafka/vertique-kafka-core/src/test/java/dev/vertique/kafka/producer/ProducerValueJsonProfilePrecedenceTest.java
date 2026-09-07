// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * Unit tests for the producer {@code jsonProfile} precedence (slice 4.3): the resolved profile
 * is threaded into the merged {@code serdeConfig} bag handed to {@link KafkaSerdeProvider#serializer}.
 *
 * <p>Precedence (FR-JSON-036C): per-method config {@code jsonProfile} &gt; per-producer config
 * {@code jsonProfile} &gt; {@code @JsonProfile} annotation &gt;
 * {@code vertx} default. The cleanest observable for the resolution logic is the top-level
 * {@code "jsonProfile"} key on the serde bag the JSON provider receives (present only when
 * non-null/non-blank), so a bag-capturing fake JSON provider records what each method's serializer
 * was built with.
 *
 * <p>The {@code explicitSerializer_stillWins} case asserts the non-JSON-format path (FR-JSON-036D/037):
 * a method that resolves to a non-JSON format is built by that format's provider, which never sees
 * (and never consults) the JSON {@code jsonProfile} key.
 */
@DisplayName("Producer jsonProfile precedence")
class ProducerValueJsonProfilePrecedenceTest {

    // --- Fixtures ---

    record Payload(String field) {}

    /** No annotation profile — exercises config-only precedence. */
    @KafkaProducer(name = "p")
    interface PlainProducer {
        @Topic("the.topic")
        Future<RecordMetadata> publish(Payload value);
    }

    /** Annotation profile "C" — exercises annotation-as-fallback precedence via {@code @JsonProfile}. */
    @JsonProfile("C")
    @KafkaProducer(name = "p")
    interface AnnotatedProducer {
        @Topic("the.topic")
        Future<RecordMetadata> publish(Payload value);
    }

    /** A fake record type the avro provider auto-detects, to exercise the non-JSON-format path. */
    interface AvroLike {}

    record AvroPayload(String field) implements AvroLike {}

    @KafkaProducer(name = "p")
    interface AvroProducer {
        @Topic("the.topic")
        Future<RecordMetadata> publish(AvroPayload value);
    }

    /**
     * A JSON-format provider that records the {@code jsonProfile} seen on the merged serde bag
     * when its {@link #serializer} is built, exposing the resolution outcome to assertions.
     */
    static final class CapturingJsonProvider implements KafkaSerdeProvider {

        final AtomicReference<String> capturedProfile = new AtomicReference<>();
        final AtomicReference<Boolean> keyPresent = new AtomicReference<>();

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
            keyPresent.set(endpointConfig.containsKey("jsonProfile"));
            capturedProfile.set(endpointConfig.getString("jsonProfile"));
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            throw new UnsupportedOperationException("not needed for producer tests");
        }
    }

    /** A non-JSON ("avro") provider that records whether the JSON profile key reached it. */
    static final class CapturingAvroProvider implements KafkaSerdeProvider {

        final AtomicReference<Boolean> sawProfileKey = new AtomicReference<>(false);

        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return AvroLike.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return true;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            // The avro provider does not consult jsonProfile; record whether the key was present
            // so the test can assert the profile is not surfaced through the avro path.
            sawProfileKey.set(endpointConfig.containsKey("jsonProfile"));
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            throw new UnsupportedOperationException("not needed for producer tests");
        }
    }

    /**
     * Builds the typed {@link KafkaConfig} for producer {@code "p"} from a raw producer block and an
     * optional global format, parsed at the boundary exactly as the runtime does.
     */
    private static KafkaConfig typedConfig(JsonObject producerBlock, String globalFormat) {
        JsonObject kafka = new JsonObject().put("producers", new JsonObject().put("p", producerBlock));
        if (globalFormat != null) {
            kafka.put("format", globalFormat);
        }
        return KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafka), new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    /** Resolves the serializers for {@code producerInterface}, driving the capturing provider. */
    private static void resolve(Class<?> producerInterface, JsonObject producerBlock, KafkaSerdeRegistry registry) {
        KafkaConfig typed = typedConfig(producerBlock, null);
        KafkaProducerFactory.resolveMethodSerializers(
                producerInterface, "p", typed.producerIndex().get("p"), typed, registry);
    }

    // --- Precedence ---

    @Test
    @DisplayName("producerMethodProfile_overridesProducerAndAnnotation: method 'A' > producer 'B' > annotation 'C'")
    void producerMethodProfile_overridesProducerAndAnnotation() {
        CapturingJsonProvider json = new CapturingJsonProvider();
        JsonObject producerBlock = new JsonObject()
                .put("jsonProfile", "B")
                .put("methods", new JsonObject().put("publish", new JsonObject().put("jsonProfile", "A")));

        resolve(AnnotatedProducer.class, producerBlock, new KafkaSerdeRegistry(Set.of(json)));

        assertEquals("A", json.capturedProfile.get());
    }

    @Test
    @DisplayName("producerConfigProfile_overridesAnnotation: producer 'B' > annotation 'C' when no method profile")
    void producerConfigProfile_overridesAnnotation() {
        CapturingJsonProvider json = new CapturingJsonProvider();
        JsonObject producerBlock = new JsonObject().put("jsonProfile", "B");

        resolve(AnnotatedProducer.class, producerBlock, new KafkaSerdeRegistry(Set.of(json)));

        assertEquals("B", json.capturedProfile.get());
    }

    @Test
    @DisplayName("producerAnnotationProfile_used: only @JsonProfile(\"C\") is set")
    void producerAnnotationProfile_used() {
        CapturingJsonProvider json = new CapturingJsonProvider();

        resolve(AnnotatedProducer.class, new JsonObject(), new KafkaSerdeRegistry(Set.of(json)));

        assertEquals("C", json.capturedProfile.get());
    }

    @Test
    @DisplayName("producerNoProfile_isVertique: nothing set leaves the bag key absent (vertique default)")
    void producerNoProfile_isVertique() {
        CapturingJsonProvider json = new CapturingJsonProvider();

        resolve(PlainProducer.class, new JsonObject(), new KafkaSerdeRegistry(Set.of(json)));

        assertNull(json.capturedProfile.get());
        assertEquals(Boolean.FALSE, json.keyPresent.get());
    }

    // --- Slice 2.4: kafka.jsonProfile boundary tier ---

    /**
     * Builds a typed {@link KafkaConfig} whose top-level {@code kafka.jsonProfile} is set,
     * alongside the given producer block and optional global format.
     *
     * @param producerBlock the per-producer config JSON block
     * @param kafkaBoundaryProfile the value to put into {@code kafka.jsonProfile} (may be
     *     {@code null} to leave unset)
     * @return the typed {@link KafkaConfig} parsed from the assembled section
     */
    private static KafkaConfig typedConfigWithBoundary(JsonObject producerBlock, String kafkaBoundaryProfile) {
        JsonObject kafka = new JsonObject().put("producers", new JsonObject().put("p", producerBlock));
        if (kafkaBoundaryProfile != null) {
            kafka.put("jsonProfile", kafkaBoundaryProfile);
        }
        return KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafka), new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    @Test
    @DisplayName("kafkaBoundaryApplies: kafka.jsonProfile 'b' is used when no method/producer/annotation default")
    void kafkaBoundaryApplies() {
        CapturingJsonProvider json = new CapturingJsonProvider();
        // kafka.jsonProfile="b"; no per-method, no per-producer, no annotation profile
        KafkaConfig typed = typedConfigWithBoundary(new JsonObject(), "b");

        KafkaProducerFactory.resolveMethodSerializers(
                PlainProducer.class, "p", typed.producerIndex().get("p"), typed, new KafkaSerdeRegistry(Set.of(json)));

        // RED: KafkaConfig.jsonProfile field does not exist yet AND the chain is not extended,
        // so the serde bag will NOT carry "b" — this assertion will fail red.
        assertEquals(
                "b",
                json.capturedProfile.get(),
                "kafka.jsonProfile boundary default must reach the serde bag when no higher tier is set");
    }

    @Test
    @DisplayName("producerConfigOverBoundaryDefault: producer config 'B' wins over kafka.jsonProfile 'b2'")
    void producerConfigOverBoundaryDefault() {
        CapturingJsonProvider json = new CapturingJsonProvider();
        JsonObject producerBlock = new JsonObject().put("jsonProfile", "B");
        KafkaConfig typed = typedConfigWithBoundary(producerBlock, "b2");

        KafkaProducerFactory.resolveMethodSerializers(
                PlainProducer.class, "p", typed.producerIndex().get("p"), typed, new KafkaSerdeRegistry(Set.of(json)));

        assertEquals(
                "B",
                json.capturedProfile.get(),
                "per-producer config must win over the kafka.jsonProfile boundary default");
    }

    @Test
    @DisplayName("annotationOverBoundaryDefault: @JsonProfile(\"C\") wins over kafka.jsonProfile 'b2'")
    void annotationOverBoundaryDefault() {
        CapturingJsonProvider json = new CapturingJsonProvider();
        KafkaConfig typed = typedConfigWithBoundary(new JsonObject(), "b2");

        KafkaProducerFactory.resolveMethodSerializers(
                AnnotatedProducer.class,
                "p",
                typed.producerIndex().get("p"),
                typed,
                new KafkaSerdeRegistry(Set.of(json)));

        assertEquals(
                "C",
                json.capturedProfile.get(),
                "@JsonProfile annotation must win over the kafka.jsonProfile boundary default");
    }

    @Test
    @DisplayName("producerVertiqueFloor: nothing set leaves the bag key absent (FR-JSON-057)")
    void producerVertiqueFloor() {
        CapturingJsonProvider json = new CapturingJsonProvider();

        resolve(PlainProducer.class, new JsonObject(), new KafkaSerdeRegistry(Set.of(json)));

        assertNull(json.capturedProfile.get());
        assertEquals(Boolean.FALSE, json.keyPresent.get());
    }

    // --- Non-JSON format ignores the profile (FR-JSON-036D/037) ---

    @Test
    @DisplayName("explicitSerializer_stillWins: a non-JSON format method ignores the JSON jsonProfile")
    void explicitSerializer_stillWins() {
        CapturingAvroProvider avro = new CapturingAvroProvider();
        CapturingJsonProvider json = new CapturingJsonProvider();
        // Producer-level profile set, but the method's value type auto-detects to avro, so the avro
        // provider builds the serializer — the JSON profile must not change that path.
        JsonObject producerBlock = new JsonObject().put("jsonProfile", "B");

        resolve(AvroProducer.class, producerBlock, new KafkaSerdeRegistry(Set.of(avro, json)));

        // The avro provider built the serializer; the JSON capturing provider was never invoked.
        assertNull(json.capturedProfile.get());
        // The resolved profile IS threaded into the serde bag regardless of format (FR-JSON-036D/037):
        // the producer threads effectiveProfile="B" into the bag via buildSerdeConfig; a non-JSON
        // provider simply ignores the key. Asserting key presence pins that the routing stays
        // transparent to the provider — the framework never strips the key before dispatch.
        assertEquals(
                Boolean.TRUE,
                avro.sawProfileKey.get(),
                "jsonProfile key must be present in the serde bag even for non-JSON providers"
                        + " (FR-JSON-036D) — providers that do not need it simply ignore it");
    }
}
