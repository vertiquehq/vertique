// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

/**
 * Determines whether a failed operation should be retried.
 *
 * <p>This interface is concerned only with eligibility. The delay between retries is computed
 * separately by {@link BackoffStrategy}.
 */
@FunctionalInterface
public interface RetryPolicy {

    /**
     * Determines whether the given failure should be retried.
     *
     * @param error the failure from the most recent attempt
     * @param retryCount the 0-based retry count
     * @return {@code true} to retry, {@code false} to abort
     */
    boolean shouldRetry(Throwable error, int retryCount);
}
