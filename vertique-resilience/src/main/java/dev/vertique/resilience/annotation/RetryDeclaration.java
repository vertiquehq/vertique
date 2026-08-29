// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import dev.vertique.resilience.BackoffStrategy;
import java.util.List;
import java.util.Objects;

/** Immutable metadata snapshot of a {@link Retry} annotation. */
public record RetryDeclaration(
        int maxRetries,
        long delayMs,
        double backoffMultiplier,
        long maxDelayMs,
        Class<? extends BackoffStrategy> backoffClass,
        List<Class<? extends Throwable>> retryOn,
        List<Class<? extends Throwable>> abortOn) {

    /** Validates and defensively copies collection members. */
    public RetryDeclaration {
        Objects.requireNonNull(backoffClass, "backoffClass");
        retryOn = List.copyOf(retryOn);
        abortOn = List.copyOf(abortOn);
    }
}
