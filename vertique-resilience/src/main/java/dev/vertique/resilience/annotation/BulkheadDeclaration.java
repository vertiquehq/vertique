// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

/** Immutable metadata snapshot of a {@link Bulkhead} annotation. */
public record BulkheadDeclaration(int maxConcurrentCalls, Bulkhead.Mode mode, int maxQueueSize, long queueTimeoutMs) {}
