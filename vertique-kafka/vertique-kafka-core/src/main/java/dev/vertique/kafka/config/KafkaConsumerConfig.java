// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;

/**
 * Typed per-consumer configuration read from {@code kafka.consumers.{name}}.
 *
 * <p>The {@code name} identity is injected from the keyed-object key during boundary parsing (the
 * parent {@link KafkaConfig#consumers()} list is annotated
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("name")}). Follows the canonical config-record
 * pattern: a compact-constructor validator (identity non-blank) plus a {@link JsonCreator} factory
 * filling defaults from {@link #defaults()}.
 *
 * <p>Components that must distinguish "absent" from a concrete default stay nullable boxed values
 * with no defaulting — {@code worker} and {@code format} are inherited from a higher level when
 * unset, so {@code null} is meaningful and preserved. The {@code properties} and {@code serdeProperties}
 * bags are intentionally open property bags (config rule R9) whose values are kept intact for runtime
 * use; their secret keys are masked only when this record is rendered to a string (see
 * {@link #toString()}).
 *
 * @param name the consumer name (identity; injected from the keyed-object key)
 * @param topic the topic this consumer subscribes to, or {@code null} when not set
 * @param groupId the consumer group id, or {@code null} when not set
 * @param enabled whether the consumer is enabled (default {@code true})
 * @param commitStrategy the offset commit strategy, or {@code null} when not set
 * @param errorStrategy the error-handling strategy, or {@code null} when not set
 * @param deadLetterTopic the dead-letter topic, or {@code null} when not set
 * @param eventBusTimeoutMs the event bus dispatch timeout in milliseconds (default {@code 30000})
 * @param maxInFlight the maximum number of in-flight records (default {@code 256})
 * @param instances the number of consumer verticle instances to deploy (default {@code 1})
 * @param worker {@code true} to run on the worker pool, or {@code null} to inherit (no default)
 * @param format the value format override, or {@code null} to inherit the top-level format (no default)
 * @param properties the open Kafka native property bag, or {@code null} when none
 * @param serdeProperties the open serde property bag, or {@code null} when none
 * @param retry the retry configuration (defaults applied; never {@code null} after construction)
 * @param jsonProfile the JSON mapper profile id for value deserialization, or {@code null} to
 *     use the framework default (the {@code vertique} profile resolved through the registry,
 *     floored by {@code json.jsonProfile})
 */
