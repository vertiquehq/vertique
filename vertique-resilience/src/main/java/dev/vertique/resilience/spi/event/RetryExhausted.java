// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks terminal retry exhaustion or ineligibility. */
public record RetryExhausted(
        String operationKey, long executionId, int attemptsMade, ResilienceFailureCategory failureCategory)
        implements ResilienceEvent {
    public RetryExhausted {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        attemptsMade = EventValidation.positive(attemptsMade, "attemptsMade");
        failureCategory = EventValidation.required(failureCategory, "failureCategory");
    }
}
