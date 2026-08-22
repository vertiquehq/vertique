// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;

/**
 * SPI for a zero-or-more ordered chain of rejective hooks that run at the frozen pre-dispatch stage
 * — after the envelope is decoded and identity is established, but before the method is dispatched
 * to a handler and before any tool is resolved or argument is processed.
 *
 * <p>{@link #beforeRequest(McpRequestContext)} is invoked exactly once per applicable request, on
 * the request's owning Vert.x context. Implementations must not block, must never return
 * {@code null}, and reject a request only by completing the returned {@link Future} with a failure
 * — never by throwing past this method in a way callers must catch. A rejection stops dispatch with
 * the frozen bounded external error; a synchronous throw, a {@code null} future, or an unexpectedly
 * failed future all reject fail-closed the same way, with no exception message ever reaching the
 * client.
 *
 * <p>An interceptor may permit or reject; it can never mutate the request, reorder the fixed
 * pipeline stages, recover a failure another stage produced, or observe raw headers or the request
 * body — {@link McpRequestContext} exposes no such accessor. Phase 1 provides no general recovery
 * capability: once rejected, a request cannot be turned back into success by any interceptor.
 *
 * <p>Interceptors are ordered by the {@link OrderedExtension} {@code phase} → {@code priority} →
 * {@code orderKey} comparator and never fall back to Dagger set iteration order. Two interceptors
 * sharing the same {@code (phase, priority, orderKey)} triple fail startup, naming both conflicting
 * implementation classes, rather than silently picking one order.
 *
 * <p>Named implementors include an application tenant-entitlement guard and a maintenance-window
 * guard. Future optional capability is added only through a default method or a separate SPI; this
 * interface's one published abstract-shaped method never gains a sibling.
 *
 * <p>Register implementations via Dagger set multibinding ({@code @IntoSet}).
 *
 * @see OrderedExtension
 * @see McpRequestContext
 */
public interface McpRequestInterceptor extends OrderedExtension {

    /**
     * Runs this interceptor's pre-dispatch check for one request.
     *
     * <p>Called exactly once per applicable request, on the request's owning Vert.x context. Must
     * not block and must never return {@code null}. The default implementation always permits.
     *
     * @param context the immutable, payload-free pre-dispatch snapshot
     * @return a {@link Future} that succeeds to permit the request, or fails to reject it; never
     *     {@code null}
     */
    default Future<Void> beforeRequest(McpRequestContext context) {
        return Future.succeededFuture();
    }
}
