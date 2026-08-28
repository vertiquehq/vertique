// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

/**
 * Governs whether the MCP request body's optional W3C trace reference ({@code
 * params._meta.traceparent}/{@code tracestate}) is parsed at all, and whether a distinct extracted
 * reference is linked onto the request's OpenTelemetry span (repair task R51).
 *
 * <p>Mirrors Vert.x's own {@code TracingPolicy} default-off posture: the body reference is
 * client-supplied and, absent a trusted upstream gateway, carries exactly the same trust posture as
 * an inbound HTTP {@code traceparent} header from an anonymous caller — untrusted, link-only data
 * that must never enter an identity, authorization, or tenancy decision (D004; repair task R47).
 * Configured via {@code mcp.bodyTracePolicy}.
 */
public enum McpBodyTracePolicy {

    /**
     * The default. {@code params._meta.traceparent}/{@code tracestate} are never parsed: {@code
     * McpProtocolCodec#extractBodyTraceContext} is never invoked, so there is no extraction cost and
     * no diagnostic is ever logged for a malformed or oversized body trace value. No span link is
     * ever added, and {@code McpRequestTerminalObservation#linkedTrace()} is always {@code null}.
     * Protects a gateway-less, open deployment from an untrusted caller claiming an arbitrary trace
     * identity.
     */
    IGNORE,

    /**
     * Today's (repair task R39) extraction behavior: a bounded, syntactically-validated {@code
     * traceparent}/{@code tracestate} pair is extracted at most once per request and, when its trace
     * id differs from the request's own HTTP-established trace, linked onto the request's
     * OpenTelemetry span. Appropriate for a deployment behind a header-cleaning gateway, or one that
     * otherwise trusts its callers not to forge the body reference.
     */
    LINK
}
