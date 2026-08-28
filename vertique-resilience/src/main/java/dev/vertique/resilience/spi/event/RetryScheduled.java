// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks a scheduled retry between one-based attempts. */
public record RetryScheduled(
        String operationKey,
        long executionId,
        int failedAttemptOrdinal,
        int nextAttemptOrdinal,
        long delayMs,
        ResilienceFailureCategory failureCategory)
        implements ResilienceEvent {
    public RetryScheduled {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        failedAttemptOrdinal = EventValidation.positive(failedAttemptOrdinal, "failedAttemptOrdinal");
        nextAttemptOrdinal = EventValidation.positive(nextAttemptOrdinal, "nextAttemptOrdinal");
        delayMs = EventValidation.nonNegative(delayMs, "delayMs");
        failureCategory = EventValidation.required(failureCategory, "failureCategory");
    }
}
