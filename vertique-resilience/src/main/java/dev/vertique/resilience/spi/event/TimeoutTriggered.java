// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks a timeout of one supplier attempt. */
public record TimeoutTriggered(String operationKey, long executionId, int attemptOrdinal, long timeoutMs)
        implements ResilienceEvent {
    public TimeoutTriggered {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        attemptOrdinal = EventValidation.positive(attemptOrdinal, "attemptOrdinal");
        timeoutMs = EventValidation.positive(timeoutMs, "timeoutMs");
    }
}
