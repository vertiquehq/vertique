// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import dev.vertique.mcp.server.runtime.McpToolRuntime;

/**
 * Synthetic test-scope fixture for {@code McpProfileDependencyArchitectureTest}: it deliberately declares the
 * {@code dev.vertique.mcp.tool} package owned by {@code vertique-mcp-core} and then depends on the
 * {@code vertique-mcp-server} runtime, which is the reversed direction of the one approved
 * {@code mcp-server -> mcp-core} edge.
 *
 * <p>It is the single forbidden signature the direction rule has to catch: replacing the {@link McpToolRuntime}
 * return type with a neutral type must drop the rule's synthetic violation count from 1 to 0 while the production
 * count stays 0.
 *
 * <p>Nothing in production may follow this example.
 */
public final class SyntheticReversedMcpCoreToServerEdge {

    /**
     * Carries the one reversed {@code mcp-core -> mcp-server} edge in its return type.
     *
     * @return always {@code null}; only the compiled signature matters
     */
    public McpToolRuntime<?> reversedEdge() {
        return null;
    }
}
