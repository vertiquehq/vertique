// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * Thrown when a resolved workflow plan's {@code planHash} does not match the hash stored on an
 * in-flight instance.
 *
 * <p>This indicates that the plan content for a given definition id / version pair changed between
 * deployments without a version number bump — a packaging error. When this exception is thrown the
 * operation is aborted; no state mutation occurs.
 *
 * <p>Resolution: either restore the original plan (so the hash matches again) or increment the
 * definition version and redeploy.
 */
public class WorkflowPlanHashDriftException extends WorkflowConfigurationException {

    /** The workflow definition id whose plan content changed. */
    private final String definitionId;

    /** The definition version whose plan content changed. */
    private final long version;

    /** The id of the in-flight instance that cannot be advanced. */
    private final WorkflowInstanceId instanceId;

    /** The planHash stored on the instance at start time. */
    private final String storedHash;

    /** The planHash computed from the currently-registered plan. */
    private final String currentHash;

    /**
     * Creates a new {@code WorkflowPlanHashDriftException}.
     *
     * @param definitionId the workflow definition id
     * @param version the definition version whose plan content changed
     * @param instanceId the id of the in-flight instance that cannot be advanced
     * @param storedHash the planHash stored on the instance when it was started
     * @param currentHash the planHash computed from the currently-registered plan
     */
    public WorkflowPlanHashDriftException(
            String definitionId, long version, WorkflowInstanceId instanceId, String storedHash, String currentHash) {
        super("Workflow definition '"
                + definitionId
                + "' v"
                + version
                + " for instance "
                + instanceId.value()
                + ": plan hash drift detected (stored="
                + storedHash
                + ", current="
                + currentHash
                + "). The plan content changed without a version bump — packaging bug."
                + " Restore the original plan or bump the definition version.");
        this.definitionId = definitionId;
        this.version = version;
        this.instanceId = instanceId;
        this.storedHash = storedHash;
        this.currentHash = currentHash;
    }

    /**
     * Returns the workflow definition id whose plan content changed.
     *
     * @return the definition id
     */
    public String definitionId() {
        return definitionId;
    }

    /**
     * Returns the definition version whose plan content changed.
     *
     * @return the version number
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

    /**
     * Returns the planHash stored on the instance when it was started.
     *
     * @return the stored plan hash
     */
    public String storedHash() {
        return storedHash;
    }

    /**
     * Returns the planHash computed from the currently-registered plan.
     *
     * @return the current plan hash
     */
    public String currentHash() {
        return currentHash;
    }
}
