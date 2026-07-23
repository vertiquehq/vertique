// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaConsumerConfig;
import dev.vertique.kafka.config.KafkaProducerConfig;
import dev.vertique.kafka.config.KafkaProducerMethodConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the JSON-profile selection surface.
 *
 * <p>Covers the config-key/builder surfaces: {@link KafkaConsumerConfig},
 * {@link KafkaConsumerBinding.Builder}, {@link KafkaProducerConfig}, and
 * {@link KafkaProducerMethodConfig}. The config records and the programmatic binding builder use the
 * harmonized {@code jsonProfile} key/accessor. The per-binding annotation selector is
 * {@code @JsonProfile} on the listener/producer type (covered by the codegen and reflective scanner
 * tests).
 */
@DisplayName("jsonProfile selection surface")
class KafkaValueJsonProfileSelectionTest {

    // --- Helpers ---

    private static KafkaConfig parseKafka(JsonObject kafka) {
        return KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafka), new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    // --- KafkaConsumerConfig ---

    @Nested
    @DisplayName("KafkaConsumerConfig.jsonProfile")
    class ConsumerConfigProfileTests {

        @Test
        @DisplayName("kafkaConsumerConfig_parsesJsonProfileKey: present value round-trips")
        void kafkaConsumerConfig_parsesJsonProfileKey() {
            JsonObject kafka = new JsonObject()
                    .put("consumers", new JsonObject().put("payments", new JsonObject().put("jsonProfile", "c-v2")));

            KafkaConfig config = parseKafka(kafka);
            KafkaConsumerConfig cfg = config.consumerIndex().get("payments");

            assertEquals("c-v2", cfg.jsonProfile());
        }

        @Test
        @DisplayName("kafkaConsumerConfig_parsesJsonProfileKey: absent value is null")
        void kafkaConsumerConfig_parsesJsonProfileKey_absent() {
            JsonObject kafka = new JsonObject().put("consumers", new JsonObject().put("payments", new JsonObject()));

            KafkaConfig config = parseKafka(kafka);
            KafkaConsumerConfig cfg = config.consumerIndex().get("payments");

            assertNull(cfg.jsonProfile());
        }
    }

    // --- KafkaConsumerBinding.Builder ---

    @Nested
    @DisplayName("KafkaConsumerBinding.Builder.jsonProfile")
    class ConsumerBindingProfileTests {

        record Foo(String name) {}

        @Test
        @DisplayName("binding_builderSetsJsonProfile: builder sets profile; accessor returns it")
        void binding_builderSetsJsonProfile() {
            JsonProfileId id = JsonProfileId.of("payments-events-v2");
            KafkaConsumerBinding<Foo> binding =
                    KafkaConsumerBinding.builder("c", Foo.class).jsonProfile(id).build();

            assertEquals(id, binding.jsonProfile());
        }

        @Test
        @DisplayName("binding_builderSetsJsonProfile: default jsonProfile is null")
        void binding_builderSetsJsonProfile_default() {
            KafkaConsumerBinding<Foo> binding =
                    KafkaConsumerBinding.builder("c", Foo.class).build();

            assertNull(binding.jsonProfile());
        }
    }

    // --- KafkaProducerConfig ---

    @Nested
    @DisplayName("KafkaProducerConfig.jsonProfile")
    class ProducerConfigProfileTests {

        @Test
        @DisplayName("kafkaProducerConfig_parsesJsonProfileKey: present value round-trips")
        void kafkaProducerConfig_parsesJsonProfileKey() {
            JsonObject kafka = new JsonObject()
                    .put("producers", new JsonObject().put("events", new JsonObject().put("jsonProfile", "p-v2")));

            KafkaConfig config = parseKafka(kafka);
            KafkaProducerConfig cfg = config.producerIndex().get("events");

            assertEquals("p-v2", cfg.jsonProfile());
        }

        @Test
        @DisplayName("kafkaProducerConfig_parsesJsonProfileKey: absent value is null")
        void kafkaProducerConfig_parsesJsonProfileKey_absent() {
            JsonObject kafka = new JsonObject().put("producers", new JsonObject().put("events", new JsonObject()));

            KafkaConfig config = parseKafka(kafka);
            KafkaProducerConfig cfg = config.producerIndex().get("events");

            assertNull(cfg.jsonProfile());
        }
    }

    // --- KafkaProducerMethodConfig ---

    @Nested
    @DisplayName("KafkaProducerMethodConfig.jsonProfile")
    class ProducerMethodConfigProfileTests {

        @Test
        @DisplayName("kafkaProducerMethodConfig_parsesJsonProfileKey: present value round-trips")
        void kafkaProducerMethodConfig_parsesJsonProfileKey() {
            JsonObject kafka = new JsonObject()
                    .put(
                            "producers",
                            new JsonObject()
                                    .put(
                                            "events",
                                            new JsonObject()
                                                    .put(
                                                            "methods",
                                                            new JsonObject()
                                                                    .put(
                                                                            "publishOrder",
                                                                            new JsonObject()
                                                                                    .put("jsonProfile", "m-v2")))));

            KafkaConfig config = parseKafka(kafka);
            KafkaProducerConfig producer = config.producerIndex().get("events");
            KafkaProducerMethodConfig method = producer.methods().stream()
                    .filter(m -> m.method().equals("publishOrder"))
                    .findFirst()
                    .orElseThrow();

            assertEquals("m-v2", method.jsonProfile());
        }

        @Test
        @DisplayName("kafkaProducerMethodConfig_parsesJsonProfileKey: absent value is null")
        void kafkaProducerMethodConfig_parsesJsonProfileKey_absent() {
            JsonObject kafka = new JsonObject()
                    .put(
                            "producers",
                            new JsonObject()
                                    .put(
                                            "events",
                                            new JsonObject()
                                                    .put(
                                                            "methods",
                                                            new JsonObject().put("publishOrder", new JsonObject()))));

            KafkaConfig config = parseKafka(kafka);
            KafkaProducerConfig producer = config.producerIndex().get("events");
            KafkaProducerMethodConfig method = producer.methods().stream()
                    .filter(m -> m.method().equals("publishOrder"))
                    .findFirst()
                    .orElseThrow();

            assertNull(method.jsonProfile());
        }
    }
}
