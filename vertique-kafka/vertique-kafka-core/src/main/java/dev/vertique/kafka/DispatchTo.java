// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Placed on {@link KafkaHandler @KafkaHandler} methods to declare the target service
 * operation for dispatch.
 *
 * <p>If omitted, the matched record is consumed but not dispatched (skip behavior).
 *
 * @see KafkaHandler
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface DispatchTo {

    /**
     * Target service contract interface.
     *
     * @return the service contract class
     */
    Class<?> service();

    /**
     * Target operation identifier on the service contract.
     *
     * <p>For operations annotated with {@link dev.vertique.services.ServiceOperation}, this should
     * be the durable operation id (the {@code @ServiceOperation} value) so that
     * {@link dev.vertique.services.ServiceTargetResolver} can resolve the stable target. Using the
     * durable id ensures this reference survives Java method renames.
     *
     * <p>For operations without {@code @ServiceOperation}, this falls back to the Java method name.
     *
     * @return the durable operation id, or the Java method name for legacy operations
     */
    String operation();
}
