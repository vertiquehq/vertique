// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compile-time validators for Kafka consumers (FR-CG006-005): {@code @KafkaHandler} payload-parameter arity
 * and context-type allow-list, handler return types, router match-rule mutual exclusivity and duplicate route
 * selectors (same {@code matchHeader}+{@code matchValue} or {@code matchProperty}+{@code matchValue}), blank
 * {@code @KafkaListener#topic()}, {@code @KafkaSource} placement, and direct-handler
 * {@code KafkaRecordHandler} conformance. Routers are <em>not</em> required to have distinct payload types per
 * route — selection is by selector, not payload type.
 */
package dev.vertique.codegen.kafka.processor.validate;
