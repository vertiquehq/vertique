// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Apicurio-backed Avro value-format provider for {@code vertique-kafka}.
 *
 * <p>Adding this module to the application component contributes an {@code "avro"}
 * {@link dev.vertique.kafka.serialization.KafkaSerdeProvider} (via {@link dev.vertique.kafka.avro.AvroModule})
 * that serializes/deserializes {@code SpecificRecord} payloads through the Apicurio Registry serde in
 * Confluent-wire-compatible mode (4-byte schema id in the payload, not in headers). Absence of this
 * module only makes the {@code "avro"} format unavailable; JSON is available when
 * {@code vertique-kafka-json} ({@link dev.vertique.kafka.json.KafkaJsonModule}) is included.
 *
 * <p>The provider keeps all {@code org.apache.avro} type access on this module's classpath —
 * {@code vertique-kafka-core} never imports Avro types (see ADR-0074 and ADR-0075).
 */
package dev.vertique.kafka.avro;
