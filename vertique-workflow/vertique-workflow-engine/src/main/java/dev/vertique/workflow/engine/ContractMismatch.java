// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.types.EchoedNames;
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
        return refusal(
                inst,
                new DefinitionKey(
                        inst.definitionScopeKey(),
                        pinned.plan().definitionId(),
                        pinned.plan().definitionVersion()),
                pinned.contractName(),
                callerContractName,
                operation);
    }

    /**
     * Compares the caller's contract with the contract that the pinned definition's stored row holds, for a node that
     * needs no plan to answer.
     *
     * @param inst the addressed instance
     * @param pinnedKey the scope, definition id and version the instance is pinned to
     * @param pinnedContractName the contract name stored for the pinned definition
     * @param callerContractName the contract name of the caller, or {@code null} when the caller names none
     * @param operation what the caller attempted, for example {@code "query"}
     * @return the refusal, or {@code null} when the caller names no contract or names the pinned one
     */
    static @Nullable WorkflowDefinitionContractMismatchException refusal(
            WorkflowInstance inst,
            DefinitionKey pinnedKey,
            String pinnedContractName,
            @Nullable String callerContractName,
            String operation) {
        if (callerContractName == null || callerContractName.equals(pinnedContractName)) {
            return null;
        }
        return new WorkflowDefinitionContractMismatchException(
                pinnedKey,
                "Instance '" + inst.id().value() + "' is pinned to '" + pinnedKey.definitionId() + "' v"
                        + pinnedKey.definitionVersion() + ", which implements contract '" + pinnedContractName
                        + "'; a client of contract '" + EchoedNames.bounded(callerContractName) + "' cannot "
                        + operation + " it");
    }
}
