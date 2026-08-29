// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks admission from a queue-mode bulkhead. */
public record BulkheadAdmitted(String operationKey, long executionId, long queueWaitMs, int activeCount)
        implements ResilienceEvent {
    public BulkheadAdmitted {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        queueWaitMs = EventValidation.nonNegative(queueWaitMs, "queueWaitMs");
        activeCount = EventValidation.positive(activeCount, "activeCount");
    }
}
