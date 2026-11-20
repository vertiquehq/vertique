// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Partial operation-level retry configuration.
 *
 * @param enabled whether the retry concern is enabled
 * @param maxRetries the retry count
 * @param backoff the backoff override
 * @param retryOn the retryable failure types
 * @param abortOn the failure types that are never retried
 * @param fallbackPolicy the eligibility policy used when {@code retryOn} is empty
 * @param retryAfterMaxMs the longest failure delay hint to honor in milliseconds, {@code >= 0}, or
 *     {@code null} when unset
 */
public record RetryOverride(
        Optional<Boolean> enabled,
        OptionalInt maxRetries,
        Optional<BackoffOverride> backoff,
        Optional<Set<Class<? extends Throwable>>> retryOn,
        Optional<Set<Class<? extends Throwable>>> abortOn,
        Optional<RetryPolicy> fallbackPolicy,
        Long retryAfterMaxMs) {

    /** Creates an override without a delay hint maximum. */
    public RetryOverride(
            Optional<Boolean> enabled,
            OptionalInt maxRetries,
            Optional<BackoffOverride> backoff,
            Optional<Set<Class<? extends Throwable>>> retryOn,
            Optional<Set<Class<? extends Throwable>>> abortOn,
            Optional<RetryPolicy> fallbackPolicy) {
        this(enabled, maxRetries, backoff, retryOn, abortOn, fallbackPolicy, null);
    }

    public RetryOverride {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(maxRetries, "maxRetries");
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(retryOn, "retryOn");
        Objects.requireNonNull(abortOn, "abortOn");
        Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        if (maxRetries.isPresent() && (maxRetries.getAsInt() < 0 || maxRetries.getAsInt() > 100)) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 100");
        }
        if (retryAfterMaxMs != null && retryAfterMaxMs < 0L) {
            throw new IllegalArgumentException("retryAfterMaxMs must be >= 0");
        }
        retryOn = copySet(retryOn, "retryOn");
        abortOn = copySet(abortOn, "abortOn");
        if (enabled.orElse(null) != null
                && !enabled.orElseThrow()
                && hasSiblingValue(maxRetries, backoff, retryOn, abortOn, fallbackPolicy, retryAfterMaxMs)) {
            throw new IllegalArgumentException("disabled retry override cannot contain retry values");
        }
    }

    private static boolean hasSiblingValue(
            OptionalInt maxRetries,
            Optional<BackoffOverride> backoff,
            Optional<Set<Class<? extends Throwable>>> retryOn,
            Optional<Set<Class<? extends Throwable>>> abortOn,
            Optional<RetryPolicy> fallbackPolicy,
            Long retryAfterMaxMs) {
        return maxRetries.isPresent()
                || retryAfterMaxMs != null
                || backoff.isPresent()
                || retryOn.isPresent()
                || abortOn.isPresent()
                || fallbackPolicy.isPresent();
    }

    private static Optional<Set<Class<? extends Throwable>>> copySet(
            Optional<Set<Class<? extends Throwable>>> value, String name) {
        return value.map(values -> {
            Objects.requireNonNull(values, name);
            return Set.copyOf(values);
        });
    }
}
