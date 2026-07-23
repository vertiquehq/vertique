// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

/**
 * Resolved metadata for a delayed-job target, including the durable target id and
 * effective execution defaults.
 *
 * <p>For typed delayed-job contracts, {@code targetId} equals {@code handlerName}
 * (both are {@link DelayedJobContract#name()}). Both fields are included because
 * {@code handlerName} is a service-dispatch concern (used by {@link DelayedJobService})
 * while {@code targetId} is a durable-identity concern (used by outbox integrations).
 *
 * @param targetId durable target id (the {@link DelayedJobContract#name()} value)
 * @param handlerName handler name for service dispatch
 * @param handlerAddress current runtime event bus address
 * @param queue effective queue name
 * @param priority effective priority
 * @param maxAttempts effective max attempts
 */
public record ResolvedDelayedJobTarget(
        String targetId, String handlerName, String handlerAddress, String queue, int priority, int maxAttempts) {}
