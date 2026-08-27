// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import io.vertx.core.Future;

/**
 * Lets a tool handler stop cooperative work when its call is cancelled.
 *
 * <p>This is the only framework-supplied parameter a tool method may declare. It is excluded from
 * the published input schema. The server fires it exactly once when the request settles as anything
 * other than a successful write: a client disconnect, a response stream reset, a failed write, or —
 * because MCP arms no whole-request timer of its own — the shared HTTP layer closing an idle or slow
 * connection, which reaches this same disconnect/reset settlement path rather than a distinct
 * timeout. MCP-001 has no timeout producer of its own; every case above is a transport-level
 * settlement, never a deadline this signal fires on its own account.
 *
 * <p>Cancellation is cooperative only: the framework cannot forcibly stop a handler that ignores
 * the signal.
 */
public interface McpCancellationSignal {

    /**
     * Reports whether the call has already been cancelled.
     *
     * <p>Safe to poll from any thread, including an application worker thread backing the tool
     * invocation, not only the request-owning Vert.x context. A callback-style consumer should prefer
     * {@link #cancelled()} instead.
     *
     * @return {@code true} once the call is cancelled
     */
    boolean isCancelled();

    /**
     * Returns a future that completes when the call is cancelled.
     *
     * @return a future completed on cancellation, and never completed otherwise
     */
    Future<Void> cancelled();
}
