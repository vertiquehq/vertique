// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.producer;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares the Kafka topic for a {@link KafkaProducer @KafkaProducer} method.
 *
 * <p>The effective topic can be overridden at runtime via config at
 * {@code kafka.producers.{producerName}.methods.{methodName}.topic}.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface Topic {

    /**
     * Topic name to publish to.
     *
     * @return the topic name
     */
    String value();
}
