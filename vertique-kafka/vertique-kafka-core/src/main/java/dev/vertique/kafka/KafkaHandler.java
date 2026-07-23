// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Placed on methods within a {@link KafkaListener @KafkaListener} interface for routing
 * (Model 3). Each method must specify exactly one of: {@link #matchHeader()},
 * {@link #matchProperty()}, or {@link #defaultHandler()}.
 *
 * <p>Example:
 * <pre>{@code
 * @KafkaListener(name = "order-events", topic = "order.events", groupId = "order-processing")
 * public interface OrderEventRouter {
 *     @KafkaHandler(matchHeader = "event-type", matchValue = "order.created")
 *     @DispatchTo(service = OrderService.class, operation = "processOrder")
 *     void onOrderCreated(OrderCreatedEvent event);
 *
 *     @KafkaHandler(defaultHandler = true)
 *     void onUnmatched();
 * }
 * }</pre>
 *
 * @see DispatchTo
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface KafkaHandler {

    /**
     * Header name to match against. Mutually exclusive with {@link #matchProperty()}
     * and {@link #defaultHandler()}.
     *
     * @return the header name, or empty for no header matching
     */
    String matchHeader() default "";

    /**
     * JSON property name to match against (post-deserialization). Mutually exclusive with
     * {@link #matchHeader()} and {@link #defaultHandler()}.
     *
     * @return the property name, or empty for no property matching
     */
    String matchProperty() default "";

    /**
     * Value to match against the header or property.
     *
     * @return the match value
     */
    String matchValue() default "";

    /**
     * If {@code true}, this method catches all unmatched records. At most one per
     * {@link KafkaListener}. Mutually exclusive with {@link #matchHeader()} and
     * {@link #matchProperty()}.
     *
     * @return {@code true} if this is the default handler
     */
    boolean defaultHandler() default false;
}
