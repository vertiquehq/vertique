// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Opt-in Jackson JSON {@link dev.vertique.kafka.serialization.KafkaSerdeProvider} for
 * vertique-kafka.
 *
 * <p>This is the default/reference value format. Include {@link dev.vertique.kafka.json.KafkaJsonModule}
 * in your Dagger component to register the {@code "json"} format with the
 * {@link dev.vertique.kafka.serialization.KafkaSerdeRegistry}.
 */
package dev.vertique.kafka.json;
