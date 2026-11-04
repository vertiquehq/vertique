// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import java.util.Map;

/**
 * The stored payload and application headers of one outbox entry.
 *
 * <p>The payload is the stored JSON text, unparsed: no application class is involved in reading it. Carrier,
 * security and context metadata are never returned.
 *
 * @param entryId     identifier of the entry; positive
 * @param payloadJson the stored payload as raw JSON text
 * @param headers     application headers only, as an unmodifiable copy; text only with unique keys
 */
public record OutboxEntryPayload(long entryId, String payloadJson, Map<String, String> headers) {

    /**
     * Creates a payload view.
     *
     * @param entryId     identifier of the entry; positive
     * @param payloadJson the stored payload as JSON text; never {@code null}
     * @param headers     application headers; copied, {@code null} reads as empty, no {@code null} key or value
     * @throws dev.vertique.core.exception.ValidationException if a component is absent or out of range
     */
    public OutboxEntryPayload {
        QueryChecks.positive(entryId, "entryId");
        QueryChecks.required(payloadJson, "payloadJson");
        headers = QueryChecks.copyOf(headers, "headers");
    }

    /** Returns a string without the payload or the headers, so neither can leak through logging. */
    @Override
    public String toString() {
        return "OutboxEntryPayload[entryId=" + entryId + "]";
    }
}
