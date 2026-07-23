// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Typed, validated configuration model for the {@code kafka} section.
 *
 * <p>The external config keeps an operator-friendly keyed-object shape
 * ({@code kafka.consumers.{name}}, {@code kafka.producers.{name}.methods.{method}}); these records
 * are the typed, validated result assembled at the {@code KafkaModule} provider boundary, with each
 * object key injected into a typed identity field. The open property bags ({@code properties},
 * {@code serdeProperties}, {@code schemaRegistry}) are kept as raw {@link io.vertx.core.json.JsonObject}
 * (config rule R9) with their values intact for runtime use; secret keys are masked only when a
 * record is rendered to a string (see {@link dev.vertique.kafka.config.KafkaSecretKeys}). The
 * message-key HMAC secret is constructor-bound but never serialized out and is redacted in {@code
 * toString()}.
 *
 * <p>Module internals depend on {@link dev.vertique.kafka.config.KafkaConfig} (or its indexes),
 * never on the raw {@link io.vertx.core.json.JsonObject}.
 */
package dev.vertique.kafka.config;
