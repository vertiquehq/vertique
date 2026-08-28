// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import java.util.Map;
import java.util.Objects;

/**
 * Raw, unredacted response-side evidence for one request, delivered once to an opt-in {@link
 * McpRawEvidenceObservation} session through {@link McpRawEvidenceObservation#onResponseWritten}
 * (R52, repair task R52 "audit capture parity").
 *
 * <p>Carries the exact bytes the single shared terminal writer is about to send to the wire — the same
 * bounded envelope {@code mcp.output.maxBytes} already caps — and the response headers as they stand
 * immediately before the write. Delivered for every terminal write on the {@code tools/call} surface,
 * including a bounded error or rejection response, so an opt-in session observes the response the
 * caller actually received on every settlement path that produces one.
 *
 * @param body the exact response bytes about to be written to the wire; never {@code null}, may be
 *     empty
 * @param headers the response's HTTP headers, name to last value, as set immediately before the write;
 *     never {@code null}, may be empty
 */
public record McpResponseEvidence(byte[] body, Map<String, String> headers) {

    /**
     * Validates required fields and defensively copies {@code headers} into an unmodifiable view.
     *
     * @throws NullPointerException if {@code body} or {@code headers} is {@code null}
     */
    public McpResponseEvidence {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headers, "headers");
        headers = Map.copyOf(headers);
    }
}
