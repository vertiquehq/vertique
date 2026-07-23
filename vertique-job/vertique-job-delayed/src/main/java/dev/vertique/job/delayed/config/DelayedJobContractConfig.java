// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;

/**
 * Typed per-contract configuration read from {@code delayedJob.contracts.{name}}.
 *
 * <p>This is the typed, validated per-contract record assembled at the {@code DelayedJobModule}
 * provider boundary (via {@link DelayedJobsConfig#fromConfig}); the client factory, proxy, and
 * target resolver depend on it (or on the {@link DelayedJobsConfig#contractIndex()} index), never on
 * the raw {@link io.vertx.core.json.JsonObject}. The object key is injected into {@link #name()} via
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("name")} on the parent
 * {@link DelayedJobsConfig#contracts()} list.
 *
 * <p>Each override field stays a <strong>nullable boxed value with no defaulting</strong>: these
 * values <em>override</em> the corresponding {@link dev.vertique.job.delayed.DelayedJobContract}
 * annotation defaults only when the operator actually sets them, so {@code null} (absent) must be
 * preserved and is meaningful — a field left unset falls back to the annotation value at the consumer,
 * <em>not</em> to a config default. Were these defaulted to the framework constants, a config that
 * overrides only one field would silently clobber a non-default annotation value on the others. A
 * per-enqueue {@link dev.vertique.job.delayed.DelayedJobOptions} in turn overrides these.
 *
 * @param name the contract name (identity; injected from the keyed-object key); never blank
 * @param maxAttempts the maximum-attempts override, or {@code null} to inherit the annotation value
 * @param queue the queue-name override, or {@code null} to inherit the annotation value
 * @param priority the priority override, or {@code null} to inherit the annotation value
 */
public record DelayedJobContractConfig(String name, Integer maxAttempts, String queue, Integer priority) {

    /**
     * Compact validator: {@code name} must be non-blank (it is the injected keyed-object identity).
     * The three override fields are left exactly as supplied (including {@code null} for absent).
     *
     * @param name the contract name (identity)
     * @param maxAttempts the maximum-attempts override (nullable)
     * @param queue the queue-name override (nullable)
     * @param priority the priority override (nullable)
     * @throws ConfigurationException if {@code name} is {@code null} or blank
     */
    public DelayedJobContractConfig {
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("delayedJob.contracts.<name> must be non-blank");
        }
    }

    /**
     * Jackson factory passing each override through unchanged so absent fields stay {@code null}. The
     * {@code name} is key-injected by {@link dev.vertique.core.config.ConfigParser} before
     * deserialization.
     *
     * @param name the contract name injected from the object key (validated in the compact ctor)
     * @param maxAttempts the maximum-attempts override; passed through (nullable)
     * @param queue the queue-name override; passed through (nullable)
     * @param priority the priority override; passed through (nullable)
     * @return the deserialized config with absent overrides preserved as {@code null}
     */
    @JsonCreator
    static DelayedJobContractConfig fromJson(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("maxAttempts") @Nullable Integer maxAttempts,
            @JsonProperty("queue") @Nullable String queue,
            @JsonProperty("priority") @Nullable Integer priority) {
        return new DelayedJobContractConfig(name, maxAttempts, queue, priority);
    }

    /**
     * Renders the operator-specified overrides as a per-contract {@link JsonObject} containing
     * <strong>only the keys that were actually set</strong> ({@code null} overrides are omitted), so
     * a consumer reading {@code getInteger("maxAttempts", annotationDefault)} still falls back to the
     * annotation value for any unset field — preserving the historical absent-vs-set semantics. This
     * is the typed-config replacement for the raw {@code delayedJob.contracts.{name}} subtree the
     * client proxies previously received.
     *
     * @return a {@link JsonObject} of the set overrides only (empty when no field is overridden)
     */
    public JsonObject toContractConfigJson() {
        JsonObject json = new JsonObject();
        if (maxAttempts != null) {
            json.put("maxAttempts", maxAttempts);
        }
        if (queue != null) {
            json.put("queue", queue);
        }
        if (priority != null) {
            json.put("priority", priority);
        }
        return json;
    }
}
