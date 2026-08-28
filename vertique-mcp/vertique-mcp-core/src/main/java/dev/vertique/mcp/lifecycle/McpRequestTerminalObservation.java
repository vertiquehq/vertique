// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import dev.vertique.core.correlation.TraceReference;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Terminal lifecycle facts for one MCP request, together with its optional linked trace reference.
 *
 * <p><strong>Repair task R51 (trace-reference consolidation).</strong> {@code linkedTrace} carries
 * the request body's optional, untrusted {@code dev.vertique.core.correlation.TraceReference} —
 * extracted from {@code params._meta.traceparent}/{@code tracestate} only when {@code
 * McpBodyTracePolicy.LINK} is configured, and only when a valid, bounded reference is present.
 * This is the framework's single trace-reference type (formerly the MCP-local {@code
 * McpTraceContext}, deleted by R51); this payload-free lifecycle observation remains the reference's
 * sole carrier — it never reaches the pre-dispatch {@code
 * dev.vertique.mcp.interceptor.McpRequestContext} and never reaches {@code
 * dev.vertique.core.correlation.CorrelationContextSnapshot}, which R51 deliberately does not modify.
 * Link-only: never used as an input to identity, authorization, or tenancy decisions.
 *
 * @param event the terminal lifecycle facts; never {@code null}
 * @param linkedTrace the request body's optional, untrusted trace reference, when extraction ran
 *     and produced a valid, bounded reference; {@code null} under {@code McpBodyTracePolicy.IGNORE}
 *     (the default) and whenever no valid {@code traceparent} was present
 */
public record McpRequestTerminalObservation(
        McpRequestTerminalEvent event, @Nullable TraceReference linkedTrace) {

    /** Validates the mandatory terminal event. */
    public McpRequestTerminalObservation {
        Objects.requireNonNull(event, "event");
    }
}
