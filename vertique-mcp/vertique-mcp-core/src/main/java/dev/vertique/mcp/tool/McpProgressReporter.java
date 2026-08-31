// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/** Reports standard MCP request progress; it never represents a partial tool result. */
@FunctionalInterface
public interface McpProgressReporter {

    /**
     * Reports a strictly increasing progress value to the requesting client.
     *
     * <p>When the request did not include a progress token, the server treats this as a successful
     * no-op. Implementations may also coalesce or stop reporting after their bounded output budget is
     * reached.
     *
     * @param progress the current non-negative finite progress value
     * @param total the optional non-negative finite total
     * @param message the optional bounded human-readable status
     * @return completion of the notification write, or a successful no-op
     */
    Future<Void> report(double progress, @Nullable Double total, @Nullable String message);

    /** Returns a reporter that intentionally emits no network notifications. */
    static McpProgressReporter noop() {
        return (progress, total, message) -> Future.succeededFuture();
    }
}
