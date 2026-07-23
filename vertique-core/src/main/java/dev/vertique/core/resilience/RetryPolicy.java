// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

/**
 * Determines whether a failed operation should be retried.
 *
 * <p>This interface is concerned only with eligibility — the decision to retry or abort. The delay
 * between retries is computed separately by {@link BackoffStrategy}.
 *
 * <p>Method-level {@link Retry} annotations can supplement or override this policy:
 *
 * <ul>
 *   <li>{@link Retry#abortOn()} — always stops, highest priority</li>
 *   <li>{@link Retry#retryOn()} — when non-empty, only matching types are retried (overrides this
 *       policy)</li>
 *   <li>When {@code retryOn} is empty, this policy is the fallback</li>
 * </ul>
 */
@FunctionalInterface
public interface RetryPolicy {

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
