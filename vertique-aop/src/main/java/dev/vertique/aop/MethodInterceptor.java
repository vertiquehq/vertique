// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import io.vertx.core.Future;

/**
 * Around-advice applied to a single method invocation.
 *
 * <p>An interceptor receives an {@link Invocation} and is free to inspect or mutate its
 * {@link Invocation#arguments() arguments}, run logic before and after the downstream call, and
 * decide when (and how many times) to invoke {@link Invocation#proceed()}. The result of the call is
 * always a {@link Future} so that interception never blocks the Vert.x event loop; a synchronous
 * method's value is normalized into a completed future by the generated proxy.
 *
 * <p>v1 aspects call {@code proceed()} exactly once and pass the outcome through unchanged. The
 * contract permits 0..n calls, which lets a future deferring aspect (for example a retry) re-enter
 * the chain without any SPI change.
 */
@FunctionalInterface
public interface MethodInterceptor {

    /**
     * Intercepts a method invocation.
     *
     * @param invocation the invocation being intercepted, exposing the downstream chain via
     *     {@link Invocation#proceed()}
     * @return a future of the (possibly transformed) invocation result; never {@code null}
     */
    Future<Object> intercept(Invocation invocation);
}
