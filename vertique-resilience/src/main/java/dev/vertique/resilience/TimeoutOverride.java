// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Partial operation-level timeout configuration. */
public record TimeoutOverride(Optional<Boolean> enabled, OptionalLong timeoutMs) {
    public TimeoutOverride {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(timeoutMs, "timeoutMs");
        if (timeoutMs.isPresent() && timeoutMs.getAsLong() <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (enabled.orElse(null) != null && !enabled.orElseThrow() && timeoutMs.isPresent()) {
            throw new IllegalArgumentException("disabled timeout override cannot contain timeoutMs");
        }
    }
}
