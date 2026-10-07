// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import dev.vertique.security.authz.AccessPolicy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The generated-runtime contract between the MCP server and one published tool.
 *
 * <p>One implementation is generated per {@code @McpTool} method and contributed to the server
 * through the generated Dagger module. Application code does not call this interface. A
 * hand-written implementation remains supported: it publishes its access through the descriptor, or
 * it may declare a typed policy through {@link #accessPolicy()} and must then publish the
 * {@link McpAccessMode#DENY_ALL} legacy descriptor.
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
     * Returns the typed access policy that governs this tool, when it declares one.
     *
     * <p>A generated invoker for a tool that references a policy overrides this method and returns
     * that policy type. The empty default keeps every invoker that predates the hook binary
     * compatible and means the tool is governed by the access record of its {@link #descriptor()}.
     *
     * <p>A typed invoker must publish a descriptor whose access is {@link McpAccessMode#DENY_ALL}
     * with no roles and no action. A runtime that reads only the descriptor and ignores this hook
     * therefore hides and refuses the tool instead of silently dropping requirements it cannot
     * express. A hand-written invoker may implement this method the same way; the registry
     * validates the returned policy when it registers the invoker.
     *
     * @return the typed policy type, or empty when the descriptor's access record governs the tool
     */
    default Optional<Class<? extends AccessPolicy>> accessPolicy() {
        return Optional.empty();
    }

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
     * Returns the immutable set of top-level client capabilities required before invocation.
     *
     * <p>The server evaluates this declaration after authorization and before input preparation.
     * The default preserves compatibility for hand-written invokers that require no client
     * capability.
     *
     * @return required client capability names, never {@code null}
     */
    default Set<String> requiredClientCapabilities() {
        return Set.of();
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
