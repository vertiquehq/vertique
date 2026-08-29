// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

/** Immutable metadata snapshot of a {@link CircuitBreaker} annotation. */
public record CircuitBreakerDeclaration(int maxFailures, long timeoutMs, long resetTimeoutMs) {}
