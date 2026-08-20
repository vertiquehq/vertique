// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import dev.vertique.mcp.interceptor.McpTraceContext;
import jakarta.annotation.Nullable;
import java.util.Objects;

/** Terminal lifecycle facts with an optional normalized trace reference. */
public record McpRequestTerminalObservation(
        McpRequestTerminalEvent event, @Nullable McpTraceContext bodyTraceContext) {

    /** Validates the mandatory terminal event. */
    public McpRequestTerminalObservation {
        Objects.requireNonNull(event, "event");
    }
}
