// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;

/**
 * A single method invocation flowing through the interceptor chain.
 *
 * <p>An {@link MethodInterceptor} reads the {@link #target() metadata}, {@link #arguments()
 * arguments}, and {@link #instance() instance} of the call, then invokes {@link #proceed()} to run
 * the rest of the chain (eventually reaching the terminal {@code super.method(...)} call).
 *
 * <p>The {@code arguments} array is read at each terminal dispatch, so a mutation an interceptor
 * makes to it before calling {@link #proceed()} is visible to the underlying method on that call.
 */
public interface Invocation {

    /**
     * Returns the reflection-free metadata of the intercepted method.
     *
     * @return the method metadata
     */
    MethodMetadata target();

    /**
     * Returns the live argument array of the invocation.
     *
     * <p>The array is read at each terminal dispatch, so mutations made before a {@link #proceed()}
     * call are visible to the underlying method and to subsequent {@code proceed()} calls.
     *
     * @return the mutable argument array
     */
    Object[] arguments();

    /**
     * Returns the proxied bean instance on which the method was invoked.
     *
     * @return the target instance
     */
    Object instance();

    /**
     * Invokes the remainder of the interceptor chain from this position, terminating in the
     * underlying {@code super.method(...)} call.
     *
     * <p>Re-entrant by construction: each call independently invokes the downstream chain from this
     * position, with the continuation captured per-call rather than as a mutated shared index. v1
     * aspects call it exactly once; building it re-entrant lets a future deferring aspect (for
     * example a retry) call it {@code 0..n} times with no SPI change. The result is normalized to a
     * {@link Future}.
     *
     * @return a future of the downstream result
     */
    Future<Object> proceed();
}
