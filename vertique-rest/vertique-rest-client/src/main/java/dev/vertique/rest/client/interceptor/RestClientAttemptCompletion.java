// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * One physical HTTP attempt's completion, passed to {@link RestClientInterceptor#onAttemptCompleted}.
 * Exactly one of {@code response} / {@code error} is non-null.
 *
 * @param response       the HTTP response when the attempt produced one (any status), else
 *                       {@code null}
 * @param error          the raw transport failure when the attempt failed, else {@code null}
 * @param callId         id unique to this logical client call (the per-call dedup root)
 * @param attemptOrdinal 1-based attempt number within this logical call (spans retries + recovery)
 * @param durationMs     monotonic elapsed time of this attempt in milliseconds ({@code >= 0})
 * @param completedAt    wall-clock instant the attempt completed (use for {@code occurredAt})
 * @param target         safe-by-type target identity (scheme/host/port/pathTemplate)
 */
public record RestClientAttemptCompletion(
        @Nullable RestClientResponseContext response,
        @Nullable Throwable error,
        String callId,
        int attemptOrdinal,
        long durationMs,
        Instant completedAt,
        RestClientAttemptTarget target) {

    /** Validates the invariants. */
    public RestClientAttemptCompletion {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (attemptOrdinal < 1) {
            throw new IllegalArgumentException("attemptOrdinal must be >= 1, was " + attemptOrdinal);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, was " + durationMs);
        }
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if ((response == null) == (error == null)) {
            throw new IllegalArgumentException("exactly one of {response, error} must be non-null");
        }
    }
}
