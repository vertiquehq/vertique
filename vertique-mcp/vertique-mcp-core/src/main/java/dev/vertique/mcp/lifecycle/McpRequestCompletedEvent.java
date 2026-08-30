// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import java.time.Instant;
import java.util.Objects;

/** Immutable transport facts recorded after an MCP request has settled on the wire. */
public record McpRequestCompletedEvent(
        McpRequestTerminalEvent terminal,
        Instant completedAt,
        McpTransportOutcome transportOutcome,
        boolean responseCommitted) {

    /**
     * Validates completion ordering and transport-state invariants.
     *
     * @throws NullPointerException if a required fact is null
     * @throws IllegalArgumentException if completion precedes terminal settlement or a written response is not committed
     */
    public McpRequestCompletedEvent {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(transportOutcome, "transportOutcome");
        if (completedAt.isBefore(terminal.terminalAt())) {
            throw new IllegalArgumentException("completedAt must not be before terminalAt");
        }
        if (transportOutcome == McpTransportOutcome.WRITTEN && !responseCommitted) {
            throw new IllegalArgumentException("written responses must be committed");
        }
    }

    /** Creates completion facts for a successfully written response. */
    public static McpRequestCompletedEvent written(McpRequestTerminalEvent terminal, Instant completedAt) {
        return new McpRequestCompletedEvent(terminal, completedAt, McpTransportOutcome.WRITTEN, true);
    }

    /** Creates completion facts for a disconnected response stream. */
    public static McpRequestCompletedEvent disconnected(
            McpRequestTerminalEvent terminal, Instant completedAt, boolean responseCommitted) {
        return new McpRequestCompletedEvent(terminal, completedAt, McpTransportOutcome.DISCONNECTED, responseCommitted);
    }

    /** Creates completion facts for a reset response stream. */
    public static McpRequestCompletedEvent reset(
            McpRequestTerminalEvent terminal, Instant completedAt, boolean responseCommitted) {
        return new McpRequestCompletedEvent(terminal, completedAt, McpTransportOutcome.RESET, responseCommitted);
    }

    /** Creates completion facts for a failed response write. */
    public static McpRequestCompletedEvent writeFailed(
            McpRequestTerminalEvent terminal, Instant completedAt, boolean responseCommitted) {
        return new McpRequestCompletedEvent(terminal, completedAt, McpTransportOutcome.WRITE_FAILED, responseCommitted);
    }
}
