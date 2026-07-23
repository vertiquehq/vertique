// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import java.util.Collection;

/**
 * Central registry for workflow definitions and their runtime metadata.
 *
 * <p>Definitions are registered at application startup via {@link WorkflowContributor}
 * implementations contributed to the Dagger multibinding. The registry validates each definition
 * at registration time and rejects invalid plans with a {@code WorkflowDefinitionException}.
 *
 * <p>The registry is also the source of {@link WorkflowContractMetadata} for contract interfaces
 * used by {@code WorkflowClientFactory}.
 */
public interface WorkflowRegistry {

    /**
     * Registers a workflow definition, builds its plan via the DSL, and stores the resulting
     * {@link RuntimeWorkflow}.
     *
     * <p>Registration validates the plan: it must call {@code wf.init(...)} exactly once, and all
     * {@code WaitSignalNode} signal names must be unique within the plan. Violations throw
     * {@code WorkflowDefinitionException}.
     *
     * @param def the workflow definition to register
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if the plan is invalid
     */
    void register(WorkflowDefinition<?, ?> def);

    /**
     * Returns the {@link RuntimeWorkflow} for the highest-version plan of the given definition.
     *
     * @param definitionId id of the workflow definition
     * @return the runtime workflow for the current (highest) version
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionMissingException if no definition with
     *     the given id is registered
     */
    RuntimeWorkflow resolveCurrent(String definitionId);

    /**
     * Returns the {@link RuntimeWorkflow} for a specific version of the given definition.
     *
     * <p>This method is used by the engine when resuming an in-flight instance that was pinned to
     * a specific plan version at start time.
     *
     * @param definitionId id of the workflow definition
     * @param version the exact plan version to resolve
     * @return the runtime workflow for the specified version
     * @throws WorkflowVersionPinUnavailableException if the definition id is registered but the
     *     specified version is not; this exception carries the instanceId if the caller provides
     *     one
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionMissingException if no definition with
     *     the given id is registered at all
     */
    RuntimeWorkflow resolvePinned(String definitionId, long version);

    /**
     * Returns the {@link dev.vertique.workflow.contract.WorkflowContractMetadata} for the given
     * contract interface.
     *
     * @param contractInterface a contract interface annotated with
     *     {@link dev.vertique.workflow.contract.WorkflowContract}
     * @return the contract metadata for the interface
     * @throws dev.vertique.workflow.exception.WorkflowProxyContractException if the interface is not
     *     annotated with {@code @WorkflowContract} or is not linked to a registered definition
     */
    dev.vertique.workflow.contract.WorkflowContractMetadata contractMetadata(Class<?> contractInterface);

    /**
     * Returns an immutable snapshot of every registered {@link RuntimeWorkflow} across all
     * definitionId/version pairs. Used by startup validators to walk all plans without resolving
     * each one individually.
     *
     * @return immutable collection of all registered runtime workflows; never null, may be empty
     */
    Collection<RuntimeWorkflow> allRegistered();
}
