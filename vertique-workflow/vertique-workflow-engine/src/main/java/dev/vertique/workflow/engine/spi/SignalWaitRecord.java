// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An open wait record: one wait entry of an instance whose wait declares a computed correlation key.
 *
 * <p>INTERNAL: framework wiring between the engine and the wait record repository.
 *
 * <p>A record is addressed by the instance, the step, and the fork step and branch the wait belongs to; the fork step
 * and the branch are the empty string outside a branch. The correlation key is never part of the record's description,
 * so that a log line or an error that names the record does not repeat it.
 *
 * @param waitId the identity of this entry of the wait; a new entry of the same address has a new id
 * @param instanceId the instance that waits
 * @param stepId the step id of the wait
 * @param forkStepId the fork step of the branch that waits, or the empty string on the main path
 * @param branchId the branch that waits, or the empty string on the main path
 * @param scopeKey the start scope of the instance
 * @param contractName the logical name of the contract of the definition the instance is pinned to
 * @param definitionId the id of the definition the instance is pinned to
 * @param signalName the name of the signal the wait is for
 * @param correlationKey the key the wait computed on entry
 * @param createdAt when the entry was written
 */
public record SignalWaitRecord(
        UUID waitId,
        WorkflowInstanceId instanceId,
        String stepId,
        String forkStepId,
        String branchId,
        String scopeKey,
        String contractName,
        String definitionId,
        String signalName,
        String correlationKey,
        Instant createdAt) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public SignalWaitRecord {
        Objects.requireNonNull(waitId, "waitId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(forkStepId, "forkStepId");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(contractName, "contractName");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(signalName, "signalName");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Describes the record without its correlation key.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "SignalWaitRecord[waitId=" + waitId + ", instanceId=" + instanceId.value() + ", stepId=" + stepId
                + ", contractName=" + contractName + ", signalName=" + signalName + "]";
    }
}
