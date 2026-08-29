// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;

import java.util.Set;

/** Marks admission of one logical execution. */
public record ExecutionStarted(String operationKey, long executionId, Set<ResilienceConcern> enabledConcerns)
        implements ResilienceEvent {
    public ExecutionStarted {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        enabledConcerns = EventValidation.concerns(enabledConcerns);
    }
}
