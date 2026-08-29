// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.time.Duration;
import java.util.Objects;

/** Immutable configuration for a per-attempt timeout.
 *
 * @param timeoutMs positive timeout in milliseconds
 */
public record TimeoutConfig(long timeoutMs) {

    /**
     * Creates a timeout configuration from an exact positive millisecond value.
     *
     * @param timeoutMs positive timeout in milliseconds
     * @return the validated timeout configuration
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive
     */
    public static TimeoutConfig ofMillis(long timeoutMs) {
        return new TimeoutConfig(timeoutMs);
    }

    /**
     * Creates a timeout configuration from an exact positive millisecond duration.
     *
     * @param timeout positive, millisecond-representable duration
     * @return the validated timeout configuration
     * @throws NullPointerException if {@code timeout} is {@code null}
     * @throws IllegalArgumentException if the duration is not positive, is sub-millisecond, or
     *     cannot be represented as milliseconds
     */
    public static TimeoutConfig of(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (timeout.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("timeout must be millisecond-representable");
        }

        final long timeoutMs;
        try {
            timeoutMs = Math.addExact(Math.multiplyExact(timeout.getSeconds(), 1_000L), timeout.getNano() / 1_000_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout does not fit in milliseconds", overflow);
        }
        return new TimeoutConfig(timeoutMs);
    }

    public TimeoutConfig {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
    }
}
