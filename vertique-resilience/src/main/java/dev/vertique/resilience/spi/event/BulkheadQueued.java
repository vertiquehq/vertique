// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks insertion into a bounded bulkhead queue. */
public record BulkheadQueued(String operationKey, long executionId, int queueDepth, int queueCapacity)
        implements ResilienceEvent {
    public BulkheadQueued {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        queueDepth = EventValidation.positive(queueDepth, "queueDepth");
        queueCapacity = EventValidation.positive(queueCapacity, "queueCapacity");
    }
}
