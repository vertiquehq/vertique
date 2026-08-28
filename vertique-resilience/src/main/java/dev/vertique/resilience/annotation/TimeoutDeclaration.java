// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Immutable metadata snapshot of a {@link Timeout} annotation. */
public record TimeoutDeclaration(long value, TimeUnit unit) {

    /** Validates the declaration's time unit. */
    public TimeoutDeclaration {
        Objects.requireNonNull(unit, "unit");
    }
}
