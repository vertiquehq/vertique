// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaConfig} boundary parsing — the typed model assembled from the
 * keyed-object external {@code kafka} section ({@code kafka.consumers.{name}},
 * {@code kafka.producers.{name}}, producer methods under {@code methods.{method}}).
 *
 * <p>Verifies that consumer/producer keys are injected as the {@code name} identity and producer
 * method keys as the {@code method} identity, that the open property bags ({@code properties},
 * {@code serdeProperties}, {@code schemaRegistry}) round-trip with their values intact (proving the
 * {@code ConfigParser} Vert.x-JSON support applies here), that absent fields fall back to their
 * documented defaults, and that secret keys in the open bags are masked in {@code toString()} while
 * staying intact in the live {@link JsonObject}.
 */
@DisplayName("KafkaConfig")
class KafkaConfigTest {

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for test use, matching the production boundary parser.
     *
     * @return a lenient config parser
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Builds a {@link KafkaConfig} from the {@code kafka} section of a root config object, exercising
     * the boundary parser exactly as the Dagger provider does.
     *
     * @param kafka the {@code kafka} section JSON
     * @return the parsed typed config
     */
    private static KafkaConfig parse(JsonObject kafka) {
        return KafkaConfig.fromConfig(new JsonObject().put("kafka", kafka), configParser());
    }

    // --- Tests ---

    @Test
    @DisplayName("injects the consumer key into KafkaConsumerConfig.name")
    void consumersKeyInjected() {
        JsonObject kafka = new JsonObject()
                .put(
                        "consumers",
                        new JsonObject()
                                .put("orders", new JsonObject().put("topic", "orders-topic"))
                                .put("billing", new JsonObject().put("topic", "billing-topic")));

        KafkaConfig config = parse(kafka);

        assertEquals(2, config.consumers().size());
        KafkaConsumerConfig orders = config.consumerIndex().get("orders");
        assertEquals("orders", orders.name());
        assertEquals("orders-topic", orders.topic());
        KafkaConsumerConfig billing = config.consumerIndex().get("billing");
        assertEquals("billing", billing.name());
        assertEquals("billing-topic", billing.topic());
    }

    @Test
    @DisplayName("injects the producer key into KafkaProducerConfig.name")
    void producersKeyInjected() {
        JsonObject kafka = new JsonObject()
                .put(
                        "producers",
                        new JsonObject()
                                .put("events", new JsonObject().put("format", "avro"))
                                .put("notifications", new JsonObject()));

        KafkaConfig config = parse(kafka);

        assertEquals(2, config.producers().size());
        KafkaProducerConfig events = config.producerIndex().get("events");
        assertEquals("events", events.name());
        assertEquals("avro", events.format());
        assertEquals(
                "notifications", config.producerIndex().get("notifications").name());
    }

