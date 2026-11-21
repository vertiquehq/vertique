// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.core.exception.ValidationException;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * What an inspector sees of one outbox entry: the stored payload, the application headers and the application's last
 * error text. All three can hold secrets, and the error text can quote payload fragments, so they travel together
 * in the read that needs the higher privilege; {@link OutboxEntryDetail} carries none of them.
 *
 * <p>The payload is the stored JSON text, unparsed: no application class is involved in reading it. The last error is
 * read from the database at most {@value #MAX_LAST_ERROR_LENGTH} characters long, and {@link #lastErrorTruncated()}
 * says whether there was more. Carrier, security and context metadata are never returned.
 *
 * @param entryId            identifier of the entry; positive
 * @param payloadJson        the stored payload as raw JSON text
 * @param headers            application headers only, as an unmodifiable copy; text only with unique keys
 * @param lastError          the last failure message, cut at {@value #MAX_LAST_ERROR_LENGTH} characters, or
 *                           {@code null} when the entry has none
 * @param lastErrorTruncated whether the stored last error was longer than what is returned
 */
public record OutboxEntryPayload(
        long entryId,
        String payloadJson,
        Map<String, String> headers,
        @Nullable String lastError,
        boolean lastErrorTruncated) {

    /** Longest last error the payload read returns, in characters (code points). */
    public static final int MAX_LAST_ERROR_LENGTH = 4096;

    /**
     * Creates a payload view.
     *
     * @param entryId            identifier of the entry; positive
     * @param payloadJson        the stored payload as JSON text; never {@code null}
     * @param headers            application headers; copied, {@code null} reads as empty, no {@code null} key or value
     * @param lastError          the last failure message, or {@code null}
     * @param lastErrorTruncated whether the stored last error was longer than {@code lastError}
     * @throws ValidationException if a component is absent or out of range, or the last error is too long
     */
    public OutboxEntryPayload {
        QueryChecks.positive(entryId, "entryId");
        QueryChecks.required(payloadJson, "payloadJson");
        headers = QueryChecks.copyOf(headers, "headers");
        if (lastError != null && lastError.codePointCount(0, lastError.length()) > MAX_LAST_ERROR_LENGTH) {
            throw new ValidationException("lastError must be at most " + MAX_LAST_ERROR_LENGTH + " characters");
        }
    }

    /** Returns a string without the payload, headers or last error, so none can leak through logging. */
    @Override
    public String toString() {
        return "OutboxEntryPayload[entryId=" + entryId + "]";
    }
}
