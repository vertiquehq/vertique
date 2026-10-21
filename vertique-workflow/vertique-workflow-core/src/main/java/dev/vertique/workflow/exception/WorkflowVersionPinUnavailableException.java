// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;

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

    /** The scope that owns the definition the instance is pinned to. */
    private final String scopeKey;

    /** The workflow definition id that the instance belongs to. */
    private final String definitionId;

    /** The specific plan version that is no longer registered. */
    private final long version;

    /** The id of the in-flight instance that cannot be advanced. */
    private final WorkflowInstanceId instanceId;

    /**
     * Creates a new {@code WorkflowVersionPinUnavailableException} for a definition in the default scope.
     *
     * @param definitionId the workflow definition id
     * @param version the pinned version that is no longer registered
     * @param instanceId the id of the in-flight instance
     */
    public WorkflowVersionPinUnavailableException(String definitionId, long version, WorkflowInstanceId instanceId) {
        this(WorkflowScopes.DEFAULT, definitionId, version, instanceId);
    }

    /**
     * Creates a new {@code WorkflowVersionPinUnavailableException}.
     *
     * @param scopeKey the scope that owns the definition
     * @param definitionId the workflow definition id
     * @param version the pinned version that is no longer registered
     * @param instanceId the id of the in-flight instance, or {@code null} when the lookup was not made for an
     *     instance
     */
    public WorkflowVersionPinUnavailableException(
            String scopeKey, String definitionId, long version, WorkflowInstanceId instanceId) {
        super("Workflow definition '"
                + EchoedNames.bounded(definitionId)
                + "' version "
                + version
                + " in scope '"
                + EchoedNames.bounded(scopeKey)
                + "' is no longer registered"
                + (instanceId != null
                        ? "; in-flight instance " + instanceId.value()
                                + " cannot be advanced. Restore the definition version or cancel the"
                                + " instance via management APIs."
                        : ". Restore the definition version or re-register it."));
        this.scopeKey = scopeKey;
        this.definitionId = definitionId;
        this.version = version;
        this.instanceId = instanceId;
    }

    /**
     * Returns the scope that owns the definition the instance is pinned to.
     *
     * @return the scope key
     */
    public String scopeKey() {
        return scopeKey;
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
