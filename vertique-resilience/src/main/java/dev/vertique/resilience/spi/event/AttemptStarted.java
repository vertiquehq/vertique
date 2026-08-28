// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks invocation of one one-based supplier attempt. */
public record AttemptStarted(String operationKey, long executionId, int attemptOrdinal) implements ResilienceEvent {
    public AttemptStarted {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        attemptOrdinal = EventValidation.positive(attemptOrdinal, "attemptOrdinal");
    }
}
