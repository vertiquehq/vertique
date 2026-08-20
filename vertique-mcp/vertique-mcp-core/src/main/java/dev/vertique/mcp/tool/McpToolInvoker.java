// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import java.util.Map;

/**
 * The generated-runtime contract between the MCP server and one published tool.
 *
 * <p>One implementation is generated per {@code @McpTool} method and contributed to the server
 * through the generated Dagger module. Application code neither implements nor calls this
 * interface.
 *
 * <p>{@link #prepare(Map, McpCancellationSignal)} is the fixed input boundary. Schema validation
 * stays server-owned and happens before it; the generated code then applies input policies,
 * materializes typed parameters through the effective JSON profile, and performs Bean Validation.
 * A failure at any of those stages yields no prepared call.
 */
public interface McpToolInvoker {

    /**
     * Returns the immutable descriptor built once during application composition.
     *
     * @return the published descriptor of this tool
     */
    McpToolDescriptor descriptor();

    /**
     * Prepares one call after the server has validated the arguments against the input schema.
     *
     * @param arguments the schema-validated argument tree, as normalized by the server
     * @param cancellation the signal the handler may consult to stop cooperative work
     * @return the prepared call carrying the post-processing argument tree and the invocation
     *     closure
     */
    McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation);
}
