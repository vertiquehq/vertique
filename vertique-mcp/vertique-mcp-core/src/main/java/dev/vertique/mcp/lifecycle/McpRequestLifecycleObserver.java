// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import java.time.Instant;

/**
 * Factory for one neutral observation session per MCP request.
 *
 * <p>Implementations are contributed through Dagger set multibinding. Calls run synchronously on
 * the owning Vert.x context and must not block. The server isolates failures and null sessions.
 */
public interface McpRequestLifecycleObserver {

    /** Opens an observation session for a request that started at {@code startedAt}. */
    McpRequestObservation open(Instant startedAt);
}
