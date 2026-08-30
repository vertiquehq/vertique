// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

/**
 * The immutable behavior hints advertised for one tool.
 *
 * <p>These are the values declared by {@code @McpTool} and published verbatim in the tool listing.
 * They are hints for clients, not enforced guarantees: the framework neither verifies nor relies on
 * them when dispatching a call.
 *
 * @param readOnlyHint whether the tool only reads and does not modify its environment
 * @param destructiveHint whether the tool may destroy or overwrite state
 * @param idempotentHint whether repeated calls with the same arguments have no additional effect
 * @param openWorldHint whether the tool interacts with an open world of external entities
 */
public record McpToolAnnotations(
        boolean readOnlyHint, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {}
