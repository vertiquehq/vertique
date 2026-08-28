// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service contract method as one-way (fire-and-forget).
 *
 * <p>One-way methods use {@code eventBus.send()} instead of {@code eventBus.request()},
 * and the server does not send a reply after processing. The client proxy returns
 * {@code Future.succeededFuture()} immediately after the message is sent.
 *
 * <p>Methods annotated with {@code @OneWay} must return {@code Future<Void>}.
 * Resilience policies ({@link dev.vertique.resilience.annotation.Timeout @Timeout},
 * {@link dev.vertique.resilience.annotation.CircuitBreaker @CircuitBreaker},
 * {@link dev.vertique.resilience.annotation.Retry @Retry}) are still enforced on the
 * server side during processing.
 *
 * <p>Example:
 * <pre>{@code
 * @ServiceContract(namespace = "integration", value = "notification-service")
 * public interface NotificationService {
 *     @OneWay
 *     @ServiceOperation("publish")
 *     Future<Void> publish(NotificationEvent event);
 * }
 * }</pre>
 *
 * @apiNote The {@code Future<Void>} returned by the client proxy completing successfully only
 *     means the message was submitted to the event bus — it does <strong>not</strong> guarantee
 *     delivery or processing. If no consumer is registered, the message is silently dropped.
 *     Server-side failures are logged at WARN level but never propagated to the caller. For
 *     operations that require delivery confirmation, use the default request-response pattern
 *     instead.
 * @see ServiceContract
 * @see ServiceOperation
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OneWay {}
