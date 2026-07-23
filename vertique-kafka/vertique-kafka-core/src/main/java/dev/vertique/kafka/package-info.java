// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Kafka consumer bridge to event bus services.
 *
 * <p>Provides four progressive consumption models:
 * <ol>
 *   <li>{@link dev.vertique.kafka.KafkaSource @KafkaSource} on service implementation methods</li>
 *   <li>{@link dev.vertique.kafka.KafkaConsumerBinding} programmatic builder</li>
 *   <li>{@link dev.vertique.kafka.KafkaListener @KafkaListener} with
 *       {@link dev.vertique.kafka.KafkaHandler @KafkaHandler} declarative routing</li>
 *   <li>{@link dev.vertique.kafka.KafkaListener @KafkaListener} with
 *       {@link dev.vertique.kafka.KafkaRecordHandler} custom logic</li>
 * </ol>
 */
package dev.vertique.kafka;
