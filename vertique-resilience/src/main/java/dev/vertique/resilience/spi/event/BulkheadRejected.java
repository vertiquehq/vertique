// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks immediate bulkhead rejection. */
public record BulkheadRejected(
        String operationKey, long executionId, BulkheadMode mode, int activeCount, int queueDepth, int queueCapacity)
        implements ResilienceEvent {
    public BulkheadRejected {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        mode = EventValidation.required(mode, "mode");
        activeCount = EventValidation.nonNegative(activeCount, "activeCount");
        queueDepth = EventValidation.nonNegative(queueDepth, "queueDepth");
        queueCapacity = EventValidation.nonNegative(queueCapacity, "queueCapacity");
    }
}
