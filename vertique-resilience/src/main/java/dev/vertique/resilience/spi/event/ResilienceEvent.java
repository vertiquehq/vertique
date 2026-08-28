// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.spi.event;

/** Sealed root for the redacted resilience observation vocabulary. */
public sealed interface ResilienceEvent
        permits ExecutionStarted,
                ExecutionCompleted,
                AttemptStarted,
                AttemptCompleted,
                RetryScheduled,
                RetryExhausted,
                PolicyEvaluationFailed,
                TimeoutTriggered,
                CircuitStateChanged,
                CircuitCallRejected,
                BulkheadQueued,
                BulkheadAdmitted,
                BulkheadRejected,
                BulkheadQueueTimedOut {}
