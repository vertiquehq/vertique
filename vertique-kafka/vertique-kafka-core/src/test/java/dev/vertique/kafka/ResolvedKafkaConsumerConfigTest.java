// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.kafka.config.KafkaConfig;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResolvedKafkaConsumerConfig#resolve} static factory: annotation defaults,
 * config-level overrides, Kafka property merging, SASL auto-construction, DLQ topic derivation,
 * retry config resolution, deployment options, and the enabled toggle.
 *
 * <p>Each test builds the raw {@code kafka} section as a {@link JsonObject} (the external operator
 * shape) and resolves through {@link #resolve}, which parses it into the typed {@link KafkaConfig}
 * at the boundary exactly as the runtime provider does — so the tests exercise the typed-config path
 * end to end while pinning the external shape unchanged.
 */
class ResolvedKafkaConsumerConfigTest {

    /**
     * Resolves a consumer config from the raw {@code kafka} section, parsing it into the typed
     * {@link KafkaConfig} (and looking up the per-consumer config by name) exactly as the runtime
     * boundary does.
     */
    private static ResolvedKafkaConsumerConfig resolve(
            String name,
            String topic,
            String groupId,
            boolean enabled,
            CommitStrategy commitStrategy,
            ErrorStrategy errorStrategy,
            String deadLetterTopic,
            long eventBusTimeoutMs,
            JsonObject kafkaConfigJson) {
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafkaConfigJson), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return ResolvedKafkaConsumerConfig.resolve(
                name,
                topic,
                groupId,
                enabled,
                commitStrategy,
                errorStrategy,
                deadLetterTopic,
                eventBusTimeoutMs,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
    }

    /** Resolves a config using all annotation defaults and an empty kafka config. */
    private static ResolvedKafkaConsumerConfig resolveDefault(
            String name, String topic, String groupId, JsonObject kafkaConfig) {
        return resolve(name, topic, groupId, true, CommitStrategy.AUTO, ErrorStrategy.SKIP, "", 30_000L, kafkaConfig);
    }

    // --- Annotation defaults ---

    @Nested
    @DisplayName("Annotation defaults when no config present")
    class AnnotationDefaults {

        @Test
        @DisplayName("topic from annotation default is used when no config override")
        void useAnnotationTopicWhenNoConfig() {
            ResolvedKafkaConsumerConfig config =
                    resolveDefault("my-consumer", "default.topic", "default-group", new JsonObject());
            assertEquals("default.topic", config.topic());
        }

        @Test
        @DisplayName("groupId from annotation default is used when no config override")
        void useAnnotationGroupIdWhenNoConfig() {
            ResolvedKafkaConsumerConfig config =
                    resolveDefault("my-consumer", "topic", "default-group", new JsonObject());
            assertEquals("default-group", config.groupId());
        }

        @Test
        @DisplayName("commitStrategy from annotation default is used when no config override")
        void useAnnotationCommitStrategyWhenNoConfig() {
            ResolvedKafkaConsumerConfig config = resolveDefault("my-consumer", "topic", "group", new JsonObject());
            assertEquals(CommitStrategy.AUTO, config.commitStrategy());
        }

        @Test
        @DisplayName("errorStrategy from annotation default is used when no config override")
        void useAnnotationErrorStrategyWhenNoConfig() {
            ResolvedKafkaConsumerConfig config = resolveDefault("my-consumer", "topic", "group", new JsonObject());
            assertEquals(ErrorStrategy.SKIP, config.errorStrategy());
        }

        @Test
        @DisplayName("enabled defaults to annotation value true when no config override")
        void enabledDefaultsToAnnotationValue() {
            ResolvedKafkaConsumerConfig config = resolveDefault("my-consumer", "topic", "group", new JsonObject());
            assertTrue(config.enabled());
        }

        @Test
        @DisplayName("eventBusTimeoutMs defaults to annotation value when no config override")
        void eventBusTimeoutMsDefaultsToAnnotation() {
            ResolvedKafkaConsumerConfig config = resolveDefault("my-consumer", "topic", "group", new JsonObject());
            assertEquals(30_000L, config.eventBusTimeoutMs());
        }
    }

    // --- Config overrides ---

    @Nested
    @DisplayName("Config overrides annotation defaults")
    class ConfigOverrides {

        @Test
        @DisplayName("config topic overrides annotation default topic")
        void configTopicOverridesAnnotation() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("test-consumer", new JsonObject().put("topic", "overridden-topic")));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "default.topic", "group", kafkaConfig);
            assertEquals("overridden-topic", config.topic());
        }

        @Test
        @DisplayName("config groupId overrides annotation default groupId")
        void configGroupIdOverridesAnnotation() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("test-consumer", new JsonObject().put("groupId", "overridden-group")));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "topic", "default-group", kafkaConfig);
            assertEquals("overridden-group", config.groupId());
        }

        @Test
        @DisplayName("config commitStrategy as string overrides annotation default")
        void configCommitStrategyStringOverridesAnnotation() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("test-consumer", new JsonObject().put("commitStrategy", "MANUAL")));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "topic", "group", kafkaConfig);
            assertEquals(CommitStrategy.MANUAL, config.commitStrategy());
        }

        @Test
        @DisplayName("config errorStrategy as string overrides annotation default")
        void configErrorStrategyStringOverridesAnnotation() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put("test-consumer", new JsonObject().put("errorStrategy", "DEAD_LETTER")));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "topic", "group", kafkaConfig);
            assertEquals(ErrorStrategy.DEAD_LETTER, config.errorStrategy());
        }

        @Test
        @DisplayName("invalid config errorStrategy throws ConfigurationException")
        void invalidConfigErrorStrategyThrowsConfigurationException() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put("test-consumer", new JsonObject().put("errorStrategy", "NOT_A_STRATEGY")));

            assertThrows(
                    ConfigurationException.class,
                    () -> resolveDefault("test-consumer", "topic", "group", kafkaConfig));
        }

        @Test
        @DisplayName("config enabled=false overrides annotation default")
        void configEnabledFalseOverridesAnnotation() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("test-consumer", new JsonObject().put("enabled", false)));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "topic", "group", kafkaConfig);
            assertFalse(config.enabled());
        }

        @Test
        @DisplayName("config eventBusTimeoutMs overrides annotation default")
        void configEventBusTimeoutMsOverridesAnnotation() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("test-consumer", new JsonObject().put("eventBusTimeoutMs", 5000L)));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "topic", "group", kafkaConfig);
            assertEquals(5000L, config.eventBusTimeoutMs());
        }
    }

    // --- Kafka properties merging ---

    @Nested
    @DisplayName("Kafka properties merging")
    class KafkaPropertiesMerging {

        @Test
        @DisplayName("bootstrap.servers from top-level kafka config is included in properties")
        void bootstrapServersFromTopLevelIncluded() {
            JsonObject kafkaConfig = new JsonObject().put("bootstrap.servers", "localhost:9092");

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);
            assertEquals("localhost:9092", config.kafkaProperties().get("bootstrap.servers"));
        }

        @Test
        @DisplayName("per-consumer properties override global properties")
        void perConsumerPropertiesOverrideGlobal() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("properties", new JsonObject().put("max.poll.records", "100"))
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put(
                                            "test-consumer",
                                            new JsonObject()
                                                    .put(
                                                            "properties",
                                                            new JsonObject().put("max.poll.records", "50"))));

            ResolvedKafkaConsumerConfig config = resolveDefault("test-consumer", "topic", "group", kafkaConfig);
            assertEquals("50", config.kafkaProperties().get("max.poll.records"));
        }

        @Test
        @DisplayName("global properties appear in merged result when no per-consumer override exists")
        void globalPropertiesAppearedWhenNoPerConsumerOverride() {
            JsonObject kafkaConfig =
                    new JsonObject().put("properties", new JsonObject().put("fetch.min.bytes", "1024"));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);
            assertEquals("1024", config.kafkaProperties().get("fetch.min.bytes"));
        }
    }

    // --- SASL auto-construction ---

    @Nested
    @DisplayName("SASL JAAS config auto-construction")
    class SaslAutoConstruction {

        @Test
        @DisplayName("SASL JAAS config is auto-constructed when security.protocol is SASL_SSL and credentials present")
        void jaasConfigAutoConstructedForSaslSsl() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("security.protocol", "SASL_SSL")
                    .put("sasl.mechanism", "PLAIN")
                    .put(
                            "properties",
                            new JsonObject().put("sasl.username", "my-user").put("sasl.password", "my-pass"));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);

            String jaasConfig = config.kafkaProperties().get("sasl.jaas.config");
            assertNotNull(jaasConfig, "sasl.jaas.config should be auto-constructed");
            assertTrue(jaasConfig.contains("PlainLoginModule"), "Should use PlainLoginModule for PLAIN mechanism");
            assertTrue(jaasConfig.contains("my-user"), "Should contain the username");
            assertTrue(jaasConfig.contains("my-pass"), "Should contain the password");
        }

        @Test
        @DisplayName("sasl.username and sasl.password are removed from properties after JAAS construction")
        void credentialKeysRemovedAfterJaasConstruction() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("security.protocol", "SASL_SSL")
                    .put(
                            "properties",
                            new JsonObject().put("sasl.username", "user").put("sasl.password", "pass"));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);

            assertFalse(config.kafkaProperties().containsKey("sasl.username"));
            assertFalse(config.kafkaProperties().containsKey("sasl.password"));
        }
    }

    // --- buildJaasConfig and escapeJaasValue helpers ---

    @Nested
    @DisplayName("buildJaasConfig() helper")
    class BuildJaasConfig {

        @Test
        @DisplayName("PLAIN mechanism uses PlainLoginModule")
        void plainMechanismUsesPlainLoginModule() {
            String jaas = KafkaConfigHelper.buildJaasConfig("PLAIN", "user", "pass");
            assertTrue(jaas.contains("PlainLoginModule"));
        }

        @Test
        @DisplayName("SCRAM-SHA-256 mechanism uses ScramLoginModule")
        void scramMechanismUsesScramLoginModule() {
            String jaas = KafkaConfigHelper.buildJaasConfig("SCRAM-SHA-256", "user", "pass");
            assertTrue(jaas.contains("ScramLoginModule"));
        }

        @Test
        @DisplayName("escapeJaasValue escapes backslashes in credentials")
        void escapeBackslashesInCredentials() {
            String escaped = KafkaConfigHelper.escapeJaasValue("pass\\word");
            assertEquals("pass\\\\word", escaped);
        }

        @Test
        @DisplayName("escapeJaasValue escapes double quotes in credentials")
        void escapeDoubleQuotesInCredentials() {
            String escaped = KafkaConfigHelper.escapeJaasValue("pass\"word");
            assertEquals("pass\\\"word", escaped);
        }
    }

    // --- Dead letter topic derivation ---

    @Nested
    @DisplayName("Dead letter topic derivation")
    class DeadLetterTopic {

        @Test
        @DisplayName("DLQ defaults to {topic}.dlq when annotation DLQ is empty")
        void dlqDefaultsToTopicDotDlq() {
            ResolvedKafkaConsumerConfig config = resolve(
                    "consumer",
                    "my.topic",
                    "group",
                    true,
                    CommitStrategy.AUTO,
                    ErrorStrategy.SKIP,
                    "",
                    30_000L,
                    new JsonObject());

            assertEquals("my.topic.dlq", config.deadLetterTopic());
        }

        @Test
        @DisplayName("config deadLetterTopic wins over auto-derived default")
        void configDlqOverridesAutoDerive() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("consumer", new JsonObject().put("deadLetterTopic", "custom.dlq")));

            ResolvedKafkaConsumerConfig config = resolve(
                    "consumer",
                    "my.topic",
                    "group",
                    true,
                    CommitStrategy.AUTO,
                    ErrorStrategy.SKIP,
                    "",
                    30_000L,
                    kafkaConfig);

            assertEquals("custom.dlq", config.deadLetterTopic());
        }
    }

    // --- Retry config resolution ---

    @Nested
    @DisplayName("RetryConfig resolution")
    class RetryConfigResolution {

        @Test
        @DisplayName("retryConfig is null when errorStrategy is SKIP")
        void retryConfigNullWhenStrategyIsSkip() {
            ResolvedKafkaConsumerConfig config = resolve(
                    "consumer",
                    "topic",
                    "group",
                    true,
                    CommitStrategy.AUTO,
                    ErrorStrategy.SKIP,
                    "",
                    30_000L,
                    new JsonObject());

            assertNull(config.retryConfig());
        }

        @Test
        @DisplayName("retryConfig is non-null with DEFAULT values when errorStrategy is RETRY and no retry block")
        void retryConfigDefaultsWhenStrategyIsRetry() {
            ResolvedKafkaConsumerConfig config = resolve(
                    "consumer",
                    "topic",
                    "group",
                    true,
                    CommitStrategy.AUTO,
                    ErrorStrategy.RETRY,
                    "",
                    30_000L,
                    new JsonObject());

            assertNotNull(config.retryConfig());
            assertEquals(RetryConfig.DEFAULT.maxRetries(), config.retryConfig().maxRetries());
            assertEquals(RetryConfig.DEFAULT.backoffMs(), config.retryConfig().backoffMs());
        }

        @Test
        @DisplayName("retryConfig is resolved from config retry block when errorStrategy is RETRY")
        void retryConfigFromConfigBlock() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put(
                                            "consumer",
                                            new JsonObject()
                                                    .put(
                                                            "retry",
                                                            new JsonObject()
                                                                    .put("maxRetries", 5)
                                                                    .put("backoffMs", 500L)
                                                                    .put("backoffMultiplier", 3.0)
                                                                    .put("maxBackoffMs", 30_000L))));

            ResolvedKafkaConsumerConfig config = resolve(
                    "consumer",
                    "topic",
                    "group",
                    true,
                    CommitStrategy.AUTO,
                    ErrorStrategy.RETRY,
                    "",
                    30_000L,
                    kafkaConfig);

            assertNotNull(config.retryConfig());
            assertEquals(5, config.retryConfig().maxRetries());
            assertEquals(500L, config.retryConfig().backoffMs());
            assertEquals(3.0, config.retryConfig().backoffMultiplier());
            assertEquals(30_000L, config.retryConfig().maxBackoffMs());
        }

        @Test
        @DisplayName("invalid retry.exhaustedStrategy throws ConfigurationException")
        void invalidRetryExhaustedStrategyThrowsConfigurationException() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put(
                                            "consumer",
                                            new JsonObject()
                                                    .put(
                                                            "retry",
                                                            new JsonObject().put("exhaustedStrategy", "NOT_A_STRATEGY"))));

            assertThrows(
                    ConfigurationException.class,
                    () -> resolve(
                            "consumer",
                            "topic",
                            "group",
                            true,
                            CommitStrategy.AUTO,
                            ErrorStrategy.RETRY,
                            "",
                            30_000L,
                            kafkaConfig));
        }
    }

    // --- Hierarchical config compatibility ---

    @Nested
    @DisplayName("Hierarchical config compatibility (nested JSON from .properties expansion)")
    class HierarchicalConfigCompatibility {

        @Test
        @DisplayName("nested bootstrap.servers resolved via mergeKafkaProperties")
        void nestedBootstrapServersResolved() {
            JsonObject kafkaConfig = new JsonObject().put("bootstrap", new JsonObject().put("servers", "broker:9092"));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);
            assertEquals("broker:9092", config.kafkaProperties().get("bootstrap.servers"));
        }

        @Test
        @DisplayName("nested sasl.mechanism and sasl.jaas.config resolved via mergeKafkaProperties")
        void nestedSaslKeysResolved() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("security", new JsonObject().put("protocol", "SASL_SSL"))
                    .put(
                            "sasl",
                            new JsonObject()
                                    .put("mechanism", "PLAIN")
                                    .put(
                                            "jaas",
                                            new JsonObject().put("config", "required username=\"u\" password=\"p\";")));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);
            assertEquals("SASL_SSL", config.kafkaProperties().get("security.protocol"));
            assertEquals("PLAIN", config.kafkaProperties().get("sasl.mechanism"));
            assertEquals(
                    "required username=\"u\" password=\"p\";",
                    config.kafkaProperties().get("sasl.jaas.config"));
        }

        @Test
        @DisplayName("nested per-consumer properties resolved via mergeKafkaProperties")
        void nestedPerConsumerPropertiesResolved() {
            JsonObject perConsumerProps =
                    new JsonObject().put("max", new JsonObject().put("poll", new JsonObject().put("records", "200")));
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("my-consumer", new JsonObject().put("properties", perConsumerProps)));

            ResolvedKafkaConsumerConfig config = resolveDefault("my-consumer", "topic", "group", kafkaConfig);
            assertEquals("200", config.kafkaProperties().get("max.poll.records"));
        }
    }

    // --- Deployment options ---

    @Nested
    @DisplayName("Deployment options")
    class DeploymentOptionsResolution {

        @Test
        @DisplayName("default instances is 1 when no config present")
        void defaultInstancesIsOne() {
            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", new JsonObject());
            assertEquals(1, config.deploymentOptions().getInstances());
        }

        @Test
        @DisplayName("config instances overrides default")
        void configInstancesOverridesDefault() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("consumer", new JsonObject().put("instances", 3)));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);
            assertEquals(3, config.deploymentOptions().getInstances());
        }

        @Test
        @DisplayName("config worker=true sets WORKER threading model")
        void configWorkerTrueSetsWorkerModel() {
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("consumer", new JsonObject().put("worker", true)));

            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", kafkaConfig);
            assertEquals(ThreadingModel.WORKER, config.deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("default worker=false does not set WORKER threading model")
        void defaultWorkerFalseDoesNotSetWorkerModel() {
            ResolvedKafkaConsumerConfig config = resolveDefault("consumer", "topic", "group", new JsonObject());
            assertNotEquals(ThreadingModel.WORKER, config.deploymentOptions().getThreadingModel());
        }
    }
}
