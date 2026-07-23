// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * APT-side scanning for Kafka consumer codegen: reads {@code @KafkaListener} router/handler types and
 * {@code @KafkaSource} service-impl methods into models, and classifies {@code @KafkaHandler} method
 * parameters into payload vs dispatch-context roles.
 */
package dev.vertique.codegen.kafka.processor.scan;
