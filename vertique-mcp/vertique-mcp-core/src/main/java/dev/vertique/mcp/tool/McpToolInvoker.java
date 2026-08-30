// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import java.util.Map;
import java.util.Optional;

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
     * Returns the effective-profile writer for structured application results.
     *
     * <p>Generated invokers always return their runtime-bound writer. The empty default exists only
     * for hand-written framework fixtures, which retain the server's neutral compatibility writer.
     * Inheriting the empty default routes structured-output serialization through that neutral
     * compatibility writer, not the tool's selected JSON profile.
     *
     * @return the generated runtime's structured-output writer, or empty for a hand-written fixture
     */
    default Optional<McpStructuredOutputWriter> structuredOutputWriter() {
        return Optional.empty();
    }

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
