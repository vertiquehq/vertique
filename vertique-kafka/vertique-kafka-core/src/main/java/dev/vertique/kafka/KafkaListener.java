// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares a Kafka consumer on a routing interface (Model 3) or handler class (Model 4).
 *
 * <p><b>Model 3 — Declarative routing:</b> Place on an interface with {@link KafkaHandler}
 * methods. Each method routes matching records to a service operation via {@link DispatchTo}.
 *
 * <p><b>Model 4 — Custom handler:</b> Place on a class implementing {@link KafkaRecordHandler}.
 * The handler receives each record and performs custom dispatch logic.
 *
 * @see KafkaHandler
 * @see KafkaRecordHandler
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface KafkaListener {

    /**
     * Binding name for config reference.
     *
     * @return the binding name
     */
    String name();

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
     * Deserialization target type for Model 4 (handler class). Model 3 infers per-method.
     *
     * @return the value type class, or {@code Void.class} if inferred per-method
     */
    Class<?> valueType() default Void.class;

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
