// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor that generates {@code {Consumer}_BindingMeta} companions with precomputed Kafka
 * consumer binding metadata from {@code @KafkaListener} types and {@code @KafkaSource} service-impl methods.
 *
 * <p>The central entry point is {@link dev.vertique.codegen.kafka.processor.KafkaConsumerProcessor}.
 * Supporting classes are organized into sub-packages:
 * <ul>
 *   <li>{@code scan} — APT-side scanners for the {@code @KafkaListener} (router/handler) and
 *       {@code @KafkaSource} (service-impl) consumption models, plus parameter classification</li>
 *   <li>{@code validate} — compile-time consumer-shape validators (FR-CG006-005)</li>
 *   <li>{@code emit} — source emitter for {@code {Consumer}_BindingMeta}</li>
 * </ul>
 */
package dev.vertique.codegen.kafka.processor;
