// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * How one exchange ended: framework facts, and the request and response bodies when the client captures
 * them. No header, path or query is part of the record.
 *
 * <p>At least one of {@code statusCode} and {@code error} is present. {@link #toString()} prints the
 * class name of the error, never its message, and never a body.
 *
 * <p>The bodies are {@link PayloadSource} views that are valid only during the call of
 * {@code onAttemptCompleted} that receives this record: a body may view the caller's own buffer, so an
 * interceptor copies the bytes it keeps. They are delivered as written and received, unredacted;
 * redacting what an interceptor stores or logs is its own job.
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
 * @param requestBody the request body as the client sent it, or an absent payload when the client does not
 *     capture bodies or the exchange had none; never {@code null}. A streamed or multipart body, or one over
 *     the request bound, is reported as a streaming payload with its declared length and no bytes
 * @param responseBody the response body, or an absent payload when the client does not capture bodies or no
 *     response head arrived; never {@code null}. A body that did not complete, or that a stream call
 *     delivers, is reported as a streaming payload with the head's content type and declared length and no
 *     bytes
 */
public record HttpClientAttemptCompletion(
        @Nullable Integer statusCode,
        @Nullable Throwable error,
        String callId,
        int attemptOrdinal,
        long durationMs,
        Instant completedAt,
        HttpClientAttemptTarget target,
        PayloadSource requestBody,
        PayloadSource responseBody) {

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
        requestBody = requestBody == null ? PayloadSources.absent() : requestBody;
        responseBody = responseBody == null ? PayloadSources.absent() : responseBody;
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

    /**
     * Creates a completion that carries no body: both bodies are absent.
     *
     * @param statusCode the status of the response head, or {@code null}
     * @param error the failure as the caller sees it, or {@code null}
     * @param callId the id of the call
     * @param attemptOrdinal the 1-based position of the attempt within its call
     * @param durationMs the duration in milliseconds, not negative
     * @param completedAt when the exchange ended
     * @param target the final hop
     * @throws NullPointerException if {@code callId}, {@code completedAt} or {@code target} is null
     * @throws IllegalArgumentException if the ordinal, the duration or the status is out of range, or
     *     neither a status nor an error is given
     */
    public HttpClientAttemptCompletion(
            @Nullable Integer statusCode,
            @Nullable Throwable error,
            String callId,
            int attemptOrdinal,
            long durationMs,
            Instant completedAt,
            HttpClientAttemptTarget target) {
        this(
                statusCode,
                error,
                callId,
                attemptOrdinal,
                durationMs,
                completedAt,
                target,
                PayloadSources.absent(),
                PayloadSources.absent());
    }

    @Override
    public String toString() {
        return "HttpClientAttemptCompletion[statusCode=" + statusCode + ", error="
                + (error == null ? "none" : error.getClass().getName()) + ", callId=" + callId + ", attemptOrdinal="
                + attemptOrdinal + ", durationMs=" + durationMs + ", completedAt=" + completedAt + ", target=" + target
                + "]";
    }
}
