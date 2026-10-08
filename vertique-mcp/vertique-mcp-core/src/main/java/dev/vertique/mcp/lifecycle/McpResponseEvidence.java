// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import java.util.Map;
import java.util.Objects;

/**
 * Raw, unredacted response-side evidence for one request, delivered once to an opt-in {@link
 * McpRawEvidenceObservation} session through {@link McpRawEvidenceObservation#onResponseWritten}.
 *
 * <p>Carries the exact bytes the single shared terminal writer is about to send to the wire — the same
 * bounded envelope {@code mcp.output.maxBytes} already caps — and the response headers as they stand
 * immediately before the write. Delivered for every terminal write on the {@code tools/call} surface,
 * including a bounded error or rejection response, so an opt-in session observes the response the
 * caller actually received on every settlement path that produces one.
 *
 * <p>The evidence is a snapshot: {@code body} is copied on the way in and on every {@link #body()}
 * read, so an observer that modifies the array it receives changes neither the bytes written to the
 * wire nor what another observer sees.
 *
 * @param body a copy of the exact response bytes about to be written to the wire; never {@code null},
 *     may be empty
 * @param headers the response's HTTP headers, name to last value, as set immediately before the write;
 *     never {@code null}, may be empty
 */
public record McpResponseEvidence(byte[] body, Map<String, String> headers) {

    /**
     * Validates required fields, copies {@code body}, and defensively copies {@code headers} into an
     * unmodifiable view.
     *
     * @throws NullPointerException if {@code body} or {@code headers} is {@code null}
     */
    public McpResponseEvidence {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headers, "headers");
        body = body.clone();
        headers = Map.copyOf(headers);
    }

    /**
     * Returns a copy of the response bytes; modifying it affects no other reader.
     *
     * @return a fresh copy of the exact response bytes; never {@code null}
     */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
