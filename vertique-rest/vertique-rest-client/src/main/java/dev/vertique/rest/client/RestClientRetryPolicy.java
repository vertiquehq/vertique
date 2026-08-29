// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.resilience.RetryPolicy;

/**
 * Determines whether a failed REST client request should be retried.
 *
 * <p>Extends the common {@link RetryPolicy} contract with REST client-specific context. The delay
 * between retries is computed separately by {@link dev.vertique.resilience.BackoffStrategy}.
 *
 * <p>The default implementation, {@link DefaultRestClientRetryPolicy}, retries transient failures:
 * connection errors, timeouts, and specific HTTP status codes (429, 502, 503, 504).
 *
 * <p>Method-level {@link dev.vertique.resilience.annotation.Retry} annotations can supplement or
 * override this policy:
 * <ul>
 *   <li>{@link dev.vertique.resilience.annotation.Retry#abortOn()} — always stops, highest priority</li>
 *   <li>{@link dev.vertique.resilience.annotation.Retry#retryOn()} — when non-empty, only matching
 *       types are retried (overrides this policy)</li>
 *   <li>When {@code retryOn} is empty, this policy is the fallback</li>
 * </ul>
 *
 * <p>Configure a custom policy on the builder:
 * <pre>{@code
 * RestClientBuilder.create(vertx)
 *     .retryPolicy((error, retryCount) -> error instanceof RestClientConnectionException)
 *     .build(MyClient.class);
 * }</pre>
 */
@FunctionalInterface
public interface RestClientRetryPolicy extends RetryPolicy {

    /**
     * Determines whether the given failure should be retried.
     *
     * @param error the failure from the most recent attempt
     * @param retryCount the 0-based retry count (0 = evaluating the first retry after the initial
     *     failure)
     * @return {@code true} to retry, {@code false} to abort
     */
    boolean shouldRetry(Throwable error, int retryCount);
}
