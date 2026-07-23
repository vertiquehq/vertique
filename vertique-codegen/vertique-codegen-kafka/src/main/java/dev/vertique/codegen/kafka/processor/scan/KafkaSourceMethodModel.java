// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import javax.lang.model.element.ExecutableElement;

/**
 * Immutable model of a single {@link dev.vertique.kafka.KafkaSource @KafkaSource}-annotated method
 * on a service implementation class.
 *
 * <p>Stores only the statically-knowable data extracted from the annotation at compile time.
 * Runtime concerns such as binding-name derivation, event-bus address resolution, and payload-type
 * lookup are deferred to the runtime loader ({@code KafkaConsumerScanner}) — see ADR-0072.
 *
 * @param method           the annotated implementation method element
 * @param name             value of {@code @KafkaSource#name()} — may be {@code ""} (auto-derived at runtime)
 * @param topic            Kafka topic from {@code @KafkaSource#topic()}
 * @param groupId          consumer group id from {@code @KafkaSource#groupId()}
 * @param errorStrategy    error strategy enum constant name (e.g. {@code "SKIP"})
 * @param commitStrategy   commit strategy enum constant name (e.g. {@code "AUTO"})
 * @param deadLetterTopic  dead-letter topic, or {@code ""} for the default {@code "{topic}.dlq"}
 * @param targetOperation  the implementation method's simple name, used by the runtime loader
 *                         to locate the corresponding {@code ServiceMethodMeta}
 */
public record KafkaSourceMethodModel(
        ExecutableElement method,
        String name,
        String topic,
        String groupId,
        String errorStrategy,
        String commitStrategy,
        String deadLetterTopic,
        String targetOperation) {}
