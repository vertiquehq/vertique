// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique compile-time method AOP SPI.
 *
 * <p>This module defines the small, reflection-free contract that the {@code vertique-codegen-aop}
 * processor generates against to apply cross-cutting concerns (for example {@code @Timed}) to any
 * method on an application bean. The SPI is deliberately tiny:
 * <ul>
 *   <li>{@link dev.vertique.aop.Aspect} — the meta-annotation that marks a user annotation as an
 *       aspect and declares its {@code ordering()} in the around-chain.</li>
 *   <li>{@link dev.vertique.aop.AspectProvider} — the framework-side factory that, given a
 *       {@link dev.vertique.core.codegen.MethodMetadata target} and the aspect annotation instance,
 *       produces the {@link dev.vertique.aop.MethodInterceptor} for one method.</li>
 *   <li>{@link dev.vertique.aop.MethodInterceptor} / {@link dev.vertique.aop.Invocation} — the
 *       around-advice contract: an interceptor wraps an {@code Invocation} and calls
 *       {@link dev.vertique.aop.Invocation#proceed()} to invoke the rest of the chain.</li>
 *   <li>{@link dev.vertique.aop.Invocations} — the continuation nester that folds an ordered
 *       interceptor array around a terminal {@code super.method(...)} call so each
 *       {@code proceed()} re-enters the downstream chain from its own position.</li>
 * </ul>
 *
 * <p>The contract is entirely asynchronous: every interception result is normalized to a
 * {@link io.vertx.core.Future} so that aspects never block the Vert.x event loop. Ordering of
 * aspects by {@link dev.vertique.aop.Aspect#ordering()} and annotation FQN is resolved at compile
 * time by the processor; the runtime nester simply applies the array it is handed in order.
 *
 * <p><b>Allocated {@code @Aspect(ordering)} registry.</b> Every aspect family records its chosen
 * ordering value here so a future family never picks a colliding value blind. A higher value is
 * outermost.
 *
 * <ul>
 *   <li>{@code 50} — {@code @Resilient} ({@code vertique-resilience})
 *   <li>{@code 100} — {@code @CacheEvict} ({@code vertique-cache-aop})
 *   <li>{@code 200} — {@code @Cacheable} ({@code vertique-cache-aop})
 *   <li>{@code 300} — {@code @RateLimited} ({@code vertique-rate-limit-aop})
 *   <li>{@code 1000} — {@code @Timed} (default; {@link dev.vertique.aop.Aspect#ordering()})
 * </ul>
 *
 * <p>An application aspect ordered below 50 runs inside retry and is re-executed once per
 * retry attempt.
 */
package dev.vertique.aop;
