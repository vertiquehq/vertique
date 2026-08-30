// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/** Logical request settlement classifications. */
public enum McpOutcome {
    SUCCESS,
    TOOL_ERROR,
    REJECTED,
    FAILED,
    CANCELLED
}
