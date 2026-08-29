// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;

/** Partial operation-level bulkhead configuration. */
public record BulkheadOverride(Optional<Boolean> enabled, Optional<BulkheadConfig> config) {
    public BulkheadOverride {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(config, "config");
        if (enabled.orElse(null) != null && !enabled.orElseThrow() && config.isPresent()) {
            throw new IllegalArgumentException("disabled bulkhead override cannot contain config");
        }
    }
}
