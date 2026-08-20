// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import io.vertx.core.Future;

/**
 * Lets a tool handler stop cooperative work when its call is cancelled.
 *
 * <p>This is the only framework-supplied parameter a tool method may declare. It is excluded from
 * the published input schema, and the server supplies it when the client disconnects or the call
 * times out.
 *
 * <p>Cancellation is cooperative only: the framework cannot forcibly stop a handler that ignores
 * the signal.
 */
public interface McpCancellationSignal {

    /**
     * Reports whether the call has already been cancelled.
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
