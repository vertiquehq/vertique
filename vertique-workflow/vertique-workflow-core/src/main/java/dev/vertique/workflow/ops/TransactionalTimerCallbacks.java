// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import io.vertx.core.Future;
import java.util.UUID;

/**
 * INTERNAL SPI — consumed only by {@code vertique-workflow-delayed}; never exposed to
 * application code or signal contributors.
 *
 * <p>Provides transactional callbacks that the timer executor invokes when a scheduled workflow
 * timer fires or fails. Each method is called inside an existing database transaction opened by
 * the caller; implementations must not open their own transactions.
 *
 * <p>This interface is generic over the transaction context type {@code TX} so that the
 * PostgreSQL implementation can use {@code SqlClient} without the core module depending on the
 * pg-client library.
 *
 * <p>Application code and signal contributors must never call these methods directly. They are
 * part of the internal wiring between the delayed-job executor and the workflow engine.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL stack)
 */
public interface TransactionalTimerCallbacks<TX> {

    /**
     * Called by the timer executor when a scheduled timer has fired and the workflow should be
     * resumed or advanced to a timeout branch.
     *
     * <p>If the timer is no longer in {@link dev.vertique.workflow.timer.TimerStatus#SCHEDULED}
     * status (e.g., it was already cancelled or a duplicate delivery arrived), the implementation
     * returns {@link TimerFiringResult#STALE_NOOP} rather than failing.
     *
     * @param workflowId the workflow instance this timer belongs to
     * @param timerId the stable UUID of the timer that fired
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the firing result
     */
    Future<TimerFiringResult> timerFired(WorkflowInstanceId workflowId, UUID timerId, TX tx);

    /**
     * Called by the timer executor when the attempt to fire a scheduled timer has permanently
     * failed (e.g., the underlying delayed job failed after exhausting retries).
     *
     * <p>Implementations should mark the timer as {@link dev.vertique.workflow.timer.TimerStatus#FAILED},
     * append a {@link dev.vertique.workflow.state.WorkflowEntryType#TIMER_FAILED} history entry,
     * and leave the workflow instance in a state that allows manual intervention or retry. If the
     * timer is already in a terminal status, the implementation should return
     * {@link TimerFiringResult#STALE_NOOP}.
     *
     * @param workflowId the workflow instance this timer belongs to
     * @param timerId the stable UUID of the timer that failed to fire
     * @param errorType the error category reported by the delayed job executor
     * @param errorMessage human-readable description of the failure cause
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the firing result
     */
    Future<TimerFiringResult> timerFiringFailed(
            WorkflowInstanceId workflowId, UUID timerId, String errorType, String errorMessage, TX tx);
}
