// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;

import java.util.Optional;
import java.util.OptionalLong;

/** Marks a local circuit state transition. */
public record CircuitStateChanged(
        String stateKey,
        CircuitState oldState,
        CircuitState newState,
        long failureCount,
        Optional<String> triggeringOperationKey,
        OptionalLong triggeringExecutionId)
        implements ResilienceEvent {
    public CircuitStateChanged {
        stateKey = EventValidation.key(stateKey);
        oldState = EventValidation.required(oldState, "oldState");
        newState = EventValidation.required(newState, "newState");
        failureCount = EventValidation.nonNegative(failureCount, "failureCount");
        triggeringOperationKey = EventValidation.optionalKey(triggeringOperationKey);
        triggeringExecutionId = EventValidation.optionalId(triggeringExecutionId);
    }
}
