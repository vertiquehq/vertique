// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Partial operation-level retry configuration. */
public record RetryOverride(
        Optional<Boolean> enabled,
        OptionalInt maxRetries,
        Optional<BackoffOverride> backoff,
        Optional<Set<Class<? extends Throwable>>> retryOn,
        Optional<Set<Class<? extends Throwable>>> abortOn,
        Optional<RetryPolicy> fallbackPolicy) {

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
        retryOn = copySet(retryOn, "retryOn");
        abortOn = copySet(abortOn, "abortOn");
        if (enabled.orElse(null) != null
                && !enabled.orElseThrow()
                && hasSiblingValue(maxRetries, backoff, retryOn, abortOn, fallbackPolicy)) {
            throw new IllegalArgumentException("disabled retry override cannot contain retry values");
        }
    }

    private static boolean hasSiblingValue(
            OptionalInt maxRetries,
            Optional<BackoffOverride> backoff,
            Optional<Set<Class<? extends Throwable>>> retryOn,
            Optional<Set<Class<? extends Throwable>>> abortOn,
            Optional<RetryPolicy> fallbackPolicy) {
        return maxRetries.isPresent()
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
