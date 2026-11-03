// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.util.Map;

/**
 * The stored payload and application headers of one dead-lettered outbox entry.
 *
 * <p>The payload is the stored JSON text, unparsed: no application class is involved in reading it.
 * Carrier, security and context metadata are never returned.
 *
 * @param entryId     identifier of the entry; positive
 * @param revision    administration revision of the entry; not negative
 * @param payloadJson the stored payload as raw JSON text
 * @param headers     application headers only, as an unmodifiable copy; text only with unique keys
 */
public record OutboxDeadLetterContent(long entryId, long revision, String payloadJson, Map<String, String> headers) {

    /**
     * Creates a content view.
     *
     * @param entryId     identifier of the entry; positive
     * @param revision    administration revision; not negative
     * @param payloadJson the stored payload as JSON text; never {@code null}
     * @param headers     application headers; copied, {@code null} reads as empty, no {@code null} key or
     *                    value
     * @throws dev.vertique.core.exception.ValidationException if a component is absent or out of range
     */
    public OutboxDeadLetterContent {
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.nonNegative(revision, "revision");
        AdminChecks.required(payloadJson, "payloadJson");
        headers = AdminChecks.copyOf(headers, "headers");
    }

    /** Returns a string without the payload or the headers, so neither can leak through logging. */
    @Override
    public String toString() {
        return "OutboxDeadLetterContent[entryId=" + entryId + ", revision=" + revision + "]";
    }
}
