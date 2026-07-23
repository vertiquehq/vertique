// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * Thrown when a workflow instance is pinned to a specific definition version that is no longer
 * registered in the {@code WorkflowRegistry}.
 *
 * <p>This exception is thrown by the engine when resuming an in-flight instance (via signal,
 * cancel, or retry) if the plan version the instance was started against has been removed. The
 * calling transaction is rolled back; no state mutation occurs.
 *
 * <p>Resolution: restore the definition version, or cancel the instance via management APIs.
 */
public class WorkflowVersionPinUnavailableException extends WorkflowUnavailableException {

    /** The workflow definition id that the instance belongs to. */
    private final String definitionId;

    /** The specific plan version that is no longer registered. */
    private final long version;

    /** The id of the in-flight instance that cannot be advanced. */
    private final WorkflowInstanceId instanceId;

    /**
     * Creates a new {@code WorkflowVersionPinUnavailableException}.
     *
     * @param definitionId the workflow definition id
     * @param version the pinned version that is no longer registered
     * @param instanceId the id of the in-flight instance
     */
    public WorkflowVersionPinUnavailableException(String definitionId, long version, WorkflowInstanceId instanceId) {
        super("Workflow definition '"
                + definitionId
                + "' version "
                + version
                + " is no longer registered"
                + (instanceId != null
                        ? "; in-flight instance " + instanceId.value()
                                + " cannot be advanced. Restore the definition version or cancel the"
                                + " instance via management APIs."
                        : ". Restore the definition version or re-register it."));
        this.definitionId = definitionId;
        this.version = version;
        this.instanceId = instanceId;
    }

    /**
     * Returns the workflow definition id that the instance belongs to.
     *
     * @return the definition id
     */
    public String definitionId() {
        return definitionId;
    }

    /**
     * Returns the pinned version that is no longer registered.
     *
     * @return the unavailable version number
     */
    public long version() {
        return version;
    }

    /**
     * Returns the id of the in-flight instance that cannot be advanced.
     *
     * @return the workflow instance id
     */
    public WorkflowInstanceId instanceId() {
        return instanceId;
    }
}
