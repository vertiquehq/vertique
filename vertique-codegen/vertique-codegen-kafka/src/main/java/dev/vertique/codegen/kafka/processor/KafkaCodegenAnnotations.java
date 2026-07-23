// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor;

/**
 * Fully-qualified class name constants for Kafka and framework types referenced by
 * {@link KafkaConsumerProcessor} and its sub-components.
 *
 * <p>Using string constants rather than class literals avoids compile-time dependencies on the
 * runtime modules, keeping the processor jar's classpath minimal. All names must match the exact
 * binary names used in the target modules.
 */
public final class KafkaCodegenAnnotations {

    // --- Kafka consumer annotations ---

    /** FQN of {@code dev.vertique.kafka.KafkaListener}. */
    public static final String KAFKA_LISTENER = "dev.vertique.kafka.KafkaListener";

    /** FQN of {@code dev.vertique.kafka.KafkaHandler}. */
    public static final String KAFKA_HANDLER = "dev.vertique.kafka.KafkaHandler";

    /** FQN of {@code dev.vertique.kafka.KafkaSource}. */
    public static final String KAFKA_SOURCE = "dev.vertique.kafka.KafkaSource";

    /** FQN of {@code dev.vertique.kafka.DispatchTo}. */
    public static final String DISPATCH_TO = "dev.vertique.kafka.DispatchTo";

    // --- Kafka runtime interfaces ---

    /** FQN of {@code dev.vertique.kafka.KafkaRecordHandler}. */
    public static final String KAFKA_RECORD_HANDLER = "dev.vertique.kafka.KafkaRecordHandler";

    /** FQN of {@code dev.vertique.kafka.KafkaRecordContext}. */
    public static final String KAFKA_RECORD_CONTEXT = "dev.vertique.kafka.KafkaRecordContext";

    // --- Shared parameter classification types (mirrored from ServiceAnnotations) ---

    /** FQN of {@code dev.vertique.security.SecurityContext}. */
    public static final String SECURITY_CONTEXT = "dev.vertique.security.SecurityContext";

    /** FQN of {@code dev.vertique.core.eventbus.DispatchContextValue}. */
    public static final String DISPATCH_CONTEXT_VALUE = "dev.vertique.core.eventbus.DispatchContextValue";

    /** FQN of {@code dev.vertique.core.eventbus.DispatchEnvelope}. */
    public static final String DISPATCH_ENVELOPE = "dev.vertique.core.eventbus.DispatchEnvelope";

    // --- Async types ---

    /** FQN of {@code io.vertx.core.Future}. */
    public static final String FUTURE = "io.vertx.core.Future";

    private KafkaCodegenAnnotations() {}
}
