// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import io.vertx.core.Future;
import java.util.Map;

/**
 * One tool call that passed every input stage and is ready to run.
 *
 * <p>A prepared call exists only when schema validation, input policy processing, parameter
 * materialization, and Bean Validation all succeeded. It is produced by
 * {@link McpToolInvoker#prepare(Map, McpCancellationSignal)}; application code neither implements
 * nor calls this interface.
 */
public interface McpPreparedToolCall {

    /**
     * Returns the exact argument tree as it stands after input policy processing.
     *
     * <p>The map is deeply immutable, bounded, and JSON-compatible. The server hands this exact
     * tree to opt-in value-observation sessions; it never substitutes the pre-processing map and
     * never re-runs processing to rebuild it.
     *
     * @return the deeply immutable post-processing argument tree
     */
    Map<String, Object> normalizedArguments();

    /**
     * Invokes the application method directly with the already-materialized validated arguments.
     *
     * @return the tool's result, or a failed future when the handler fails
     */
    Future<McpToolResult<?>> invoke();
}