    @Test
    @DisplayName("injects the producer method key into KafkaProducerMethodConfig.method")
    void producerMethodsKeyInjected() {
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
                                                                        new JsonObject().put("topic", "order-events"))
                                                                .put(
                                                                        "publishShipment",
                                                                        new JsonObject()
                                                                                .put("topic", "shipment-events")))));

        KafkaConfig config = parse(kafka);

        KafkaProducerConfig events = config.producerIndex().get("events");
        assertEquals(2, events.methods().size());
        KafkaProducerMethodConfig publishOrder = events.methods().stream()
                .filter(m -> m.method().equals("publishOrder"))
                .findFirst()
                .orElseThrow();
        assertEquals("publishOrder", publishOrder.method());
        assertEquals("order-events", publishOrder.topic());
        KafkaProducerMethodConfig publishShipment = events.methods().stream()
                .filter(m -> m.method().equals("publishShipment"))
                .findFirst()
                .orElseThrow();
        assertEquals("shipment-events", publishShipment.topic());
    }

    @Test
    @DisplayName(
            "open bags (top-level properties/schemaRegistry, consumer properties/serdeProperties) round-trip intact")
    void bagsRoundTrip() {
        JsonObject kafka = new JsonObject()
                .put("properties", new JsonObject().put("bootstrap.servers", "broker:9092"))
                .put("schemaRegistry", new JsonObject().put("url", "http://registry:8081"))
                .put(
                        "consumers",
                        new JsonObject()
                                .put(
                                        "orders",
                                        new JsonObject()
                                                .put("topic", "orders-topic")
                                                .put("properties", new JsonObject().put("max.poll.records", "100"))
                                                .put(
                                                        "serdeProperties",
                                                        new JsonObject().put("specific.avro.reader", "true"))));

        KafkaConfig config = parse(kafka);

        // Top-level bags retain their values (not bound to empty objects).
        assertEquals("broker:9092", config.properties().getString("bootstrap.servers"));
        assertEquals("http://registry:8081", config.schemaRegistry().getString("url"));

        // Consumer-level bags retain their values.
        KafkaConsumerConfig orders = config.consumerIndex().get("orders");
        assertFalse(orders.properties().isEmpty());
        assertEquals("100", orders.properties().getString("max.poll.records"));
        assertFalse(orders.serdeProperties().isEmpty());
        assertEquals("true", orders.serdeProperties().getString("specific.avro.reader"));
    }

    @Test
    @DisplayName("absent fields fall back to defaults (enabled, timeouts, retry)")
    void defaults() {
        JsonObject kafka = new JsonObject().put("consumers", new JsonObject().put("orders", new JsonObject()));

        KafkaConfig config = parse(kafka);
        KafkaConsumerConfig orders = config.consumerIndex().get("orders");

        assertTrue(orders.enabled());
        assertEquals(30_000L, orders.eventBusTimeoutMs());
        assertEquals(256, orders.maxInFlight());
        assertEquals(1, orders.instances());
        // worker/format have no default — they inherit from a higher level when absent.
        assertNull(orders.worker());
        assertNull(orders.format());

        KafkaConsumerRetryConfig retry = orders.retry();
        assertEquals(3, retry.maxRetries());
        assertEquals(1000L, retry.backoffMs());
        assertEquals(2.0, retry.backoffMultiplier());
        assertEquals(60_000L, retry.maxBackoffMs());
        assertEquals("DEAD_LETTER", retry.exhaustedStrategy());
    }

    @Test
    @DisplayName("loose top-level connection keys (flat and nested) are captured into connectionProperties")
    void connectionPropertiesCapturedFromTopLevel() {
        JsonObject kafka = new JsonObject()
                .put("bootstrap.servers", "broker:9092")
                .put("security", new JsonObject().put("protocol", "SASL_SSL"))
                .put("properties", new JsonObject().put("max.poll.records", "100"))
                .put("consumers", new JsonObject().put("orders", new JsonObject().put("topic", "orders-topic")));

        KafkaConfig config = parse(kafka);

        // The loose connection scalars (flat-dotted and nested) land in connectionProperties verbatim.
        assertEquals("broker:9092", config.connectionProperties().getString("bootstrap.servers"));
        assertEquals(
                "SASL_SSL",
                config.connectionProperties().getJsonObject("security").getString("protocol"));
        // Structured keys are NOT mixed into connectionProperties.
        assertNull(config.connectionProperties().getValue("properties"));
        assertNull(config.connectionProperties().getValue("consumers"));
        // The structured property bag stays where it belongs.
        assertEquals("100", config.properties().getString("max.poll.records"));
    }

    @Test
    @DisplayName("kafka.producer.properties is captured into producerProperties")
    void producerPropertiesCapturedFromProducerBlock() {
        JsonObject kafka = new JsonObject()
                .put(
                        "producer",
                        new JsonObject()
                                .put(
                                        "properties",
                                        new JsonObject().put("acks", "all").put("linger.ms", "20")));

        KafkaConfig config = parse(kafka);

        assertEquals("all", config.producerProperties().getString("acks"));
        assertEquals("20", config.producerProperties().getString("linger.ms"));
        // The singular `producer` key must not be mistaken for the keyed `producers` collection.
        assertTrue(config.producers().isEmpty());
    }

    @Test
    @DisplayName("absent connection/producer bags default to empty, never null")
    void clientBagsDefaultEmpty() {
        KafkaConfig config = parse(new JsonObject());

        assertTrue(config.connectionProperties().isEmpty());
        assertTrue(config.producerProperties().isEmpty());
    }

    @Test
    @DisplayName("kafka.messageKeyHash.hmacSecret binds to the typed message-key hash config")
    void messageKeyHashSecretBinds() {
        JsonObject kafka = new JsonObject().put("messageKeyHash", new JsonObject().put("hmacSecret", "super-secret"));

        KafkaConfig config = parse(kafka);

        assertEquals("super-secret", config.messageKeyHash().hmacSecret());
        assertFalse(
                config.connectionProperties().containsKey("messageKeyHash"),
                "messageKeyHash is structured config and must not leak into Kafka client properties");
    }

    @Test
    @DisplayName("retired audit nesting neither binds a secret nor becomes a client property")
    void retiredAuditMessageKeyHashIsIgnored() {
        JsonObject kafka = new JsonObject()
                .put(
                        "audit",
                        new JsonObject().put("messageKeyHash", new JsonObject().put("hmacSecret", "retired-secret")));

        KafkaConfig config = parse(kafka);

        assertNull(config.messageKeyHash().hmacSecret(), "the retired audit nesting must not configure the secret");
        assertFalse(
                config.connectionProperties().containsKey("audit"),
                "the retired audit key must not leak into Kafka client properties");
    }

    @Test
    @DisplayName("jsonProfile is a STRUCTURED_ROOT_KEY and is excluded from connectionProperties"
            + " while native client props (bootstrap.servers, sasl.mechanism) remain in connectionProperties")
    void jsonProfileExcludedFromConnectionProperties() {
        // Given: a kafka section that carries BOTH the structured jsonProfile key AND loose
        // native Kafka client props (bootstrap.servers, sasl.mechanism).
        JsonObject kafka = new JsonObject()
                .put("jsonProfile", "my-profile") // structured root key — must NOT leak into connectionProperties
                .put("bootstrap.servers", "broker:9092") // loose native client prop — must land in connectionProperties
                .put("sasl.mechanism", "PLAIN"); // loose native client prop — must land in connectionProperties

        KafkaConfig config = parse(kafka);

        // jsonProfile is a structured key — it parses into the typed record component.
        assertEquals("my-profile", config.jsonProfile(), "jsonProfile must be parsed into the typed component");

        // jsonProfile must NOT appear in the loose connectionProperties bag.
        assertNull(
                config.connectionProperties().getValue("jsonProfile"),
                "jsonProfile must not leak into connectionProperties"
                        + " (it is in STRUCTURED_ROOT_KEYS and is not a native Kafka client property)");

        // The loose native client props are still captured correctly.
        assertEquals(
                "broker:9092",
                config.connectionProperties().getString("bootstrap.servers"),
                "bootstrap.servers must be captured in connectionProperties");
        assertEquals(
                "PLAIN",
                config.connectionProperties().getString("sasl.mechanism"),
                "sasl.mechanism must be captured in connectionProperties");
    }

    @Test
    @DisplayName("kafkaConfig_parsesJsonProfileKey: the new kafka.jsonProfile key binds into the typed component")
    void kafkaConfig_parsesJsonProfileKey() {
        // Given: a kafka JSON section that carries only the new key {"jsonProfile":"events-v2"}.
        JsonObject kafka = new JsonObject().put("jsonProfile", "events-v2");

        // When: parsed through the Jackson @JsonCreator factory.
        KafkaConfig config = KafkaConfig.fromJson(null, null, null, null, null, null, "events-v2");

        // Then: the renamed @JsonProperty("jsonProfile") binds; the parser path agrees.
        assertEquals("events-v2", config.jsonProfile(), "fromJson must bind the jsonProfile component");
        assertEquals(
                "events-v2",
                parse(kafka).jsonProfile(),
                "the new kafka.jsonProfile key must parse into the typed component");
    }

    @Test
    @DisplayName("kafkaConfig_staleValueJsonProfileKey_isNotProfileAndLeaksToConnectionProperties:"
            + " the retired valueJsonProfile key is no longer a profile and falls through to connectionProperties")
    void kafkaConfig_staleValueJsonProfileKey_isNotProfileAndLeaksToConnectionProperties() {
        // Given: a root config whose kafka section uses the RETIRED key {"valueJsonProfile":"events-v2"}.
        // Must go through fromConfig — the leak-to-connectionProperties path runs only via
        // fromConfig -> extractConnectionProperties (fromJson seeds connectionProperties empty).
        JsonObject root = new JsonObject().put("kafka", new JsonObject().put("valueJsonProfile", "events-v2"));

        KafkaConfig config = KafkaConfig.fromConfig(root, configParser());

        // Then (D-B retired-key policy): the stale key selects no profile ...
        assertNull(
                config.jsonProfile(),
                "the retired valueJsonProfile key must NOT bind to the jsonProfile component (no special handling)");
        // ... and falls through to connectionProperties like any unknown root key.
        assertEquals(
                "events-v2",
                config.connectionProperties().getString("valueJsonProfile"),
                "the retired valueJsonProfile key must leak to connectionProperties as an arbitrary connection prop");
    }

    @Test
    @DisplayName("secret keys in a consumer properties bag are masked in toString but intact in the live JsonObject")
    void secretKeysScrubbedInToString() {
        JsonObject kafka = new JsonObject()
                .put(
                        "consumers",
                        new JsonObject()
                                .put(
                                        "orders",
                                        new JsonObject()
                                                .put(
                                                        "properties",
                                                        new JsonObject()
                                                                .put("sasl.password", "topsecret")
                                                                .put(
                                                                        "sasl.jaas.config",
                                                                        "org.apache.kafka.common.security.plain.PlainLoginModule required;")
                                                                .put("bootstrap.servers", "broker:9092"))));

        KafkaConfig config = parse(kafka);
        KafkaConsumerConfig orders = config.consumerIndex().get("orders");

        // The live JsonObject still carries the real secret values for runtime use.
        assertEquals("topsecret", orders.properties().getString("sasl.password"));
        assertTrue(orders.properties().getString("sasl.jaas.config").contains("PlainLoginModule"));

        // toString() masks the secret values but keeps the non-secret one.
        String rendered = orders.toString();
        assertFalse(rendered.contains("topsecret"), "password value must not appear in toString");
        assertFalse(rendered.contains("PlainLoginModule"), "jaas.config value must not appear in toString");
        assertTrue(rendered.contains(KafkaSecretKeys.MASK), "secret values must be masked");
        assertTrue(rendered.contains("broker:9092"), "non-secret values stay visible");
    }

    @Test
    @DisplayName("nested sasl.password (in connectionProperties and properties) is masked in toString but intact live")
    void nestedSaslPasswordScrubbedInToString() {
        // The operator-nested form: sasl as a nested object carrying password + jaas.config. The runtime's
        // KafkaConfigHelper.flattenRecursive accepts this shape, so the secret round-trips to the client and
        // MUST be masked by toString() at every depth.
        JsonObject kafka = new JsonObject()
                .put("security", new JsonObject().put("protocol", "SASL_SSL")) // nested non-secret connection scalar
                .put(
                        "sasl",
                        new JsonObject()
                                .put("password", "SEKRIT")
                                .put("jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required;")
                                .put("mechanism", "PLAIN")) // nested non-secret stays visible
                .put("properties", new JsonObject().put("sasl", new JsonObject().put("password", "SEKRIT2")));

        KafkaConfig config = parse(kafka);

        // The live JsonObjects keep the real secrets for runtime use (flatten yields the real value).
        assertEquals(
                "SEKRIT", config.connectionProperties().getJsonObject("sasl").getString("password"));
        assertEquals("SEKRIT2", config.properties().getJsonObject("sasl").getString("password"));

        String rendered = config.toString();
        assertFalse(rendered.contains("SEKRIT"), "nested sasl.password (connectionProperties) must be masked");
        assertFalse(rendered.contains("SEKRIT2"), "nested sasl.password (properties) must be masked");
        assertFalse(rendered.contains("PlainLoginModule"), "nested sasl.jaas.config must be masked");
        assertTrue(rendered.contains(KafkaSecretKeys.MASK), "nested secret values must be masked");
        // Non-secret nested scalars stay visible.
        assertTrue(rendered.contains("SASL_SSL"), "non-secret nested connection scalar stays visible");
        assertTrue(rendered.contains("PLAIN"), "non-secret nested sasl.mechanism stays visible");
    }

    @Test
    @DisplayName("schemaRegistry credential keys (*.password, nested secret) are masked in toString")
    void schemaRegistryCredentialScrubbedInToString() {
        JsonObject kafka = new JsonObject()
                .put(
                        "schemaRegistry",
                        new JsonObject()
                                .put("url", "http://registry:8081")
                                .put("basic.auth.credentials.secret", "BASICSEKRIT")
                                .put("schema.registry.ssl", new JsonObject().put("keystore.password", "STOREPASS")));

        KafkaConfig config = parse(kafka);

        // The live JsonObject keeps the real credentials for runtime use.
        assertEquals("BASICSEKRIT", config.schemaRegistry().getString("basic.auth.credentials.secret"));
        assertEquals(
                "STOREPASS",
                config.schemaRegistry().getJsonObject("schema.registry.ssl").getString("keystore.password"));

        String rendered = config.toString();
        assertFalse(rendered.contains("BASICSEKRIT"), "registry *.secret key must be masked");
        assertFalse(rendered.contains("STOREPASS"), "registry nested *.password key must be masked");
        assertTrue(rendered.contains(KafkaSecretKeys.MASK), "registry secret values must be masked");
        // The non-secret registry URL stays visible for debuggability.
        assertTrue(rendered.contains("http://registry:8081"), "non-secret registry url stays visible");
    }

    @Test
    @DisplayName(
            "schemaRegistry basic.auth.user.info (Confluent registry basic-auth) is masked in toString but intact live")
    void basicAuthUserInfoScrubbedInToString() {
        // Confluent Schema Registry basic-auth credential: value is "user:password".
        // Both the flat key (basic.auth.user.info) and the fully-qualified key
        // (schema.registry.basic.auth.user.info) must be masked.
        JsonObject kafka = new JsonObject()
                .put(
                        "schemaRegistry",
                        new JsonObject()
                                .put("url", "http://registry:8081")
                                .put("basic.auth.user.info", "user:SEKRIT")
                                .put("schema.registry.basic.auth.user.info", "user:SEKRIT2"));

        KafkaConfig config = parse(kafka);

        // The live schemaRegistry JsonObject still carries the real credential for runtime use.
        assertEquals("user:SEKRIT", config.schemaRegistry().getString("basic.auth.user.info"));
        assertEquals("user:SEKRIT2", config.schemaRegistry().getString("schema.registry.basic.auth.user.info"));

        // toString() must NOT expose either credential.
        String rendered = config.toString();
        assertFalse(rendered.contains("SEKRIT"), "basic.auth.user.info value must not appear in toString");
        assertTrue(rendered.contains(KafkaSecretKeys.MASK), "user.info values must be masked");
        // The non-secret registry URL must stay visible for debuggability.
        assertTrue(rendered.contains("http://registry:8081"), "non-secret registry url stays visible");
    }

    @Test
    @DisplayName("a secret nested inside a JsonArray element object is masked in toString but intact live")
    void arrayNestedSecretScrubbed() {
        // An open bag may carry an array of objects; a secret nested under an array index leaked
        // verbatim before the shared array-aware redactor (gap W2). The runtime flatten also
        // propagates array-nested values, so it MUST be masked at every depth.
        JsonObject kafka = new JsonObject()
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "items",
                                        new JsonArray()
                                                .add(new JsonObject()
                                                        .put("password", "SEKRIT")
                                                        .put("name", "n1"))));

        KafkaConfig config = parse(kafka);

        // The live JsonObject keeps the real secret for runtime use.
        assertEquals(
                "SEKRIT",
                config.properties().getJsonArray("items").getJsonObject(0).getString("password"));

        String rendered = config.toString();
        assertFalse(rendered.contains("SEKRIT"), "array-nested password must be masked in toString");
        assertTrue(rendered.contains(KafkaSecretKeys.MASK), "array-nested secret values must be masked");
        assertTrue(rendered.contains("n1"), "non-secret array-nested value stays visible");
    }

    // --- Bound validation: KafkaConsumerConfig ---

    @Nested
    @DisplayName("KafkaConsumerConfig bounds")
    class ConsumerBounds {

        /**
         * Parses a single consumer with one field overridden, exercising the boundary parser exactly
         * as the Dagger provider does.
         *
         * @param field the consumer config field name to set
         * @param value the value to set
         * @return the parsed typed config
         */
        private KafkaConfig parseConsumerWith(String field, Object value) {
            JsonObject kafka = new JsonObject()
                    .put("consumers", new JsonObject().put("orders", new JsonObject().put(field, value)));
            return parse(kafka);
        }

        @Test
        @DisplayName("eventBusTimeoutMs <= 0 is rejected with a path/identity-bearing message")
        void eventBusTimeoutMs_nonPositive_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseConsumerWith("eventBusTimeoutMs", 0L));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.eventBusTimeoutMs"),
                    "message must name the dotted path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("maxInFlight < 1 is rejected with a path/identity-bearing message")
        void maxInFlight_belowOne_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseConsumerWith("maxInFlight", 0));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.maxInFlight"),
                    "message must name the dotted path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("instances < 1 is rejected with a path/identity-bearing message")
        void instances_belowOne_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseConsumerWith("instances", 0));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.instances"),
                    "message must name the dotted path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("boundary-valid values (eventBusTimeoutMs=1, maxInFlight=1, instances=1) are accepted")
        void boundaryValidValuesAccepted() {
            assertDoesNotThrow(() -> {
                KafkaConfig config = parse(new JsonObject()
                        .put(
                                "consumers",
                                new JsonObject()
                                        .put(
                                                "orders",
                                                new JsonObject()
                                                        .put("eventBusTimeoutMs", 1L)
                                                        .put("maxInFlight", 1)
                                                        .put("instances", 1))));
                KafkaConsumerConfig orders = config.consumerIndex().get("orders");
                assertEquals(1L, orders.eventBusTimeoutMs());
                assertEquals(1, orders.maxInFlight());
                assertEquals(1, orders.instances());
            });
        }
    }

    // --- Bound validation: KafkaConsumerRetryConfig ---

    @Nested
    @DisplayName("KafkaConsumerRetryConfig bounds")
    class RetryBounds {

        /**
         * Parses a single consumer whose retry block has one field overridden.
         *
         * @param field the retry config field name to set
         * @param value the value to set
         * @return the parsed typed config
         */
        private KafkaConfig parseRetryWith(String field, Object value) {
            JsonObject kafka = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put("orders", new JsonObject().put("retry", new JsonObject().put(field, value))));
            return parse(kafka);
        }

        @Test
        @DisplayName("maxRetries < 0 is rejected")
        void maxRetries_negative_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseRetryWith("maxRetries", -1));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.retry.maxRetries"),
                    "message must name the dotted retry path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("backoffMs < 0 is rejected")
        void backoffMs_negative_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseRetryWith("backoffMs", -1L));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.retry.backoffMs"),
                    "message must name the dotted retry path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("backoffMultiplier < 1.0 is rejected")
        void backoffMultiplier_belowOne_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseRetryWith("backoffMultiplier", 0.5));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.retry.backoffMultiplier"),
                    "message must name the dotted retry path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("maxBackoffMs < 0 is rejected")
        void maxBackoffMs_negative_rejected() {
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parseRetryWith("maxBackoffMs", -1L));
            assertTrue(
                    ex.getMessage().contains("kafka.consumers.orders.retry.maxBackoffMs"),
                    "message must name the dotted retry path with identity: " + ex.getMessage());
        }

        @Test
        @DisplayName("boundary-valid retry values (maxRetries=0, backoffMs=0, backoffMultiplier=1.0, maxBackoffMs=0)"
                + " are accepted")
        void boundaryValidRetryValuesAccepted() {
            assertDoesNotThrow(() -> {
                KafkaConfig config = parse(new JsonObject()
                        .put(
                                "consumers",
                                new JsonObject()
                                        .put(
                                                "orders",
                                                new JsonObject()
                                                        .put(
                                                                "retry",
                                                                new JsonObject()
                                                                        .put("maxRetries", 0)
                                                                        .put("backoffMs", 0L)
                                                                        .put("backoffMultiplier", 1.0)
                                                                        .put("maxBackoffMs", 0L)))));
                KafkaConsumerRetryConfig retry =
                        config.consumerIndex().get("orders").retry();
                assertEquals(0, retry.maxRetries());
                assertEquals(0L, retry.backoffMs());
                assertEquals(1.0, retry.backoffMultiplier());
                assertEquals(0L, retry.maxBackoffMs());
            });
        }
    }
}
