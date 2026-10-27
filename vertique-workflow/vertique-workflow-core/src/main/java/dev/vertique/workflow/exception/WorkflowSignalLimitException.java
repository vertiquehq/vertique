// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * Thrown when a signal cannot be recorded because its instance already holds the configured number of open signals
 * ({@code workflow.recording.maxOpenSignalsPerInstance}).
 *
 * <p>The recording fails in the caller's transaction and writes nothing: no command, no history entry and no
 * deduplication key, so the caller can send the same signal again once an open signal has settled. The failure is
 * not retryable inside the same call. The message names the instance and the limit, never a payload.
 */
public class WorkflowSignalLimitException extends WorkflowException {

    private final WorkflowInstanceId instanceId;
    private final int limit;

    /**
     * Creates the exception for an instance that holds {@code limit} open signals.
     *
     * @param instanceId the instance that holds the open signals
     * @param limit the configured maximum number of open signals per instance
     */
    public WorkflowSignalLimitException(WorkflowInstanceId instanceId, int limit) {
        super("Instance '" + instanceId.value() + "' already holds " + limit
                + " open signals, the configured maximum (workflow.recording.maxOpenSignalsPerInstance)");
        this.instanceId = instanceId;
        this.limit = limit;
    }

    /**
     * The instance that holds the open signals.
     *
     * @return the instance id
     */
    public WorkflowInstanceId instanceId() {
        return instanceId;
    }

    /**
     * The configured maximum number of open signals per instance.
     *
     * @return the limit
     */
    public int limit() {
        return limit;
    }
}
