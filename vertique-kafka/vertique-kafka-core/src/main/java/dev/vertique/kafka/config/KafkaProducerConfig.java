// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.KeyedBy;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Typed per-producer configuration read from {@code kafka.producers.{name}}.
 *
 * <p>The {@code name} identity is injected from the keyed-object key during boundary parsing (the
 * parent {@link KafkaConfig#producers()} list is annotated
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("name")}). The per-method configuration is the
 * keyed object {@code methods.{method}}, parsed via {@link KeyedBy @KeyedBy("method")} which injects
 * each method key into {@link KafkaProducerMethodConfig#method()}.
 *
 * <p>The {@code serdeProperties} bag is an intentionally open property bag (config rule R9) whose
 * values are kept intact for runtime use; its secret keys are masked only when this record is
 * rendered to a string (see {@link #toString()}).
 *
 * @param name the producer name (identity; injected from the keyed-object key)
 * @param format the value format override, or {@code null} to inherit the top-level format
 * @param serdeProperties the open serde property bag, or {@code null} when none
 * @param methods the per-method configuration keyed by method name (default empty)
 * @param jsonProfile the JSON mapper profile id for value serialization, or {@code null} to
 *     use the framework default (the {@code vertique} profile resolved through the registry,
 *     floored by {@code json.jsonProfile})
 */
public record KafkaProducerConfig(
        String name,
        String format,
        JsonObject serdeProperties,
        @KeyedBy("method") List<KafkaProducerMethodConfig> methods,
        String jsonProfile) {

    /**
     * Compact validator: {@code name} must be non-blank (it is the injected keyed-object identity);
     * {@code methods} is defensively copied for immutability.
     *
     * @throws ConfigurationException if {@code name} is blank
     */
    public KafkaProducerConfig {
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("kafka.producers.<name> must be non-blank");
        }
        methods = methods != null ? List.copyOf(methods) : List.of();
    }

    /**
     * Jackson factory: {@code methods} defaults to empty when absent; {@code format},
     * {@code serdeProperties}, and {@code jsonProfile} pass through (nullable).
     *
     * @param name the producer name (injected identity); must be present
     * @param format the format override; passed through (nullable)
     * @param serdeProperties the open serde property bag; passed through (nullable)
     * @param methods the per-method configs; defaults to empty when {@code null}
     * @param jsonProfile the JSON mapper profile id; passed through (nullable — framework
     *     default when absent)
     * @return the deserialized config with defaults applied
     */
    @JsonCreator
    static KafkaProducerConfig fromJson(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("format") @Nullable String format,
            @JsonProperty("serdeProperties") @Nullable JsonObject serdeProperties,
            @JsonProperty("methods") @Nullable List<KafkaProducerMethodConfig> methods,
            @JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        return new KafkaProducerConfig(
                name, format, serdeProperties, methods != null ? methods : List.of(), jsonProfile);
    }

    /**
     * Renders this producer config with secret keys in {@code serdeProperties} masked, so a log line
     * or exception message never reveals a credential carried in the bag.
     *
     * @return a log-safe string rendering of this config
     */
    @Override
    public String toString() {
        return "KafkaProducerConfig[name=" + name + ", format=" + format + ", serdeProperties="
                + KafkaSecretKeys.scrubToString(serdeProperties) + ", methods=" + methods
                + ", jsonProfile=" + jsonProfile + "]";
    }
}