public record KafkaConsumerConfig(
        String name,
        String topic,
        String groupId,
        Boolean enabled,
        String commitStrategy,
        String errorStrategy,
        String deadLetterTopic,
        Long eventBusTimeoutMs,
        Integer maxInFlight,
        Integer instances,
        Boolean worker,
        String format,
        JsonObject properties,
        JsonObject serdeProperties,
        KafkaConsumerRetryConfig retry,
        String jsonProfile) {

    /**
     * Compact validator. Enforces:
     *
     * <ul>
     *   <li>{@code name} non-blank (the injected keyed-object identity);</li>
     *   <li>{@code eventBusTimeoutMs > 0}, {@code maxInFlight >= 1}, {@code instances >= 1} — the
     *       resolved values (defaulted by {@link #fromJson}, so non-null at construction) that feed
     *       {@link dev.vertique.kafka.ResolvedKafkaConsumerConfig}, deployment, and backpressure
     *       logic;</li>
     *   <li>the {@code retry} block bounds ({@code maxRetries >= 0}, {@code backoffMs >= 0},
     *       {@code backoffMultiplier >= 1.0}, {@code maxBackoffMs >= 0}), validated here so the
     *       message can name the full {@code kafka.consumers.<name>.retry.*} path with identity.</li>
     * </ul>
     *
     * <p>Each numeric bound is checked only when the value is non-null — it is non-null after
     * {@link #fromJson} defaulting, so this guards direct constructor calls that pass {@code null}
     * without re-introducing a NullPointerException.
     *
     * @throws ConfigurationException if {@code name} is blank or any bound is violated
     */
    public KafkaConsumerConfig {
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("kafka.consumers.<name> must be non-blank");
        }
        String path = "kafka.consumers." + name;
        if (eventBusTimeoutMs != null && eventBusTimeoutMs <= 0) {
            throw new ConfigurationException(path + ".eventBusTimeoutMs must be > 0");
        }
        if (maxInFlight != null && maxInFlight < 1) {
            throw new ConfigurationException(path + ".maxInFlight must be >= 1");
        }
        if (instances != null && instances < 1) {
            throw new ConfigurationException(path + ".instances must be >= 1");
        }
        validateRetryBounds(path, retry);
    }

    /**
     * Validates the {@code retry} block bounds against the consumer's dotted path so each message names
     * the full {@code kafka.consumers.<name>.retry.*} path with identity. Each bound is checked only
     * when the component is non-null (it is non-null after {@link KafkaConsumerRetryConfig#fromJson}
     * defaulting); a {@code null} {@code retry} block is skipped entirely.
     *
     * @param consumerPath the consumer's dotted config path (e.g. {@code kafka.consumers.orders})
     * @param retry the retry config to validate, or {@code null} to skip
     * @throws ConfigurationException if any retry bound is violated
     */
    private static void validateRetryBounds(String consumerPath, KafkaConsumerRetryConfig retry) {
        if (retry == null) {
            return;
        }
        String path = consumerPath + ".retry";
        if (retry.maxRetries() != null && retry.maxRetries() < 0) {
            throw new ConfigurationException(path + ".maxRetries must be >= 0");
        }
        if (retry.backoffMs() != null && retry.backoffMs() < 0) {
            throw new ConfigurationException(path + ".backoffMs must be >= 0");
        }
        if (retry.backoffMultiplier() != null && retry.backoffMultiplier() < 1.0) {
            throw new ConfigurationException(path + ".backoffMultiplier must be >= 1.0");
        }
        if (retry.maxBackoffMs() != null && retry.maxBackoffMs() < 0) {
            throw new ConfigurationException(path + ".maxBackoffMs must be >= 0");
        }
    }

    /**
     * Jackson factory filling defaults for omitted properties. {@code enabled=true},
     * {@code eventBusTimeoutMs=30000}, {@code maxInFlight=256}, {@code instances=1}, and
     * {@code retry=}{@link KafkaConsumerRetryConfig#defaults()} are applied when absent;
     * {@code worker} and {@code format} stay {@code null} (inherited from a higher level).
     *
     * @param name the consumer name (injected identity); must be present
     * @param topic the topic; passed through (nullable)
     * @param groupId the consumer group id; passed through (nullable)
     * @param enabled enabled flag; defaults to {@code true} when {@code null}
     * @param commitStrategy the commit strategy; passed through (nullable)
     * @param errorStrategy the error strategy; passed through (nullable)
     * @param deadLetterTopic the dead-letter topic; passed through (nullable)
     * @param eventBusTimeoutMs the event bus timeout in ms; defaults to {@code 30000} when {@code null}
     * @param maxInFlight the max in-flight records; defaults to {@code 256} when {@code null}
     * @param instances the instance count; defaults to {@code 1} when {@code null}
     * @param worker the worker flag; passed through (nullable — inherited when absent)
     * @param format the format override; passed through (nullable — inherited when absent)
     * @param properties the open Kafka property bag; passed through (nullable)
     * @param serdeProperties the open serde property bag; passed through (nullable)
     * @param retry the retry config; defaults to {@link KafkaConsumerRetryConfig#defaults()} when
     *     {@code null}
     * @param jsonProfile the JSON mapper profile id; passed through (nullable — framework
     *     default when absent)
     * @return the deserialized config with defaults applied
     */
    @JsonCreator
    static KafkaConsumerConfig fromJson(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("topic") @Nullable String topic,
            @JsonProperty("groupId") @Nullable String groupId,
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("commitStrategy") @Nullable String commitStrategy,
            @JsonProperty("errorStrategy") @Nullable String errorStrategy,
            @JsonProperty("deadLetterTopic") @Nullable String deadLetterTopic,
            @JsonProperty("eventBusTimeoutMs") @Nullable Long eventBusTimeoutMs,
            @JsonProperty("maxInFlight") @Nullable Integer maxInFlight,
            @JsonProperty("instances") @Nullable Integer instances,
            @JsonProperty("worker") @Nullable Boolean worker,
            @JsonProperty("format") @Nullable String format,
            @JsonProperty("properties") @Nullable JsonObject properties,
            @JsonProperty("serdeProperties") @Nullable JsonObject serdeProperties,
            @JsonProperty("retry") @Nullable KafkaConsumerRetryConfig retry,
            @JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        KafkaConsumerConfig d = defaults(name);
        return new KafkaConsumerConfig(
                name,
                topic,
                groupId,
                enabled != null ? enabled : d.enabled,
                commitStrategy,
                errorStrategy,
                deadLetterTopic,
                eventBusTimeoutMs != null ? eventBusTimeoutMs : d.eventBusTimeoutMs,
                maxInFlight != null ? maxInFlight : d.maxInFlight,
                instances != null ? instances : d.instances,
                worker,
                format,
                properties,
                serdeProperties,
                retry != null ? retry : d.retry,
                jsonProfile);
    }

    /**
     * Builds the default-valued consumer config for the given name: {@code enabled=true},
     * {@code eventBusTimeoutMs=30000}, {@code maxInFlight=256}, {@code instances=1},
     * {@code retry=}{@link KafkaConsumerRetryConfig#defaults()}, and every nullable field left
     * {@code null}.
     *
     * @param name the consumer name (identity); must be non-blank
     * @return the default config for {@code name}; never {@code null}
     */
    public static KafkaConsumerConfig defaults(String name) {
        return new KafkaConsumerConfig(
                name,
                null,
                null,
                true,
                null,
                null,
                null,
                30_000L,
                256,
                1,
                null,
                null,
                null,
                null,
                KafkaConsumerRetryConfig.defaults(),
                null);
    }

    /**
     * Renders this consumer config with secret keys in {@code properties} and {@code serdeProperties}
     * masked, so a log line or exception message never reveals a credential carried in either bag.
     *
     * @return a log-safe string rendering of this config
     */
    @Override
    public String toString() {
        return "KafkaConsumerConfig[name=" + name + ", topic=" + topic + ", groupId=" + groupId + ", enabled="
                + enabled + ", commitStrategy=" + commitStrategy + ", errorStrategy=" + errorStrategy
                + ", deadLetterTopic=" + deadLetterTopic + ", eventBusTimeoutMs=" + eventBusTimeoutMs + ", maxInFlight="
                + maxInFlight + ", instances=" + instances + ", worker=" + worker + ", format=" + format
                + ", properties=" + KafkaSecretKeys.scrubToString(properties) + ", serdeProperties="
                + KafkaSecretKeys.scrubToString(serdeProperties) + ", retry=" + retry
                + ", jsonProfile=" + jsonProfile + "]";
    }
}
