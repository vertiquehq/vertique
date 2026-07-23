// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Placed on a service <b>implementation</b> method to auto-register a Kafka consumer binding.
 *
 * <p>The framework scans service implementations from the {@code ServiceContractRegistry} for
 * this annotation and automatically creates a consumer that dispatches Kafka messages to the
 * annotated service operation via the event bus.
 *
 * <p><b>Do not place on the contract interface</b> — Kafka sourcing is an infrastructure concern.
 * The registrar validates and emits a clear error if found on a contract interface method.
 *
 * <p>Example:
 * <pre>{@code
 * public class OrderServiceImpl implements OrderService {
 *     @KafkaSource(topic = "order.created", groupId = "order-processing")
 *     @Override
 *     public Future<Void> processOrder(OrderCreatedEvent event) { ... }
 * }
 * }</pre>
 *
 * @see KafkaConsumerBinding
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface KafkaSource {

    /**
     * Binding name for config reference. Default: {@code "{serviceName}-{operationName}"}.
     *
     * @return the binding name, or empty for auto-derived
     */
    String name() default "";

    /**
     * Kafka topic to consume from.
     *
     * @return the topic name
     */
    String topic();

    /**
     * Consumer group ID.
     *
     * @return the consumer group identifier
     */
    String groupId();

    /**
     * Error handling strategy.
     *
     * @return the error strategy (default {@link ErrorStrategy#SKIP})
     */
    ErrorStrategy errorStrategy() default ErrorStrategy.SKIP;

    /**
     * Offset commit strategy.
     *
     * @return the commit strategy (default {@link CommitStrategy#AUTO})
     */
    CommitStrategy commitStrategy() default CommitStrategy.AUTO;

    /**
     * Dead-letter topic name. Empty string means default: {@code "{topic}.dlq"}.
     *
     * @return the DLQ topic name, or empty for default
     */
    String deadLetterTopic() default "";
}
