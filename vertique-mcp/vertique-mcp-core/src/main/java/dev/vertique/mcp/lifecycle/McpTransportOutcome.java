// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

/** Post-settlement response transport outcomes. */
public enum McpTransportOutcome {
    WRITTEN,
    DISCONNECTED,
    RESET,
    WRITE_FAILED
}
