// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * Raw, unredacted admission-time evidence for one {@code tools/call} request, delivered once to an
 * opt-in {@link McpRawEvidenceObservation} session through {@link
 * McpRawEvidenceObservation#onRequestAdmitted}.
 *
 * <p>Carries the exact bytes the server already read to parse the JSON-RPC envelope, the exact
 * request headers, and the two identifying facts a forensic audit trail needs — the client-supplied
 * JSON-RPC request id and the caller's resolved principal id — <strong>raw and unhashed</strong>. This
 * type exists below the neutral, payload-free {@link McpRequestObservation}/{@link
 * McpToolValueObservation} contract; only a session that structurally opts into {@link
 * McpRawEvidenceObservation} — in practice, the audit adapter's own private evidence-capture session
 * — ever receives it. Delivered once per request, before tool-name resolution or authorization, so it
 * reaches an opt-in session even for a request rejected before the input pipeline runs (an unknown
 * tool, an authorization denial, a protocol-level rejection).
 *
 * @param body the raw request-body bytes already read to parse the JSON-RPC envelope; never {@code
 *     null}, may be empty
 * @param headers the request's HTTP headers, name to last value; never {@code null}, may be empty
 * @param contentType the request's {@code Content-Type} header value, or {@code null} when absent
 * @param jsonRpcRequestId the client-supplied JSON-RPC {@code id}, in its wire textual form, or {@code
 *     null} when the request carried no id
 * @param principalId the caller's resolved principal id, or {@code null} when no security identity
 *     was established for this request
 */
public record McpRequestAdmissionEvidence(
        byte[] body,
        Map<String, String> headers,
        @Nullable String contentType,
        @Nullable String jsonRpcRequestId,
        @Nullable String principalId) {

    /**
     * Validates required fields and defensively copies {@code headers} into an unmodifiable view.
     *
     * @throws NullPointerException if {@code body} or {@code headers} is {@code null}
     */
    public McpRequestAdmissionEvidence {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headers, "headers");
        headers = Map.copyOf(headers);
    }
}
