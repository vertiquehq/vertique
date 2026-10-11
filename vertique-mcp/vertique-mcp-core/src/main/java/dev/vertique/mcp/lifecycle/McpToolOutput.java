// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import java.util.Optional;

/**
 * A tool call's normalized, schema-valid result as {@link McpRequestView#toolOutput()} reports it.
 *
 * <p>The framework provides the only implementation.
 */
public interface McpToolOutput {

    /**
     * Returns the normalized structured result.
     *
     * <p>The tree is read-only at every level: a {@code Map}, a {@code List} or a scalar.
     *
     * @return the structured result, or empty when the tool produced no structured output
     */
    Optional<Object> structuredContent();
}
