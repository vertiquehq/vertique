// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares a typed Kafka producer interface. The framework creates a JDK dynamic proxy
 * that serializes values and publishes to the topics specified by {@link Topic} annotations
 * on each method.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @KafkaProducer
 * public interface OrderEvents {
 *     @Topic("order.created")
 *     Future<RecordMetadata> orderCreated(OrderCreatedEvent event);
 *
 *     @Topic("order.created")
 *     Future<RecordMetadata> orderCreatedWithKey(String key, OrderCreatedEvent event);
 * }
 * }</pre>
 *
 * <p>Create proxies via {@link KafkaProducerFactory#create(Class)}.
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface KafkaProducer {

    /**
     * Producer name for config reference. Defaults to the interface simple name when empty.
     *
     * @return the producer name, or empty string for default
     */
    String name() default "";
}
