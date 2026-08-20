// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/**
 * Observe-only callback invoked once after response transport completion.
 *
 * <p>Implementations must not block or alter request processing. The server isolates listener
 * failures and does not define listener ordering.
 */
public interface McpRequestCompletedListener {

    /** Receives the completed request facts. */
    void onCompleted(McpRequestCompletedEvent event);
}
