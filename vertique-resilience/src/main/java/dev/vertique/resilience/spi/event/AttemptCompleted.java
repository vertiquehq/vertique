// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks completion of one supplier attempt without exposing its failure. */
public record AttemptCompleted(
        String operationKey, long executionId, int attemptOrdinal, ResilienceOutcomeCategory outcome, long elapsedMs)
        implements ResilienceEvent {
    public AttemptCompleted {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        attemptOrdinal = EventValidation.positive(attemptOrdinal, "attemptOrdinal");
        outcome = EventValidation.required(outcome, "outcome");
        elapsedMs = EventValidation.nonNegative(elapsedMs, "elapsedMs");
    }
}
