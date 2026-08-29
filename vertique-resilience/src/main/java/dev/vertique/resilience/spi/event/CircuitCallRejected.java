// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks rejection by an open or half-open circuit. */
public record CircuitCallRejected(String operationKey, long executionId, String stateKey, CircuitState currentState)
        implements ResilienceEvent {
    public CircuitCallRejected {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        stateKey = EventValidation.key(stateKey);
        currentState = EventValidation.required(currentState, "currentState");
    }
}
