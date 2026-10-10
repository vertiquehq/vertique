// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import io.vertx.core.Future;

/**
 * Lets a tool handler stop cooperative work when its call is cancelled.
 *
 * <p>This is the only framework-supplied parameter a tool method may declare. It is excluded from
 * the published input schema. The server fires it exactly once when the request settles as anything
 * other than a successful write: a client disconnect, a response stream reset, a failed write, the
 * shared HTTP layer closing an idle or slow connection (which reaches this same disconnect/reset
 * settlement path rather than a distinct timeout), or the mount's optional request deadline
 * expiring. The signal never fires a deadline on its own account.
 *
 * <p>Cancellation is cooperative only: the framework cannot forcibly stop a handler that ignores
 * the signal.
 */
public interface McpCancellationSignal {

    /** Returns the request-scoped standard MCP progress reporter, which may be a no-op. */
    default McpProgressReporter progressReporter() {
        return McpProgressReporter.noop();
    }

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
