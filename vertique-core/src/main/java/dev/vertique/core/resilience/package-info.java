// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared resilience primitives: annotations, backoff strategies, and retry policies.
 *
 * <p>This package provides the building blocks for resilience patterns (circuit breaker, retry,
 * timeout) that are shared across framework modules such as {@code rest-client} and
 * {@code services}. The types here are pure Java with no Vert.x circuit breaker dependency —
 * integration with Vert.x's {@code CircuitBreaker} is left to each consumer module.
 *
 * <h2>Annotations</h2>
 *
 * <ul>
 *   <li>{@link dev.vertique.core.resilience.CircuitBreaker} — failure tracking and circuit
 *       open/half-open/closed lifecycle</li>
 *   <li>{@link dev.vertique.core.resilience.Retry} — retry count, backoff strategy, and
 *       exception filtering</li>
 *   <li>{@link dev.vertique.core.resilience.Timeout} — per-attempt timeout with configurable
 *       time unit</li>
 * </ul>
 *
 * <h2>Interfaces</h2>
 *
 * <ul>
 *   <li>{@link dev.vertique.core.resilience.BackoffStrategy} — computes delay between retry
 *       attempts; built-in factories for exponential, fixed, and no-delay strategies</li>
 *   <li>{@link dev.vertique.core.resilience.RetryPolicy} — determines whether a failure is
 *       eligible for retry</li>
 * </ul>
 *
 * <h2>Utilities</h2>
 *
 * <ul>
 *   <li>{@link dev.vertique.core.resilience.ResilienceAnnotations} — resolves
 *       {@code @CircuitBreaker}, {@code @Retry}, and {@code @Timeout} from a method and its
 *       declaring type with method-level-overrides-type-level semantics</li>
 *   <li>{@link dev.vertique.core.resilience.BackoffStrategyResolver} — resolves the effective
 *       {@link dev.vertique.core.resilience.BackoffStrategy} from a {@code @Retry} annotation,
 *       choosing between a custom class reference and inline parameters</li>
 * </ul>
 */
package dev.vertique.core.resilience;
