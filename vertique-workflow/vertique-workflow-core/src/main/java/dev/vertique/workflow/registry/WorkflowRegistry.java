// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import java.util.Collection;
import java.util.Optional;

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
     * Returns the {@link RuntimeWorkflow} for the highest-version plan of the given definition that is
     * not marked deprecated.
     *
     * @param definitionId id of the workflow definition
     * @return the runtime workflow for the current (highest non-deprecated) version
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionMissingException if no definition with
     *     the given id is registered, or every registered version of it is deprecated
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
    /** Removes a version and its contract mapping. Unsupported registries fail explicitly. */
    default void unregister(String definitionId, long version) {
        throw new UnsupportedOperationException("unregister is not supported");
    }

    /**
     * Marks one registered version of a definition as deprecated. A deprecated version is skipped
     * by current resolution and by explicit new starts and migration targets, yet remains available
     * through pinned resolution so in-flight instances keep running. Unregistering the version
     * clears the mark.
     *
     * <p>Implementations must make the mark atomic with registration changes, so that no caller
     * observes a version that is registered but not yet marked after this method returns. The
     * default implementation throws, so registries that do not support deprecation fail explicitly.
     *
     * @param definitionId id of the workflow definition
     * @param version the exact plan version to mark as deprecated
     * @throws UnsupportedOperationException if this registry does not support deprecation
     */
    default void markDeprecated(String definitionId, long version) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support the markDeprecated operation");
    }

    /**
     * Returns whether one registered version of a definition is marked deprecated.
     *
     * <p>Implementations must read the mark atomically with registration changes. The default
     * implementation returns {@code false}, which preserves the behavior of existing registries
     * that do not track deprecation.
     *
     * @param definitionId id of the workflow definition
     * @param version the exact plan version to check
     * @return {@code true} if the version is registered and marked deprecated; {@code false}
     *     otherwise
     */
    default boolean isDeprecated(String definitionId, long version) {
        return false;
    }

    /**
     * Returns the definition that was passed to {@link #register} for one version.
     *
     * <p>This is an internal framework seam: the engine uses it to keep the source of the definitions
     * that the deployed code provides, so it can publish them again after the registry dropped them.
     * Applications do not call it. The default implementation returns an empty result, which preserves
     * the behavior of existing registries that do not retain their sources.
     *
     * @param definitionId id of the workflow definition
     * @param version the exact plan version
     * @return the definition registered for this version, or empty when none is registered or this
     *     registry does not retain sources
     */
    default Optional<WorkflowDefinition<?, ?>> registeredDefinition(String definitionId, long version) {
        return Optional.empty();
    }
}
