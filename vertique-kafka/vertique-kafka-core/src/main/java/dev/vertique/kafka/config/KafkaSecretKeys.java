// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import dev.vertique.core.config.ConfigSecretRenderer;
import io.vertx.core.json.JsonObject;

/**
 * Centralized secret-key predicate and log-safe rendering for Kafka open property bags.
 *
 * <p>Kafka {@code properties}, {@code serdeProperties}, {@code connectionProperties}, and the
 * {@code schemaRegistry} block are intentionally open bags (config rule R9) whose schema Vertique does
 * not own — they pass arbitrary Kafka/registry native properties straight to the client. Such a bag may
 * carry credentials ({@code sasl.password}, {@code sasl.jaas.config}, other {@code *secret*} keys, and
 * Confluent Schema Registry basic-auth credentials such as {@code basic.auth.user.info}), supplied
 * either flat ({@code {"sasl.password": "…"}}) or nested ({@code {"sasl": {"password": "…"}}}).
 * The live {@link JsonObject} values are kept intact for runtime use; only the {@code toString()}/log
 * rendering of a config record is scrubbed — recursively, at every depth — so a secret never leaks
 * through a log line, an exception message, or Jackson re-serialization of a debug dump.
 *
 * <p>The recursion and the dotted-path masking are delegated to the framework-wide
 * {@link ConfigSecretRenderer}, which descends into both nested objects <em>and</em> arrays; this
 * class keeps the Kafka-facing public surface ({@link #MASK}, {@link #isSensitiveKey(String)},
 * {@link #scrubToString(JsonObject)}). {@code KafkaProducerFactory} delegates its
 * {@code isSensitiveKey} check here so the debug-log masking and the config-record scrubbing stay in
 * lockstep, and both share the shared redactor's union secret-token rule.
 */
public final class KafkaSecretKeys {

    /** The masked replacement rendered in place of a secret value. */
    public static final String MASK = ConfigSecretRenderer.MASK;

    private KafkaSecretKeys() {}

    /**
     * Returns {@code true} when the property key corresponds to a sensitive credential value.
     *
     * <p>Delegates to {@link ConfigSecretRenderer#isSensitivePath(String)} — the framework-wide union
     * secret-token rule, which is a superset of the Kafka-specific tokens ({@code password},
     * {@code jaas.config}, {@code secret}, {@code user.info}) and adds further credential tokens such
     * as {@code keystore}/{@code truststore}/{@code passphrase}. This is the same rule applied by
     * {@code KafkaProducerFactory} when masking properties for debug logging.
     *
     * @param key the Kafka property key (must not be {@code null})
     * @return {@code true} for JAAS config, password, secret, user-info, or other credential-bearing
     *     keys
     */
    public static boolean isSensitiveKey(String key) {
        return ConfigSecretRenderer.isSensitivePath(key);
    }

    /**
     * Renders a property bag as a log-safe string, masking the values of keys matching
     * {@link #isSensitiveKey(String)} at <em>every</em> depth.
     *
     * <p>A Kafka open bag is a valid operator form whether a secret is supplied flat
     * ({@code {"sasl.password": "…"}}) or nested ({@code {"sasl": {"password": "…"}}}) — the runtime's
     * {@code KafkaConfigHelper.flattenRecursive} accepts both (and propagates array-nested values
     * too), so all must be scrubbed. The recursion, dotted-path building, and array descent are
     * delegated to {@link ConfigSecretRenderer#redactBag(JsonObject)}; the input bag is never mutated
     * and a {@code null} bag renders as {@code "null"}.
     *
     * @param bag the property bag to render, or {@code null}
     * @return a string rendering of the bag with secret values masked at every depth, or {@code "null"}
     *     when the bag is {@code null}
     */
    public static String scrubToString(JsonObject bag) {
        return ConfigSecretRenderer.redactBag(bag);
    }
}
