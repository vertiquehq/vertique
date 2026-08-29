// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Marks timeout of one queued bulkhead execution. */
public record BulkheadQueueTimedOut(
        String operationKey, long executionId, long queueWaitMs, long configuredQueueTimeoutMs)
        implements ResilienceEvent {
    public BulkheadQueueTimedOut {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        queueWaitMs = EventValidation.nonNegative(queueWaitMs, "queueWaitMs");
        configuredQueueTimeoutMs = EventValidation.positive(configuredQueueTimeoutMs, "configuredQueueTimeoutMs");
    }
}
