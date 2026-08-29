// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks terminal settlement of one logical execution. */
public record ExecutionCompleted(
        String operationKey, long executionId, ResilienceOutcomeCategory outcome, long elapsedMs)
        implements ResilienceEvent {
    public ExecutionCompleted {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        outcome = EventValidation.required(outcome, "outcome");
        elapsedMs = EventValidation.nonNegative(elapsedMs, "elapsedMs");
    }
}
