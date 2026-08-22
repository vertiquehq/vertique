// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;

/**
 * SPI for a zero-or-more ordered chain of rejective hooks that run at the frozen post-validation
 * stage — after Bean Validation has already run inside the generated invoker's {@code prepare(...)},
 * but before the generated invocation ({@code McpPreparedToolCall#invoke()}) ever runs.
 *
 * <p>{@link #beforeInvocation(McpToolInvocationContext)} is invoked exactly once per applicable
 * {@code tools/call} request, on the request's owning Vert.x context. Implementations must not
 * block, must never return {@code null}, and reject a call only by completing the returned
 * {@link Future} with a failure — never by throwing past this method in a way callers must catch. A
 * rejection stops the call with the frozen bounded external error before the generated invoker ever
 * runs; a synchronous throw, a {@code null} future, or an unexpectedly failed future all reject
 * fail-closed the same way, with no exception message ever reaching the client.
 *
 * <p>A tool interceptor may permit or reject; it can never mutate an argument, reorder the fixed
 * pipeline stages, or recover a failure another stage produced — {@link McpToolInvocationContext}
 * exposes no argument accessor of any kind, raw or normalized. This is the second and final live
 * interceptor stage; the pre-dispatch {@link McpRequestInterceptor} stage runs earlier, before any
 * tool is resolved.
 *
 * <p>Interceptors are ordered by the {@link OrderedExtension} {@code phase} → {@code priority} →
 * {@code orderKey} comparator and never fall back to Dagger set iteration order. Two interceptors
 * sharing the same {@code (phase, priority, orderKey)} triple fail startup, naming both conflicting
 * implementation classes, rather than silently picking one order.
 *
 * <p>Named implementors include a per-tool entitlement guard and a data-loss-prevention guard.
 * Future optional capability is added only through a default method or a separate SPI; this
 * interface's one published abstract-shaped method never gains a sibling.
 *
 * <p>Register implementations via Dagger set multibinding ({@code @IntoSet}).
 *
 * @see OrderedExtension
 * @see McpToolInvocationContext
 */
public interface McpToolInterceptor extends OrderedExtension {

    /**
     * Runs this interceptor's post-validation check for one prepared tool call.
     *
     * <p>Called exactly once per applicable {@code tools/call} request, on the request's owning
     * Vert.x context, after Bean Validation has already run and before the generated invocation runs.
     * Must not block and must never return {@code null}. The default implementation always permits.
     *
     * @param context the immutable, argument-free descriptor snapshot for this call
     * @return a {@link Future} that succeeds to permit the call, or fails to reject it; never
     *     {@code null}
     */
    default Future<Void> beforeInvocation(McpToolInvocationContext context) {
        return Future.succeededFuture();
    }
}
