// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * How one exchange ended, as framework facts only: no header, path, query or body.
 *
 * <p>At least one of {@code statusCode} and {@code error} is present. {@link #toString()} prints the
 * class name of the error, never its message.
 *
 * @param statusCode the status of the response head when one arrived, otherwise {@code null}; between
 *     100 and 599 when present
 * @param error the failure as the caller sees it, or {@code null} when the exchange completed with a
 *     response
 * @param callId the id of the call: one per {@code execute} call, or per request sent through
 *     {@code webClient()}
 * @param attemptOrdinal the 1-based position of the attempt within its call
 * @param durationMs the time from the start of the exchange to its end in milliseconds, not negative
 * @param completedAt when the exchange ended
 * @param target the final hop
 */
public record HttpClientAttemptCompletion(
        @Nullable Integer statusCode,
        @Nullable Throwable error,
        String callId,
        int attemptOrdinal,
        long durationMs,
        Instant completedAt,
        HttpClientAttemptTarget target) {

    private static final int MIN_STATUS = 100;
    private static final int MAX_STATUS = 599;

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code callId}, {@code completedAt} or {@code target} is null
     * @throws IllegalArgumentException if the ordinal, the duration or the status is out of range, or
     *     neither a status nor an error is given
     */
    public HttpClientAttemptCompletion {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(target, "target");
        if (attemptOrdinal < 1) {
            throw new IllegalArgumentException("The attempt ordinal must be at least 1");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("The duration must not be negative");
        }
        if (statusCode != null && (statusCode < MIN_STATUS || statusCode > MAX_STATUS)) {
            throw new IllegalArgumentException("The status code must be between 100 and 599");
        }
        if (statusCode == null && error == null) {
            throw new IllegalArgumentException("A completion needs a status code or an error");
        }
    }

    @Override
    public String toString() {
        return "HttpClientAttemptCompletion[statusCode=" + statusCode + ", error="
                + (error == null ? "none" : error.getClass().getName()) + ", callId=" + callId + ", attemptOrdinal="
                + attemptOrdinal + ", durationMs=" + durationMs + ", completedAt=" + completedAt + ", target=" + target
                + "]";
    }
}
