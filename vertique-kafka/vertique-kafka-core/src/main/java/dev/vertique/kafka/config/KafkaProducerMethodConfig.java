// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;

/**
 * Typed per-method producer configuration read from
 * {@code kafka.producers.{name}.methods.{method}}.
 *
 * <p>The {@code method} identity is injected from the keyed-object key during boundary parsing (the
 * parent {@link KafkaProducerConfig#methods()} list is annotated
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("method")}). The {@code serdeProperties} bag is an
 * intentionally open property bag (config rule R9) whose values are kept intact for runtime use; its
 * secret keys are masked only when this record is rendered to a string (see {@link #toString()}).
 *
 * @param method the producer method name (identity; injected from the keyed-object key)
 * @param topic the destination topic for this method, or {@code null} when not overridden
 * @param format the value format override for this method, or {@code null} to inherit the producer
 *     or top-level format
 * @param serdeProperties the open serde property bag for this method, or {@code null} when none
 * @param jsonProfile the JSON mapper profile id for value serialization on this method, or
 *     {@code null} to inherit the producer-level profile (or framework default when also absent)
 */
public record KafkaProducerMethodConfig(
        String method, String topic, String format, JsonObject serdeProperties, String jsonProfile) {

    /**
     * Compact validator: {@code method} must be non-blank (it is the injected keyed-object identity).
     *
     * @throws ConfigurationException if {@code method} is blank
     */
    public KafkaProducerMethodConfig {
        if (method == null || method.isBlank()) {
            throw new ConfigurationException("kafka.producers.<name>.methods.<method> must be non-blank");
        }
    }

    /**
     * Jackson factory: all fields pass through (nullable except {@code method} which is the
     * injected keyed-object identity).
     *
     * @param method the method name (injected identity); must be present
     * @param topic the destination topic override; passed through (nullable)
     * @param format the format override; passed through (nullable)
     * @param serdeProperties the open serde property bag; passed through (nullable)
     * @param jsonProfile the JSON mapper profile id; passed through (nullable — inherits from
     *     producer-level or framework default when absent)
     * @return the deserialized config
     */
    @JsonCreator
    static KafkaProducerMethodConfig fromJson(
            @JsonProperty("method") @Nullable String method,
            @JsonProperty("topic") @Nullable String topic,
            @JsonProperty("format") @Nullable String format,
            @JsonProperty("serdeProperties") @Nullable JsonObject serdeProperties,
            @JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        return new KafkaProducerMethodConfig(method, topic, format, serdeProperties, jsonProfile);
    }

    /**
     * Renders this method config with secret keys in {@code serdeProperties} masked, so a log line
     * or exception message never reveals a credential carried in the bag.
     *
     * @return a log-safe string rendering of this config
     */
    @Override
    public String toString() {
        return "KafkaProducerMethodConfig[method=" + method + ", topic=" + topic + ", format=" + format
                + ", serdeProperties=" + KafkaSecretKeys.scrubToString(serdeProperties)
                + ", jsonProfile=" + jsonProfile + "]";
    }
}
