// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.OptionalLong;

/** A typed upper bound for a resilience execution duration. */
public sealed interface DurationBound permits DurationBound.Known, DurationBound.Unknown, DurationBound.Unbounded {

    /**
     * Returns the maximum duration when it is known.
     *
     * @return the known maximum, or empty when the bound is unknown or unbounded
     */
    default OptionalLong maximumMs() {
        return OptionalLong.empty();
    }

    /**
     * A finite duration, optionally saturated at {@link Long#MAX_VALUE}.
     *
     * @param valueMs maximum duration in milliseconds
     * @param saturated whether arithmetic reached the saturation limit
     */
    record Known(long valueMs, boolean saturated) implements DurationBound {
        public Known {
            if (valueMs < 0) {
                throw new IllegalArgumentException("valueMs must be non-negative");
            }
            if (saturated && valueMs != Long.MAX_VALUE) {
                throw new IllegalArgumentException("saturated duration must equal Long.MAX_VALUE");
            }
            if (valueMs == Long.MAX_VALUE && !saturated) {
                throw new IllegalArgumentException("Long.MAX_VALUE duration must be marked saturated");
            }
        }

        @Override
        public OptionalLong maximumMs() {
            return OptionalLong.of(valueMs);
        }
    }

    /**
     * A finite-duration shape whose maximum cannot be computed.
     *
     * @param reason reason the maximum is unavailable
     */
    record Unknown(ExecutionBudgetUnknownReason reason) implements DurationBound {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A duration with no finite upper bound. */
    record Unbounded() implements DurationBound {}
}
