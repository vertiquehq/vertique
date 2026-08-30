// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.util.Strings;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaConsumerConfig;
import dev.vertique.kafka.config.KafkaConsumerRetryConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolved configuration for a single Kafka consumer, merging annotation defaults with
 * config overrides from {@code kafka.consumers.{name}.*}.
 *
 * <p>Config always wins: every field is first seeded from annotation defaults and then
 * overridden by the corresponding JSON config key if present.
 *
 * <p>This is the internal runtime value-object produced by {@link #resolve}. It is distinct from
 * the external parsed config record {@code KafkaConsumerConfig}, which is the
 * Jackson-deserialized form of the {@code kafka.consumers.{name}} config block.
 *
 * @param topic effective topic (config override or annotation default)
 * @param groupId effective consumer group ID
 * @param enabled whether this consumer is enabled
 * @param commitStrategy effective commit strategy
 * @param errorStrategy effective error strategy
 * @param retryConfig retry configuration (non-null when errorStrategy is {@link ErrorStrategy#RETRY})
 * @param deadLetterTopic effective DLQ topic (resolved default: {@code {topic}.dlq})
 * @param eventBusTimeoutMs event bus send timeout in milliseconds
 * @param maxInFlight max concurrent dispatches before pausing the consumer
 * @param deploymentOptions Vert.x deployment options (instances, worker threading model)
 * @param kafkaProperties native Kafka consumer properties merged from global and per-consumer config
 * @param endpointFormat the endpoint-level format override from
 *     {@code kafka.consumers.{name}.format}; {@code null} if absent
 * @param globalFormat the global format from {@code kafka.format}; {@code null} if absent
 * @param workerConfigured the explicit tri-state {@code worker} flag: {@code true} if the
 *     config explicitly sets {@code worker=true}, {@code false} if explicitly {@code worker=false},
 *     and {@code null} if absent
 * @param serdeConfig the merged serde configuration view for this consumer, containing
 *     {@code serdeProperties} and {@code schemaRegistry} sub-objects, plus {@code jsonProfile}
 *     when a JSON mapper profile is selected
 * @param jsonProfile the resolved JSON mapper profile id for value deserialization
 *     (config override, then binding/listener default), or {@code null} for the framework default
 *     (the {@code vertx} profile backed by {@code DatabindCodec.mapper()})
 */
record ResolvedKafkaConsumerConfig(
        String topic,
        String groupId,
        boolean enabled,
        CommitStrategy commitStrategy,
        ErrorStrategy errorStrategy,
        RetryConfig retryConfig,
        String deadLetterTopic,
        long eventBusTimeoutMs,
        int maxInFlight,
        DeploymentOptions deploymentOptions,
        Map<String, String> kafkaProperties,
        @Nullable String endpointFormat,
        @Nullable String globalFormat,
        @Nullable Boolean workerConfigured,
        JsonObject serdeConfig,
        @Nullable String jsonProfile) {

    // --- Factory ---

    /**
     * Resolves the configuration for a named consumer by merging annotation defaults with the typed
     * per-consumer config ({@code kafka.consumers.{name}}) looked up off the typed {@link KafkaConfig}.
     *
     * <p>Property merging (lowest to highest): {@link KafkaConfig#connectionProperties()} (the loose
     * top-level connection scalars) provides layer 1, {@link KafkaConfig#properties()} the global base
     * layer 2, and {@link KafkaConsumerConfig#properties()} per-consumer overrides layer 3 (latter wins),
     * then SASL JAAS auto-construction runs on the merged map.
     *
     * <p>The four serde-related fields ({@link #endpointFormat}, {@link #globalFormat},
     * {@link #workerConfigured}, {@link #serdeConfig}) are captured here for later use by
     * {@link KafkaConsumerValidation#validateAndBuild}. The explicit {@code worker=true} setting still
     * sets the {@link ThreadingModel#WORKER} threading model on the deployment options immediately so
     * that existing behavior is preserved; the validation step may additionally force WORKER when a
     * blocking deserializer is selected.
     *
     * @param name the binding name used for diagnostics
     * @param defaultTopic annotation-supplied default topic
     * @param defaultGroupId annotation-supplied default group ID
     * @param defaultEnabled annotation-supplied enabled flag
     * @param defaultCommitStrategy annotation-supplied commit strategy
     * @param defaultErrorStrategy annotation-supplied error strategy
     * @param defaultDeadLetterTopic annotation-supplied DLQ topic (empty means auto-derive)
     * @param defaultEventBusTimeoutMs annotation-supplied event bus timeout in milliseconds
     * @param defaultJsonProfile the binding/listener-supplied JSON mapper profile id default
     *     (e.g. {@code KafkaConsumerBinding.jsonProfile().value()} or the listener type's
     *     {@code @JsonProfile} value); applied when the per-consumer config does not
     *     set {@code jsonProfile}; {@code null}/blank means the framework default ({@code vertx})
     * @param kafkaConfig the typed {@code kafka} config (supplies the global format, schema registry,
     *     connection/global property bags)
     * @param consumerConfig the typed per-consumer config for {@code name}, or {@code null} when the
     *     consumer is not configured (every field then falls back to its annotation default)
     * @return the resolved consumer configuration
     */
    static ResolvedKafkaConsumerConfig resolve(
            String name,
            String defaultTopic,
            String defaultGroupId,
            boolean defaultEnabled,
            CommitStrategy defaultCommitStrategy,
            ErrorStrategy defaultErrorStrategy,
            String defaultDeadLetterTopic,
            long defaultEventBusTimeoutMs,
            @Nullable String defaultJsonProfile,
            KafkaConfig kafkaConfig,
            @Nullable KafkaConsumerConfig consumerConfig) {

        String topic = firstNonNull(consumerConfig != null ? consumerConfig.topic() : null, defaultTopic);
        String groupId = firstNonNull(consumerConfig != null ? consumerConfig.groupId() : null, defaultGroupId);
        boolean enabled =
                consumerConfig != null && consumerConfig.enabled() != null ? consumerConfig.enabled() : defaultEnabled;
        long eventBusTimeoutMs = consumerConfig != null && consumerConfig.eventBusTimeoutMs() != null
                ? consumerConfig.eventBusTimeoutMs()
                : defaultEventBusTimeoutMs;
        int maxInFlight =
                consumerConfig != null && consumerConfig.maxInFlight() != null ? consumerConfig.maxInFlight() : 256;

        CommitStrategy commitStrategy = resolveEnum(
                consumerConfig != null ? consumerConfig.commitStrategy() : null,
                CommitStrategy.class,
                defaultCommitStrategy);
        ErrorStrategy errorStrategy = resolveEnum(
                consumerConfig != null ? consumerConfig.errorStrategy() : null,
                ErrorStrategy.class,
                defaultErrorStrategy);

        // Resolve DLQ topic: config wins, then annotation default, then auto-derive from topic
        String rawDlq =
                firstNonNull(consumerConfig != null ? consumerConfig.deadLetterTopic() : null, defaultDeadLetterTopic);
        String deadLetterTopic = (rawDlq == null || rawDlq.isBlank()) ? topic + ".dlq" : rawDlq;

        // Retry config from the typed per-consumer retry block (only material for ErrorStrategy.RETRY)
        RetryConfig retryConfig = resolveRetryConfig(consumerConfig, errorStrategy);

        // Deployment options: explicit worker=true still forces WORKER immediately (existing behavior).
        int instances = consumerConfig != null && consumerConfig.instances() != null ? consumerConfig.instances() : 1;
        Boolean workerConfigured = consumerConfig != null ? consumerConfig.worker() : null;
        boolean worker = Boolean.TRUE.equals(workerConfigured);
        DeploymentOptions deploymentOptions = new DeploymentOptions().setInstances(instances);
        if (worker) {
            deploymentOptions.setThreadingModel(ThreadingModel.WORKER);
        }

        // Kafka properties: connection + global base merged with per-consumer overrides
        Map<String, String> kafkaProperties = mergeKafkaProperties(kafkaConfig, consumerConfig);

        // Endpoint-level and global format (used by validateAndBuild for serde selection)
        String endpointFormat = consumerConfig != null ? consumerConfig.format() : null;
        String globalFormat = kafkaConfig.format();

        // Value JSON profile precedence: config override → binding/listener default →
        // kafka.jsonProfile boundary default → vertx (FR-JSON-052).
        // The json.jsonProfile global tier and the vertx floor are applied in kafka-json
        // (JsonSerdeProvider.resolveMapper), which sees the bag id via the serde config.
        String jsonProfile = Strings.firstNonBlank(
                consumerConfig != null ? consumerConfig.jsonProfile() : null,
                defaultJsonProfile,
                kafkaConfig.jsonProfile());

        // Serde config view: per-consumer serdeProperties + global schemaRegistry block, plus the
        // resolved JSON profile id (added to the bag only when non-blank, so the vertx path is unchanged)
        JsonObject schemaRegistry =
                kafkaConfig.schemaRegistry() != null ? kafkaConfig.schemaRegistry() : new JsonObject();
        JsonObject consumerSerde = consumerConfig != null && consumerConfig.serdeProperties() != null
                ? consumerConfig.serdeProperties()
                : new JsonObject();
        JsonObject serdeConfig = KafkaConfigHelper.serdeConfig(schemaRegistry, consumerSerde, jsonProfile);

        return new ResolvedKafkaConsumerConfig(
                topic,
                groupId,
                enabled,
                commitStrategy,
                errorStrategy,
                retryConfig,
                deadLetterTopic,
                eventBusTimeoutMs,
                maxInFlight,
                deploymentOptions,
                kafkaProperties,
                endpointFormat,
                globalFormat,
                workerConfigured,
                serdeConfig,
                jsonProfile);
    }

    // --- Private helpers ---

    /**
     * Returns {@code value} when non-null, otherwise {@code fallback}. Used to apply the
     * annotation-default fallback when a typed config field is absent (null).
     *
     * @param value the typed config value (may be {@code null})
     * @param fallback the annotation default
     * @return {@code value} when non-null, else {@code fallback}
     */
    private static String firstNonNull(@Nullable String value, String fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Resolves an enum constant from a string name, returning the default if the name is blank or null.
     * A non-blank name that does not identify an enum constant fails with a
     * {@link ConfigurationException}.
     *
     * @param <E> the enum type
     * @param name the string name to look up
     * @param type the enum class
     * @param defaultValue the fallback value when name is blank or null
     * @return the resolved enum constant
     * @throws ConfigurationException if name is non-blank but does not identify an enum constant
     */
    private static <E extends Enum<E>> E resolveEnum(String name, Class<E> type, E defaultValue) {
        if (name == null || name.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    "Invalid " + type.getSimpleName() + " value '" + name + "'", e);
        }
    }

    /**
     * Resolves the runtime {@link RetryConfig} from the typed per-consumer
     * {@link KafkaConsumerConfig#retry()} block, or returns the default-valued config when the error
     * strategy is {@link ErrorStrategy#RETRY} and no explicit config is present. Returns {@code null}
     * when retry is not active.
     *
     * <p>The typed {@link KafkaConsumerRetryConfig} always carries non-null defaulted values (its
     * {@code @JsonCreator} applies {@link KafkaConsumerRetryConfig#defaults()}), so this method simply
     * maps the typed values into the validating runtime {@link RetryConfig}. The
     * {@code exhaustedStrategy} string is parsed via {@link #resolveEnum} exactly as before.
     *
     * @param consumerConfig the typed per-consumer config, or {@code null} when not configured
     * @param errorStrategy the resolved error strategy
     * @return the retry config, or {@code null} if retry is not active
     */
    private static RetryConfig resolveRetryConfig(
            @Nullable KafkaConsumerConfig consumerConfig, ErrorStrategy errorStrategy) {
        if (errorStrategy != ErrorStrategy.RETRY) {
            return null;
        }
        KafkaConsumerRetryConfig retryCfg = consumerConfig != null && consumerConfig.retry() != null
                ? consumerConfig.retry()
                : KafkaConsumerRetryConfig.defaults();
        ErrorStrategy exhausted =
                resolveEnum(retryCfg.exhaustedStrategy(), ErrorStrategy.class, RetryConfig.DEFAULT.exhaustedStrategy());
        return new RetryConfig(
                retryCfg.maxRetries(),
                retryCfg.backoffMs(),
                retryCfg.backoffMultiplier(),
                exhausted,
                retryCfg.maxBackoffMs());
    }

    /**
     * Merges Kafka properties in priority order (lowest to highest):
     * <ol>
     *   <li>{@link KafkaConfig#connectionProperties()} — the loose top-level connection keys
     *       ({@code bootstrap.servers}, {@code security.protocol}, {@code sasl.*}) in flat-dotted or
     *       nested form</li>
     *   <li>{@link KafkaConfig#properties()} — the global {@code kafka.properties} bag</li>
     *   <li>{@link KafkaConsumerConfig#properties()} — the per-consumer {@code properties} bag</li>
     * </ol>
     *
     * <p>If after merging, {@code security.protocol} contains "SASL" and {@code sasl.jaas.config} is
     * absent but {@code sasl.username} and {@code sasl.password} are present, the JAAS config string is
     * constructed automatically.
     *
     * <p>Layer 1 flattens the connection bag through {@link KafkaConfigHelper#flattenToMap} so that both
     * the flat-dotted ({@code "bootstrap.servers"}) and the nested ({@code {"bootstrap":{"servers":...}}})
     * forms resolve to the dotted Kafka property name — preserving the original {@code resolveString}
     * behavior for the connection scalars.
     *
     * @param kafkaConfig the typed {@code kafka} config (supplies the connection + global property bags)
     * @param consumerConfig the typed per-consumer config, or {@code null} when not configured
     * @return an unmodifiable map of merged Kafka native properties
     */
    private static Map<String, String> mergeKafkaProperties(
            KafkaConfig kafkaConfig, @Nullable KafkaConsumerConfig consumerConfig) {
        Map<String, String> merged = new HashMap<>();

        // Layer 1: loose top-level Kafka connection keys (flat-dotted or nested, both flattened to dotted)
        KafkaConfigHelper.flattenToMap(kafkaConfig.connectionProperties()).forEach(merged::put);

        // Layer 2: global kafka.properties (wins over connection keys)
        JsonObject globalProps = kafkaConfig.properties() != null ? kafkaConfig.properties() : new JsonObject();
        KafkaConfigHelper.flattenToMap(globalProps).forEach(merged::put);

        // Layer 3: per-consumer properties (wins over global)
        JsonObject perConsumerProps = consumerConfig != null && consumerConfig.properties() != null
                ? consumerConfig.properties()
                : new JsonObject();
        KafkaConfigHelper.flattenToMap(perConsumerProps).forEach(merged::put);

        KafkaConfigHelper.autoConstructSaslJaasConfig(merged);

        return Map.copyOf(merged);
    }
}
