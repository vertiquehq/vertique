// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.Objects;

/**
 * Thrown by {@code WorkflowOperations.result} when the stored output is not of the requested type: the stored output
 * type name matches none of the names of the requested class, or the stored JSON does not decode into it.
 *
 * <p>This is a business-rule failure by the caller. The message names the instance and both type names, never the
 * stored value.
 */
public class WorkflowResultTypeMismatchException extends WorkflowException {

    private final WorkflowInstanceId instanceId;
    private final String storedTypeName;
    private final String requestedTypeName;

    /**
     * Creates the exception for a result of another type than the one requested.
     *
     * @param instanceId the id of the instance that was read
     * @param storedTypeName the output type name stored with the instance
     * @param requestedTypeName the logical name of the requested class
     */
    public WorkflowResultTypeMismatchException(
            WorkflowInstanceId instanceId, String storedTypeName, String requestedTypeName) {
        super("Workflow instance '"
                + Objects.requireNonNull(instanceId, "instanceId").value()
                + "' stored a result of type '" + Objects.requireNonNull(storedTypeName, "storedTypeName")
                + "', which is not the requested type '"
                + Objects.requireNonNull(requestedTypeName, "requestedTypeName")
                + "'.");
        this.instanceId = instanceId;
        this.storedTypeName = storedTypeName;
        this.requestedTypeName = requestedTypeName;
    }

    /**
     * Returns the id of the instance that was read.
     *
     * @return the instance id
     */
    public WorkflowInstanceId instanceId() {
        return instanceId;
    }

    /**
     * Returns the output type name stored with the instance.
     *
     * @return the stored type name
     */
    public String storedTypeName() {
        return storedTypeName;
    }

    /**
     * Returns the logical name of the requested class.
     *
     * @return the requested type name
     */
    public String requestedTypeName() {
        return requestedTypeName;
    }
}
