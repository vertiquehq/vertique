// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

/** The effective base access policy resolved for a tool at compile time. */
public enum McpAccessMode {

    /** The tool is public: every caller, authenticated or not, may invoke it. */
    PERMIT_ALL,

    /** The tool is closed: no caller may invoke it. */
    DENY_ALL,

    /** The tool requires roles, an action, or both, composed with AND. */
    RESTRICTED
}
