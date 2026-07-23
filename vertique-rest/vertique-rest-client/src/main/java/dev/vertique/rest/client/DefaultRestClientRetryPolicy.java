// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConnectionException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.exception.RestClientTimeoutException;
import java.util.Set;

/**
 * Default {@link RestClientRetryPolicy} that retries common transient failures.
 *
 * <p>The following failures are considered retryable:
 * <ul>
 *   <li>{@link RestClientConnectionException} — transport-level errors (connection refused, unknown
 *       host)</li>
 *   <li>{@link RestClientTimeoutException} — request timeout exceeded</li>
 *   <li>{@link RestClientResponseException} with status 429 — rate limited</li>
 *   <li>{@link RestClientResponseException} with status 502 — bad gateway</li>
 *   <li>{@link RestClientResponseException} with status 503 — service unavailable</li>
 *   <li>{@link RestClientResponseException} with status 504 — gateway timeout</li>
 * </ul>
 *
 * <p>All other failures (e.g. 400 Bad Request, 401 Unauthorized, 404 Not Found, 409 Conflict) are
 * considered permanent and are not retried.
 */
public final class DefaultRestClientRetryPolicy implements RestClientRetryPolicy {

    /** HTTP status codes that indicate a transient server-side condition worth retrying. */
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

    /**
     * Creates a new instance of the default retry policy.
     */
    public DefaultRestClientRetryPolicy() {}

    /**
     * Returns {@code true} for connection errors, timeouts, and transient HTTP status codes
     * (429, 502, 503, 504).
     *
     * @param error the failure from the current attempt
     * @param retryCount the 0-based retry count
     * @return {@code true} if the failure is considered transient and retryable
     */
    @Override
    public boolean shouldRetry(Throwable error, int retryCount) {
        if (error instanceof RestClientConnectionException) {
            return true;
        }
        if (error instanceof RestClientTimeoutException) {
            return true;
        }
        if (error instanceof RestClientResponseException responseEx) {
            return RETRYABLE_STATUS_CODES.contains(responseEx.statusCode());
        }
        return false;
    }
}
