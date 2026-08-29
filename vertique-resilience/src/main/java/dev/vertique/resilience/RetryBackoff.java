// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;

/** Immutable, validated retry-delay policy. */
public sealed interface RetryBackoff permits RetryBackoff.Fixed, RetryBackoff.Exponential, RetryBackoff.Custom {

    /** Creates a bounded fixed-delay policy. */
    static Fixed fixed(long delayMs) {
        return new Fixed(delayMs);
    }

    /** Creates an exponential policy with the canonical one-second jitter bound. */
    static Exponential exponential(long initialDelayMs, double multiplier, long maxDelayMs) {
        return new Exponential(initialDelayMs, multiplier, maxDelayMs, 1_000L);
    }

    /** Creates an exponential policy with an explicit jitter bound. */
    static Exponential exponential(long initialDelayMs, double multiplier, long maxDelayMs, long maxJitterMs) {
        return new Exponential(initialDelayMs, multiplier, maxDelayMs, maxJitterMs);
    }

    /** Creates an unbounded-budget custom policy. */
    static Custom custom(BackoffStrategy delegate) {
        return new Custom(delegate);
    }

    /** A constant delay. */
    record Fixed(long delayMs) implements RetryBackoff {
        public Fixed {
            if (delayMs < 0) {
                throw new IllegalArgumentException("delayMs must be non-negative");
            }
        }
    }

    /** A capped exponential delay with bounded jitter. */
    record Exponential(long initialDelayMs, double multiplier, long maxDelayMs, long maxJitterMs)
            implements RetryBackoff {
        public Exponential {
            if (initialDelayMs < 0) {
                throw new IllegalArgumentException("initialDelayMs must be non-negative");
            }
            if (!Double.isFinite(multiplier) || multiplier < 1.0d) {
                throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
            }
            if (maxDelayMs < 0) {
                throw new IllegalArgumentException("maxDelayMs must be non-negative");
            }
            if (maxJitterMs < 0) {
                throw new IllegalArgumentException("maxJitterMs must be non-negative");
            }
        }
    }

    /** A caller-supplied delay strategy. */
    record Custom(BackoffStrategy delegate) implements RetryBackoff {
        public Custom {
            Objects.requireNonNull(delegate, "delegate");
        }
    }
}
