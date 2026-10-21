// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import jakarta.annotation.Nullable;

/**
 * Package-private check that a caller's contract is the contract of the definition version an
 * instance is pinned to.
 *
 * <p>The refusal names the instance's definition id and version and both contract names, never a
 * payload or state.
 */
final class ContractMismatch {

    private ContractMismatch() {}

    /**
     * Compares the caller's contract with the pinned definition's contract.
     *
     * @param inst the addressed instance
     * @param pinned the runtime workflow the instance is pinned to
     * @param callerContractName the contract name of the caller, or {@code null} when the caller names
     *     none
     * @param operation what the caller attempted, for example {@code "signal"} or {@code "query"}
     * @return the refusal, or {@code null} when the caller names no contract or names the pinned one
     */
    static @Nullable WorkflowDefinitionContractMismatchException refusal(
            WorkflowInstance inst, RuntimeWorkflow pinned, @Nullable String callerContractName, String operation) {
        if (callerContractName == null || callerContractName.equals(pinned.contractName())) {
            return null;
        }
        String definitionId = pinned.plan().definitionId();
        long version = pinned.plan().definitionVersion();
        return new WorkflowDefinitionContractMismatchException(
                new DefinitionKey(null, definitionId, version),
                "Instance '" + inst.id().value() + "' is pinned to '" + definitionId + "' v" + version
                        + ", which implements contract '" + pinned.contractName() + "'; a client of contract '"
                        + callerContractName + "' cannot " + operation + " it");
    }
}
