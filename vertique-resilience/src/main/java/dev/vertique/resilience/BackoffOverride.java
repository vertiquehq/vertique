// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Partial operation-level backoff configuration. */
public record BackoffOverride(
        Optional<BackoffStrategy> custom,
        OptionalLong initialDelayMs,
        Optional<Double> multiplier,
        OptionalLong maxDelayMs,
        OptionalLong maxJitterMs) {

    public BackoffOverride {
        Objects.requireNonNull(custom, "custom");
        Objects.requireNonNull(initialDelayMs, "initialDelayMs");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(maxDelayMs, "maxDelayMs");
        Objects.requireNonNull(maxJitterMs, "maxJitterMs");
        if (custom.isPresent()
                && (initialDelayMs.isPresent()
                        || multiplier.isPresent()
                        || maxDelayMs.isPresent()
                        || maxJitterMs.isPresent())) {
            throw new IllegalArgumentException("custom and scalar backoff fields are mutually exclusive");
        }
        validateNonNegative(initialDelayMs, "initialDelayMs");
        validateNonNegative(maxDelayMs, "maxDelayMs");
        validateNonNegative(maxJitterMs, "maxJitterMs");
        if (multiplier.isPresent() && (!Double.isFinite(multiplier.orElseThrow()) || multiplier.orElseThrow() < 1.0d)) {
            throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
        }
    }

    private static void validateNonNegative(OptionalLong value, String name) {
        if (value.isPresent() && value.getAsLong() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
